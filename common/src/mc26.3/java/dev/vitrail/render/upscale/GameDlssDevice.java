package dev.vitrail.render.upscale;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import dev.vitrail.Vitrail;
import dev.vitrail.mixin.access.VulkanCommandEncoderAccessor;
import dev.vitrail.render.DlssDeviceProbe;
import dev.vitrail.render.GeometryHold;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkFormatProperties;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One NGX context on the game's own Vulkan device. The Solas render chain must opt in separately. */
public final class GameDlssDevice implements AutoCloseable, DlssDevices.Device {
	public interface Owner {
		@Nullable GameDlssDevice vitrail$dlssDevice();
	}

	private record Prepared(DlssInstallation installation, Set<String> instanceExtensions) {
	}

	// Filled only for devices actually created with the complete NGX extension list, then consumed
	// at VulkanDevice construction. Device probing must not start an NGX context on another GPU.
	private static final Map<Long, Prepared> PREPARED = new ConcurrentHashMap<>();
	private static final @Nullable DlssInstallation INSTALLATION = installation();

	private final VulkanDevice device;
	private final DlssInstallation installation;
	private final DlssBridge bridge;
	private DlssBridge.Session session;
	private final Thread owner = Thread.currentThread();
	private @Nullable Dimensions dimensions;
	private volatile boolean closed;
	private volatile boolean failed;

	private record Dimensions(int width, int height, int outputWidth, int outputHeight,
			DlssBridge.Quality quality) {
	}

	private GameDlssDevice(VulkanDevice device, DlssInstallation installation) throws IOException {
		this.device = device;
		this.installation = installation;
		this.bridge = new DlssBridge(installation.bridge());
		try {
			this.session = open();
		} catch (RuntimeException | LinkageError e) {
			bridge.close();
			throw e;
		}
	}

	private static @Nullable DlssInstallation installation() {
		try {
			if (System.getProperty("wynnvitrail.dlss.backend") == null) {
				var minecraft = net.minecraft.client.Minecraft.getInstance();
				DlssSettings.initialize(minecraft == null ? java.nio.file.Path.of(".") : minecraft.gameDirectory.toPath());
				var runtime = DlssSettings.localRuntime();
				if (runtime == null || !java.nio.file.Files.isRegularFile(runtime.resolve("wynn_dlss.dll"))) return null;
				return DlssInstallation.read(key -> switch (key) {
					case "wynnvitrail.dlss.backend" -> "true";
					case "wynnvitrail.dlss.bridge" -> runtime.resolve("wynn_dlss.dll").toString();
					case "wynnvitrail.dlss.runtime" -> runtime.toString();
					default -> null;
				}).orElse(null);
			}
			return DlssInstallation.read(System::getProperty).orElse(null);
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().warn("DLSS backend disabled: invalid developer installation", e);
			return null;
		}
	}

	/** Called before vkCreateInstance, including the backend's temporary probe instances. */
	public static void instanceExtensions(Set<String> supported, Set<String> enabled) {
		if (INSTALLATION == null) return;
		try (DlssBridge probe = new DlssBridge(INSTALLATION.bridge())) {
			List<String> required = probe.extensions(0, 0, INSTALLATION.runtime());
			if (supported.containsAll(required)) {
				enabled.addAll(required);
			} else {
				Vitrail.logger().warn("DLSS instance extensions unavailable: {}", required);
			}
		} catch (IOException | RuntimeException | LinkageError e) {
			Vitrail.logger().warn("DLSS instance extension query failed; keeping normal rendering", e);
		}
	}

	/** Empty means no DLSS setup; extension failures must never prevent normal device creation. */
	public static List<String> deviceExtensions(VulkanPhysicalDevice physical) {
		if (INSTALLATION == null || physical.vkPhysicalDeviceProperties().vendorID() != 0x10de) {
			return List.of();
		}
		try (DlssBridge probe = new DlssBridge(INSTALLATION.bridge())) {
			var vkPhysical = physical.vkPhysicalDevice();
			List<String> required = probe.extensions(vkPhysical.getInstance().address(),
					vkPhysical.address(), INSTALLATION.runtime());
			if (required.stream().allMatch(physical::hasDeviceExtension)) return required;
			Vitrail.logger().warn("DLSS device extensions unavailable: {}", required);
		} catch (IOException | RuntimeException | LinkageError e) {
			Vitrail.logger().warn("DLSS device extension query failed; keeping normal rendering", e);
		}
		return List.of();
	}

	public static void prepared(VkDevice device, List<String> enabledDlssExtensions) {
		if (INSTALLATION == null || enabledDlssExtensions.isEmpty()) return;
		try (DlssBridge probe = new DlssBridge(INSTALLATION.bridge())) {
			PREPARED.put(device.address(), new Prepared(INSTALLATION,
					Set.copyOf(probe.extensions(0, 0, INSTALLATION.runtime()))));
		} catch (IOException | RuntimeException | LinkageError e) {
			Vitrail.logger().warn("DLSS device preparation failed; keeping normal rendering", e);
		}
	}

	public static @Nullable GameDlssDevice attach(VulkanDevice device) {
		Prepared prepared = PREPARED.remove(device.vkDevice().address());
		if (prepared == null) return null;
		if (!device.instance().getEnabledExtensions().containsAll(prepared.instanceExtensions())) {
			Vitrail.logger().warn("DLSS disabled: required instance extensions were not enabled");
			return null;
		}
		try {
			GameDlssDevice backend = new GameDlssDevice(device, prepared.installation());
			if (Boolean.getBoolean("wynnvitrail.dlss.selfTest")) {
				try {
					DlssDeviceProbe.run(device, backend);
				} catch (RuntimeException | LinkageError e) {
					try {
						backend.close();
					} catch (RuntimeException | LinkageError cleanup) {
						e.addSuppressed(cleanup);
					}
					throw e;
				}
			}
			Vitrail.logger().info("DLSS backend initialized on the Minecraft Vulkan device; pack integration is separate");
			DlssDevices.install(backend);
			if (DlssSettings.requested()) DlssSettings.requestReload();
			return backend;
		} catch (IOException | RuntimeException | LinkageError e) {
			Vitrail.logger().warn("DLSS unavailable on the Minecraft device; keeping normal rendering", e);
			return null;
		}
	}

	private DlssBridge.Session open() {
		VkDevice vk = device.vkDevice();
		return bridge.open(device.instance().vkInstance().address(), vk.getPhysicalDevice().address(),
				vk.address(), VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"),
				VK12.vkGetInstanceProcAddr(device.instance().vkInstance(), "vkGetDeviceProcAddr"), installation.runtime());
	}

	public DlssBridge.OptimalSize optimalSize(int width, int height, DlssBridge.Quality quality) {
		checkLive();
		return session.optimalSize(width, height, quality);
	}

	/** Pack unload or resize: submit outstanding work before NGX waits and frees its feature. */
	public void releaseFeature() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("DLSS cleanup requires owning thread");
		if (closed || (dimensions == null && !failed)) return;
		try {
			drain();
			session.close();
			dimensions = null;
			if (!failed) session = open();
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			throw e;
		}
	}

	/** All input textures are sampled color images; depth has already been converted to R32F. */
	public boolean record(GpuTextureView color, GpuTextureView depth, GpuTextureView motion,
			GpuTextureView output, boolean outputHasStorageUsage, DlssBridge.Quality quality,
			float jitterX, float jitterY, float deltaMillis, boolean reset) {
		if (!outputHasStorageUsage) throw new IllegalArgumentException("DLSS output requires storage usage");
		return recordMasked(color, depth, motion, output, null, quality, jitterX, jitterY, deltaMillis, reset);
	}

	public boolean recordMasked(GpuTextureView color, GpuTextureView depth, GpuTextureView motion,
			GpuTextureView output, GpuTextureView mask, DlssBridge.Quality quality,
			float jitterX, float jitterY, float deltaMillis, boolean reset) {
		checkLive();
		// Validate before recording any Vulkan work. The facade cannot report storage usage;
		// only the allocator that requested TextureUsage storage may assert it here.
		DlssBridge.Image c = image(color), d = image(depth), m = image(motion), o = image(output);
		DlssBridge.Image bias = mask == null ? null : image(mask);
		DlssBridge.validateFrame(1, c, d, m, o, jitterX, jitterY, deltaMillis);
		DlssBridge.validateMask(bias, c, d, m, o);
		Dimensions requested = new Dimensions(c.width(), c.height(), o.width(), o.height(), quality);
		try {
			if (dimensions != null && !dimensions.equals(requested)) {
				releaseFeature();
			}
			VulkanCommandEncoder encoder = endPass();
			VkCommandBuffer commands = ((VulkanCommandEncoderAccessor) encoder).vitrail$commandBuffer();
			if (dimensions == null) {
				session.create(commands.address(), c.width(), c.height(), o.width(), o.height(), quality);
				dimensions = requested;
			}
			DlssVulkanCommands.evaluate(commands, session, c, d, m, o, bias,
					jitterX, jitterY, deltaMillis, reset);
			return true;
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			Vitrail.logger().error("DLSS evaluation failed; caller must discard output and use fallback", e);
			return false;
		}
	}

	private static DlssBridge.Image image(GpuTextureView view) {
		if (!(view instanceof VulkanGpuTextureView vk) || vk.isClosed() || vk.texture().isClosed()
				|| view.baseMipLevel() != 0 || view.mipLevels() != 1
				|| view.texture().getDepthOrLayers() != 1
				|| (view.texture().usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
			throw new IllegalArgumentException("DLSS requires live sampled Vulkan views of base mip, one layer");
		}
		return new DlssBridge.Image(vk.texture().vkImage(), vk.vkImageView(),
				VulkanConst.toVk(view.texture().getFormat()), view.getWidth(0), view.getHeight(0));
	}

	private VulkanCommandEncoder endPass() {
		GeometryHold.flush(() -> "DLSS external compute");
		VulkanCommandEncoder encoder = device.createCommandEncoder();
		if (((VulkanCommandEncoderAccessor) encoder).vitrail$currentRenderPass() != null) {
			encoder.submitRenderPass();
		}
		return encoder;
	}

	private void drain() {
		// NGX's vkDeviceWaitIdle cannot wait for commands the encoder has not submitted yet.
		endPass().submit();
	}

	public void submit(Runnable submitted) {
		checkLive();
		endPass().submit();
		submitted.run();
	}

	public boolean available() {
		return operational() && Thread.currentThread() == owner;
	}
	public boolean operational() { return !closed && !failed; }

	/** Format conversion and resampling, outside a render pass, with both game images in GENERAL. */
	@Override
	public void blit(GpuTextureView from, GpuTextureView to) {
		if (closed || Thread.currentThread() != owner) throw new IllegalStateException("Vulkan device unavailable");
		DlssBridge.Image source = image(from), destination = image(to);
		int aspect = VulkanConst.formatAspectMask(from.texture().getFormat());
		if (source.image() == destination.image()
				|| aspect != VulkanConst.formatAspectMask(to.texture().getFormat())
				|| (aspect != VK12.VK_IMAGE_ASPECT_COLOR_BIT && aspect != VK12.VK_IMAGE_ASPECT_DEPTH_BIT)
				|| (from.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0
				|| (to.texture().usage() & GpuTexture.USAGE_COPY_DST) == 0) {
			throw new IllegalArgumentException("DLSS conversion requires separate transfer-capable images");
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties properties = VkFormatProperties.calloc(stack);
			VK12.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), source.format(), properties);
			if ((properties.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0) {
				throw new IllegalArgumentException("DLSS source format does not support blit");
			}
			VK12.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), destination.format(), properties);
			if ((properties.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) {
				throw new IllegalArgumentException("DLSS destination format does not support blit");
			}
			VkCommandBuffer commands = ((VulkanCommandEncoderAccessor) endPass()).vitrail$commandBuffer();
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
					.srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT | VK12.VK_ACCESS_MEMORY_READ_BIT)
					.dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
			VK12.vkCmdPipelineBarrier(commands, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
					VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
			VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
			region.srcSubresource().aspectMask(aspect).layerCount(1);
			region.dstSubresource().aspectMask(aspect).layerCount(1);
			region.srcOffsets(1).set(source.width(), source.height(), 1);
			region.dstOffsets(1).set(destination.width(), destination.height(), 1);
			VK12.vkCmdBlitImage(commands, source.image(), VK12.VK_IMAGE_LAYOUT_GENERAL,
					destination.image(), VK12.VK_IMAGE_LAYOUT_GENERAL, region, VK12.VK_FILTER_NEAREST);
			barrier.srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
					.dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
			VK12.vkCmdPipelineBarrier(commands, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
					VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
		}
	}

	private void checkLive() {
		if (!available()) throw new IllegalStateException("DLSS backend unavailable or used on another thread");
	}

	@Override
	public void close() {
		if (closed) return;
		if (Thread.currentThread() != owner) throw new IllegalStateException("DLSS cleanup requires its owning thread");
		try {
			if (dimensions != null || failed) drain();
			bridge.close();
		} finally {
			DlssDevices.remove(this);
			closed = true;
		}
	}
}
