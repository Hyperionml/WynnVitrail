package dev.vitrail.render.upscale;

import dev.vitrail.pack.source.ShaderPackSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Content identity and render boundary for the Solas 3.9 integration under development. */
public final class SolasDlssProfile {
	public static final String VERSION = "3.9";
	public static final String BEFORE_PROGRAM = "composite13";
	public static final int HDR_COLOR_TARGET = 0;
	public static final String TAA_PROGRAM = "composite15";
	/** Red holds lens-flare history; GBA holds TAA history. Never discard this whole target. */
	public static final int SHARED_HISTORY_TARGET = 2;
	private static final String MANIFEST = "/assets/wynnvitrail/dlss/solas-3.9.sha256";

	private SolasDlssProfile() {
	}

	public record Inspection(boolean recognized, List<String> differences) {
		public Inspection {
			differences = List.copyOf(differences);
		}
	}

	/** Recognition does not claim the renderer or pack has DLSS enabled. */
	public static Inspection inspect(ShaderPackSource source) throws IOException {
		Map<String, String> expected = new HashMap<>();
		try (var resource = SolasDlssProfile.class.getResourceAsStream(MANIFEST)) {
			if (resource == null) throw new IOException("Missing Solas DLSS profile manifest");
			String content = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
			for (String line : content.lines().toList()) {
				if (line.isBlank() || line.startsWith("#")) continue;
				expected.put(line.substring(66), line.substring(0, 64));
			}
		}
		List<String> differences = new ArrayList<>();
		List<Path> files = new ArrayList<>(source.sourceFiles());
		source.file("shaders.properties").ifPresent(files::add);
		for (Path file : files) {
			String path = source.rel(file);
			String hash = expected.remove(path);
			if (hash == null) {
				differences.add("Unexpected source: " + path);
			} else if (!hash.equals(digest(String.join("\n", source.readOriginalLines(file))))) {
				differences.add("Changed source: " + path);
			}
		}
		expected.keySet().stream().sorted().forEach(path -> differences.add("Missing source: " + path));
		return new Inspection(differences.isEmpty(), differences);
	}

	private static String digest(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("Java requires SHA-256", e);
		}
	}
}
