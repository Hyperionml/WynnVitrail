package dev.vitrail.render.upscale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a shader pack declares about its own reconstruction, read from {@code vitrail-dlss.json}.
 * <p>
 * The engine cannot work out where a pack's HDR image lives or which program marks the point it is
 * finished: both are properties of the pack's own pipeline, and guessing them from a hard-coded name
 * made the feature work on exactly one pack. This is the same bargain the Super Resolution mod
 * strikes - the pack says where the image is, the engine runs the model - with the difference that
 * the fields here describe what this engine already draws rather than asking the pack to rebuild its
 * pipeline around an outside controller.
 * <p>
 * The file is optional. Without one a recognised Solas 3.9 still gets {@link #solas()}, so nothing
 * that worked before changes, and no other pack is touched.
 */
public record DlssPackProfile(String name, String triggerPass, boolean before, int colorTarget,
		boolean hdr, boolean cameraMotion, boolean disableTaa, String jitterFile, String jitterFunction) {

	public static final String FILE = "vitrail-dlss.json";
	public static final int SCHEMA_VERSION = 1;

	private static final Pattern COLORTEX = Pattern.compile("colortex(\\d{1,2})");

	/**
	 * The values this feature used before the file existed, kept for the pack it was written for.
	 * <p>
	 * Solas 3.9 keeps its linear HDR scene in colortex0 and finishes it at composite13, which is why
	 * those two numbers were ever in the source at all.
	 */
	public static DlssPackProfile solas() {
		return new DlssPackProfile("Solas 3.9", SolasDlssProfile.BEFORE_PROGRAM, true,
				SolasDlssProfile.HDR_COLOR_TARGET, true, true, true, null, null);
	}

	/** Reads the declaration beside the pack's {@code shaders} directory, if it carries one. */
	public static Optional<DlssPackProfile> read(Path shadersRoot) throws IOException {
		Path found = locate(shadersRoot);
		if (found == null) {
			return Optional.empty();
		}

		return parse(Files.readString(found, StandardCharsets.UTF_8), found.getFileName().toString());
	}

	private static Path locate(Path shadersRoot) {
		Path direct = shadersRoot.resolve(FILE);
		if (Files.isRegularFile(direct)) {
			return direct;
		}

		// A zip mounts its root as the shaders directory's parent, a directory pack has a real one.
		// Both layouts are what "beside shaders.properties" means in practice.
		Path parent = shadersRoot.getParent();
		if (parent != null) {
			Path beside = parent.resolve(FILE);
			if (Files.isRegularFile(beside)) {
				return beside;
			}
		}

		return null;
	}

	/**
	 * Parses one declaration. Everything that can be checked without the pack's program list is
	 * checked here; whether the trigger names a program that exists is {@code PackChain}'s to answer,
	 * because only the opened chain knows the order.
	 */
	public static Optional<DlssPackProfile> parse(String text, String origin) {
		JsonObject root;
		try {
			root = JsonParser.parseString(text).getAsJsonObject();
		} catch (RuntimeException e) {
			dev.vitrail.Vitrail.logger().warn("{} is not a JSON object, so DLSS stays off for this pack: {}",
					origin, e.toString());

			return Optional.empty();
		}

		int schema = root.has("schema_version") ? root.get("schema_version").getAsInt() : 0;
		if (schema != SCHEMA_VERSION) {
			dev.vitrail.Vitrail.logger().warn("{} declares schema {} and this engine speaks {}, so it is left alone",
					origin, schema, SCHEMA_VERSION);

			return Optional.empty();
		}

		if (!root.has("trigger") || !root.get("trigger").isJsonObject()) {
			dev.vitrail.Vitrail.logger().warn("{} names no trigger, so there is no point to run at", origin);

			return Optional.empty();
		}

		JsonObject trigger = root.getAsJsonObject("trigger");
		String pass = string(trigger, "pass", "");
		if (pass.isBlank()) {
			dev.vitrail.Vitrail.logger().warn("{} names an empty trigger pass", origin);

			return Optional.empty();
		}

		String type = string(trigger, "type", "BEFORE").toUpperCase(java.util.Locale.ROOT);
		if (!type.equals("BEFORE") && !type.equals("AFTER")) {
			dev.vitrail.Vitrail.logger().warn("{} asks for trigger type {}, which is neither BEFORE nor AFTER",
					origin, type);

			return Optional.empty();
		}

		int color = SolasDlssProfile.HDR_COLOR_TARGET;
		boolean cameraMotion = true;
		JsonObject inputs = root.has("inputs") && root.get("inputs").isJsonObject()
				? root.getAsJsonObject("inputs") : null;
		if (inputs != null) {
			if (inputs.has("color") && inputs.get("color").isJsonObject()) {
				String src = string(inputs.getAsJsonObject("color"), "src", "colortex0");
				Matcher match = COLORTEX.matcher(src);
				if (!match.matches()) {
					dev.vitrail.Vitrail.logger().warn("{} asks for colour from {}, and only colortex<N> is read today",
							origin, src);

					return Optional.empty();
				}
				color = Integer.parseInt(match.group(1));
			}

			// A pack that writes its own velocity buffer can say so; the field is read and refused
			// loudly rather than silently ignored, because believing a screen-space vector buffer was
			// consumed when it was not is how ghosting becomes unexplainable.
			if (inputs.has("motion_vectors") && inputs.get("motion_vectors").isJsonObject()) {
				String src = string(inputs.getAsJsonObject("motion_vectors"), "src", "camera");
				if (!src.equalsIgnoreCase("camera")) {
					dev.vitrail.Vitrail.logger().warn("{} asks for motion vectors from {}, and this engine derives "
							+ "them from depth for now; the pack still runs, with camera motion only", origin, src);
				}
			}
		}

		boolean hdr = !root.has("hdr") || root.get("hdr").getAsBoolean();
		boolean disableTaa = !root.has("disable_pack_taa") || root.get("disable_pack_taa").getAsBoolean();
		String name = string(root, "name", origin);
		String jitterFile = null;
		String jitterFunction = null;
		if (root.has("jitter") && root.get("jitter").isJsonObject()) {
			JsonObject jitter = root.getAsJsonObject("jitter");
			jitterFile = string(jitter, "file", "");
			jitterFunction = string(jitter, "function", "");
			if (jitterFile.isBlank() || jitterFunction.isBlank()) {
				jitterFile = null;
				jitterFunction = null;
			}
		}

		return Optional.of(new DlssPackProfile(name, pass, type.equals("BEFORE"), color, hdr,
				cameraMotion, disableTaa, jitterFile, jitterFunction));
	}

	private static String string(JsonObject object, String key, String fallback) {
		return object.has(key) && object.get(key).isJsonPrimitive()
				? object.get(key).getAsString() : fallback;
	}

	/**
	 * The same contract anchored just ahead of the program after the one the pack named.
	 * <p>
	 * {@code AFTER composite15} and {@code BEFORE final} are the same instant in a chain that has no
	 * compute hanging off composite15, and the chain already knows how to run just before a program:
	 * the viewport switches there, the holds end there, and the scaling is promoted there. Resolving
	 * the one into the other is what lets a pack say "after my last post pass" without this engine
	 * growing a second, subtly different execution path.
	 */
	public DlssPackProfile resolvedTo(String pass) {
		return new DlssPackProfile(name, pass, true, colorTarget, hdr, cameraMotion, disableTaa,
				jitterFile, jitterFunction);
	}

	/** One line for the load log, so a picture can name the contract it was drawn under. */
	public String describe() {
		return name + ": " + (before ? "before " : "after ") + triggerPass + ", colortex" + colorTarget
				+ (hdr ? ", HDR" : ", LDR") + (disableTaa ? ", pack TAA off" : ", pack TAA on");
	}
}
