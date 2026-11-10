package dev.vitrail.render.upscale;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Windows x64 NGX bridge. Loading it does not enable DLSS in the render chain. */
public final class DlssBridge implements AutoCloseable {

	public enum Quality {
		PERFORMANCE(0), BALANCED(1), QUALITY(2), ULTRA_PERFORMANCE(3), DLAA(5);

		private final int nativeId;

		Quality(int nativeId) {
			this.nativeId = nativeId;
		}
	}

	/** Dimensions returned by NGX, rather than guessed render-scale percentages. */
	public record OptimalSize(int width, int height, int minWidth, int minHeight,
			int maxWidth, int maxHeight) {
	}

	/** One mip, one layer. Depth is a sampled R32F color image containing forward depth. */
	public record Image(long image, long view, int format, int width, int height) {
		public Image {
			if (image == 0 || view == 0 || format <= 0 || width <= 0 || height <= 0) {
				throw new IllegalArgumentException("DLSS needs live, non-empty Vulkan images");
			}
		}

		private void write(MemorySegment into, long offset) {
			into.set(JAVA_LONG, offset, image);
			into.set(JAVA_LONG, offset + 8, view);
			into.set(JAVA_INT, offset + 16, format);
			into.set(JAVA_INT, offset + 20, width);
			into.set(JAVA_INT, offset + 24, height);
			into.set(JAVA_INT, offset + 28, 1); // VK_IMAGE_ASPECT_COLOR_BIT
		}
	}

	private final Arena libraryArena = Arena.ofShared();
	private final Thread owner = Thread.currentThread();
	private final SymbolLookup symbols;
	private final MethodHandle extensions;
	private final MethodHandle open;
	private final MethodHandle optimal;
	private final MethodHandle create;
	private final MethodHandle evaluate;
	private final MethodHandle release;
	private Session session;
	private boolean closed;

	@SuppressWarnings("restricted") // Versioned C ABI in native/dlss, never arbitrary pack code.
	public DlssBridge(Path library) throws IOException {
		if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
				|| !List.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) {
			libraryArena.close();
			throw new UnsupportedOperationException("The DLSS bridge currently supports Windows x64");
		}
		try {
			symbols = SymbolLookup.libraryLookup(library.toRealPath(), libraryArena);
			if (call(function("wv_dlss_abi", FunctionDescriptor.of(JAVA_INT))) != 1) {
				throw new IllegalStateException("Unsupported WynnVitrail DLSS bridge ABI");
			}
			extensions = function("wv_dlss_extensions", FunctionDescriptor.of(JAVA_INT,
					JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
			open = function("wv_dlss_open", FunctionDescriptor.of(JAVA_INT,
					JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
			optimal = function("wv_dlss_optimal", FunctionDescriptor.of(JAVA_INT,
					ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
			create = function("wv_dlss_create", FunctionDescriptor.of(JAVA_INT,
					ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
			evaluate = function("wv_dlss_evaluate_masked", FunctionDescriptor.of(JAVA_INT,
					ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT));
			release = function("wv_dlss_close", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		} catch (IOException | RuntimeException | Error e) {
			libraryArena.close();
			throw e;
		}
	}

	/** Query instance extensions with zero handles, then device extensions with both handles. */
	public List<String> extensions(long instance, long physical, Path runtimeDirectory) {
		checkThread();
		if ((instance == 0) != (physical == 0)) {
			throw new IllegalArgumentException("Supply either both Vulkan handles or neither");
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment text = arena.allocate(16384);
			check("query extensions", call(extensions, instance, physical,
					widePath(arena, runtimeDirectory), text, 16384));
			return text.getString(0, StandardCharsets.UTF_8).lines().filter(s -> !s.isBlank()).toList();
		}
	}

	/** Device must already have all extensions queried above enabled. */
	public Session open(long instance, long physical, long device, long getInstanceProc,
			long getDeviceProc, Path runtimeDirectory) {
		checkThread();
		if (session != null) {
			throw new IllegalStateException("Retire the current DLSS session before opening another");
		}
		if (instance == 0 || physical == 0 || device == 0 || getInstanceProc == 0 || getDeviceProc == 0) {
			throw new IllegalArgumentException("DLSS needs a live Vulkan device and loader functions");
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment output = arena.allocate(ADDRESS);
			check("initialize NGX", call(open, instance, physical, device, getInstanceProc,
					getDeviceProc, widePath(arena, runtimeDirectory), output));
			session = new Session(output.get(ADDRESS, 0));
			return session;
		}
	}

	public final class Session implements AutoCloseable {
		private final MemorySegment context;
		private int width;
		private int height;
		private int outputWidth;
		private int outputHeight;
		private boolean retired;
		private boolean firstFrame = true;

		private Session(MemorySegment context) {
			this.context = context;
		}

		public OptimalSize optimalSize(int displayWidth, int displayHeight, Quality quality) {
			checkSession();
			requireDimensions(displayWidth, displayHeight);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment sizes = arena.allocate(24, 4);
				check("query optimal size", call(optimal, context, displayWidth, displayHeight,
						Objects.requireNonNull(quality).nativeId, sizes));
				return new OptimalSize(sizes.get(JAVA_INT, 0), sizes.get(JAVA_INT, 4),
						sizes.get(JAVA_INT, 8), sizes.get(JAVA_INT, 12),
						sizes.get(JAVA_INT, 16), sizes.get(JAVA_INT, 20));
			}
		}

		public void create(long commands, int renderWidth, int renderHeight,
				int displayWidth, int displayHeight, Quality quality) {
			checkSession();
			requireDimensions(renderWidth, renderHeight);
			requireDimensions(displayWidth, displayHeight);
			if (commands == 0 || width != 0 || renderWidth > displayWidth || renderHeight > displayHeight) {
				throw new IllegalArgumentException("Invalid command buffer, size, or duplicate DLSS creation");
			}
			check("create super resolution", call(create, context, commands, renderWidth, renderHeight,
					displayWidth, displayHeight, Objects.requireNonNull(quality).nativeId));
			width = renderWidth;
			height = renderHeight;
			outputWidth = displayWidth;
			outputHeight = displayHeight;
		}

		/** Caller owns layout transitions, submission and image lifetimes. */
		public void evaluate(long commands, Image color, Image depth, Image motion, Image output,
				float jitterX, float jitterY, float deltaMillis, boolean reset) {
			evaluate(commands, color, depth, motion, output, null, jitterX, jitterY, deltaMillis, reset);
		}

		public void evaluate(long commands, Image color, Image depth, Image motion, Image output, Image mask,
				float jitterX, float jitterY, float deltaMillis, boolean reset) {
			checkSession();
			validateFrame(commands, color, depth, motion, output, jitterX, jitterY, deltaMillis);
			validateMask(mask, color, depth, motion, output);
			if (color.width() != width || color.height() != height
					|| output.width() != outputWidth || output.height() != outputHeight) {
				throw new IllegalArgumentException("Recreate DLSS after changing render or display size");
			}
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment images = arena.allocate(4 * 32, 8);
				color.write(images, 0);
				depth.write(images, 32);
				motion.write(images, 64);
				output.write(images, 96);
				MemorySegment bias = mask == null ? MemorySegment.NULL : arena.allocate(32, 8);
				if (mask != null) mask.write(bias, 0);
				check("evaluate super resolution", call(evaluate, context, commands, images, bias,
						jitterX, jitterY, deltaMillis, reset || firstFrame ? 1 : 0));
				firstFrame = false;
			}
		}

		private void checkSession() {
			checkThread();
			if (retired) throw new IllegalStateException("DLSS session has been retired");
		}

		@Override
		public void close() {
			checkThread();
			if (retired) return;
			int status = call(release, context);
			// The bridge consumes the context on NGX teardown errors too. It only retains
			// it on the bridge's explicit wait-failure status.
			if (status == -5) {
				check("wait for DLSS submissions", status);
			}
			retired = true;
			session = null;
			check("release NGX", status);
		}
	}

	static void validateFrame(long commands, Image color, Image depth, Image motion, Image output,
			float jitterX, float jitterY, float deltaMillis) {
		if (commands == 0 || !Float.isFinite(jitterX) || !Float.isFinite(jitterY)
				|| !Float.isFinite(deltaMillis) || deltaMillis <= 0) {
			throw new IllegalArgumentException("Invalid DLSS command buffer or frame timing/jitter");
		}
		for (Image input : List.of(color, depth, motion)) {
			if (input.width() != color.width() || input.height() != color.height()
					|| input.image() == output.image()) {
				throw new IllegalArgumentException("DLSS inputs must match and must not alias output");
			}
		}
		if (color.image() == depth.image() || color.image() == motion.image() || depth.image() == motion.image()) {
			throw new IllegalArgumentException("DLSS input images must not alias each other");
		}
		// Vulkan formats R16G16B16A16_SFLOAT, R32_SFLOAT, R16G16_SFLOAT or R32G32_SFLOAT.
		if (color.format() != 97 || output.format() != 97 || depth.format() != 100
				|| (motion.format() != 83 && motion.format() != 103)
				|| output.width() < color.width() || output.height() < color.height()) {
			throw new IllegalArgumentException("Unsupported DLSS HDR/depth/motion format or output size");
		}
	}

	public static void validateMask(Image mask, Image color, Image depth, Image motion, Image output) {
		if (mask == null) return;
		if (mask.format() != 9 || mask.width() != color.width() || mask.height() != color.height()
				|| List.of(color, depth, motion, output).stream().anyMatch(image -> image.image() == mask.image())) {
			throw new IllegalArgumentException("DLSS mask must be separate R8_UNORM at render size");
		}
	}

	private static void requireDimensions(int width, int height) {
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("Empty DLSS dimensions");
	}

	private void checkThread() {
		if (Thread.currentThread() != owner || closed) {
			throw new IllegalStateException("DLSS must be used on its owning thread while open");
		}
	}

	@SuppressWarnings("restricted") // Descriptors are fixed alongside the bridge header.
	private MethodHandle function(String name, FunctionDescriptor descriptor) {
		return Linker.nativeLinker().downcallHandle(symbols.find(name)
				.orElseThrow(() -> new UnsatisfiedLinkError("Missing DLSS bridge symbol " + name)), descriptor);
	}

	private static int call(MethodHandle function, Object... arguments) {
		try {
			return (int) function.invokeWithArguments(arguments);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			throw new IllegalStateException("DLSS native invocation failed", e);
		}
	}

	private static MemorySegment widePath(Arena arena, Path path) {
		byte[] encoded = (path.toAbsolutePath().normalize().toString() + '\0')
				.getBytes(StandardCharsets.UTF_16LE);
		MemorySegment result = arena.allocate(encoded.length, 2);
		result.copyFrom(MemorySegment.ofArray(encoded));
		return result;
	}

	private static void check(String operation, int status) {
		if (status != 0) {
			throw new IllegalStateException("DLSS could not " + operation + " (0x"
					+ Integer.toHexString(status) + ")");
		}
	}

	@Override
	public void close() {
		if (closed) return;
		checkThread();
		if (session != null) session.close();
		libraryArena.close();
		closed = true;
	}
}
