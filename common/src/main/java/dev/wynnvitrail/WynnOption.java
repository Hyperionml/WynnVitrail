package dev.wynnvitrail;

import dev.vitrail.Vitrail;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public enum WynnOption {
	GLINT("glintBrightness", 110, 50, 200, 5),
	TINT("tintBrightness", 75, 25, 150, 5),
	DARK("wynncraftSceneDarkening", 100, 0, 100, 5),
	ENTITY("wynncraftEntityBrightness", 100, 0, 200, 5),
	EMISSIVE("wynncraftEntityEmissivity", 100, 0, 100, 5),
	NIGHT("wynncraftNightVisionDisablesBoost", 1, 0, 1, 1),
	TEXT("wynncraftTextBrightnessFloor", 0, 0, 1, 1),
	FLOOR("wynncraftTextBrightnessFloorLevel", 10, 0, 15, 1),
	MIST("wynncraftMistWoodsFog", 1, 0, 1, 1),
	DENSITY("wynncraftMistWoodsFogDensity", 100, 0, 100, 5),
	DISTANCE("wynncraftMistWoodsFogMinDistance", 0, 0, 300, 10),
	UNTINT("wynncraftMistWoodsFogSunTintReduction", 0, 0, 1, 1),
	WARM_CACHE("wynncraftAmbienceAutoWarmCache", 1, 0, 1, 1),
	AMBIENCE("wynncraftAmbienceEnabled", 0, 0, 1, 1),
	MOUNT("wynncraftMountArmorOverlay", 0, 0, 1, 1),
	DEBUG("wynncraftDebugLogging", 0, 0, 1, 1),
	WARMTH("wynncraftMistWoodsFogSunTintAmount", 50, 0, 100, 5);

	public final String key;
	public final int initial;
	public final int min;
	public final int max;
	public final int step;
	private volatile int value;
	private static volatile String selected = "";

	public static String selectedPack() { return selected; }

	public static synchronized void selectPack(String id) {
		String previous = selected;
		selected = id;
		if (!AMBIENCE.save(AMBIENCE.value)) selected = previous;
	}

	WynnOption(String key, int initial, int min, int max, int step) {
		this.key = key;
		this.initial = initial;
		this.min = min;
		this.max = max;
		this.step = step;
		value = initial;
	}

	public int value() { return value; }
	public float scale() { return value * 0.01F; }
	public boolean enabled() { return value != 0; }

	private static Path file(Path game) {
		return game.resolve("config").resolve("wynnvitrail.properties");
	}

	static synchronized void read(Path game) {
		Properties props = new Properties();
		Path config = file(game);
		Path source = Files.exists(config) ? config : game.resolve("config").resolve("iris.properties");
		if (Files.isRegularFile(source)) {
			try (Reader in = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
				props.load(in);
			} catch (IOException | IllegalArgumentException ex) {
				Vitrail.logger().warn("Unable to load Wynncraft settings from {}", source, ex);
				return;
			}
		}
		selected = props.getProperty("wynncraftSelectedAmbiencePack", "");
		for (WynnOption option : values()) {
			String raw = props.getProperty(option.key);
			option.value = option.initial;
			if (raw == null) continue;
			if (option.max == 1) {
				if (raw.equalsIgnoreCase("true")) option.value = 1;
				if (raw.equalsIgnoreCase("false")) option.value = 0;
			} else {
				try {
					option.value = Math.clamp(Integer.parseInt(raw.trim()), option.min, option.max);
				} catch (NumberFormatException ignored) {
					Vitrail.logger().warn("Invalid numeric setting {} in {}", option.key, source);
				}
			}
		}
	}

	public void set(int next) { save(next); }

	private boolean save(int next) {
		synchronized (WynnOption.class) {
			Path config = file(Vitrail.platform().gameDirectory());
			Properties props = new Properties();
			props.setProperty("wynncraftSelectedAmbiencePack", selected);
			next = Math.clamp(next, min, max);
			for (WynnOption option : values()) {
				int v = option == this ? next : option.value;
				props.setProperty(option.key, option.max == 1 ? Boolean.toString(v != 0) : Integer.toString(v));
			}
			try {
				Files.createDirectories(config.getParent());
				Path tmp = Files.createTempFile(config.getParent(), "wynnvitrail-", ".tmp");
				try {
					try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
						props.store(out, "Wynncraft rendering");
					}
					Files.move(tmp, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					value = next;
					return true;
				} finally {
					Files.deleteIfExists(tmp);
				}
			} catch (IOException ex) {
				Vitrail.logger().error("Unable to save Wynncraft settings to {}", config, ex);
				return false;
			}
		}
	}
}
