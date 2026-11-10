package dev.vitrail.render.upscale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** User preference is distinct from actual availability. Missing support keeps the FSR path. */
public final class DlssSettings {
	public enum Mode { FSR1, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE, DLAA }
	private static volatile Mode mode = Mode.FSR1;
	private static volatile String failure = "";
	private static Path directory;
	private static volatile boolean reload;
	private DlssSettings() {}

	public static synchronized void initialize(Path gameDirectory) {
		Path next = gameDirectory.toAbsolutePath().normalize().resolve("vitrail");
		if (next.equals(directory)) return;
		directory = next;
		mode = Mode.FSR1;
		failure = "";
		try {
			Path file = directory.resolve("dlss.properties");
			if (Files.isRegularFile(file)) {
				Properties properties = new Properties();
				try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
				mode = parse(properties.getProperty("mode", "FSR1"));
			}
		} catch (IOException | RuntimeException e) { failure = "settings: " + e.getMessage(); }
	}

	public static Mode parse(String value) {
		try { return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException | NullPointerException e) { return Mode.FSR1; }
	}
	public static Mode mode() { return mode; }
	public static boolean requested() { return mode != Mode.FSR1 && failure.isEmpty(); }
	public static boolean activeSource() {
		var backend = DlssDevices.installed();
		return requested() && backend != null && backend.operational();
	}
	public static DlssBridge.Quality quality() {
		return switch (mode) {
			case BALANCED -> DlssBridge.Quality.BALANCED;
			case PERFORMANCE -> DlssBridge.Quality.PERFORMANCE;
			case ULTRA_PERFORMANCE -> DlssBridge.Quality.ULTRA_PERFORMANCE;
			case DLAA -> DlssBridge.Quality.DLAA;
			default -> DlssBridge.Quality.QUALITY;
		};
	}
	public static synchronized void save(Mode choice) throws IOException {
		if (directory == null) throw new IllegalStateException("DLSS settings not initialized");
		Files.createDirectories(directory);
		Path file = directory.resolve("dlss.properties");
		Path temporary = directory.resolve("dlss.properties.part");
		Files.writeString(temporary, "mode=" + choice.name() + "\n");
		try {
			Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		mode = choice;
		failure = "";
	}
	public static void requestReload() { reload = true; }
	public static boolean takeReload() { boolean wanted = reload; reload = false; return wanted; }
	public static void fail(String reason) { failure = reason; reload = true; }
	public static String failure() { return failure; }
	public static Path localRuntime() { return directory == null ? null : directory.resolve("dlss"); }
}
