package dev.vitrail.render.upscale;

import java.util.ArrayList;
import java.util.List;

/** Render-graph bring-up only: every frame resets NGX, without claiming temporal reconstruction. */
public final class SolasSpatialProbe {
	private SolasSpatialProbe() {
	}

	public static boolean requested() {
		return Boolean.getBoolean("wynnvitrail.dlss.solasSpatialProbe") || DlssSettings.activeSource();
	}
	public static int mode() {
		return Boolean.getBoolean("wynnvitrail.dlss.solasSpatialProbe") ? 1 : DlssSettings.activeSource() ? 2 : 0;
	}

	public static void validatePasses(List<String> passes, String trigger) {
		if (passes.indexOf(trigger) < 0) {
			throw new IllegalArgumentException("The declared DLSS trigger " + trigger
					+ " is not one of this pack's programs, so there is no point to run at: " + passes);
		}
	}

	/** Applied only after the complete original Solas manifest matched, before include expansion. */
	public static List<String> patch(String relative, List<String> original) {
		return patch(relative, original, false, true, null, null);
	}

	public static List<String> patch(String relative, List<String> original, boolean temporal,
			boolean disableTaa, String jitterFile, String jitterFunction) {
		if (jitterFile != null && jitterFunction != null && relative.equals(jitterFile)) {
			return replaceJitter(original, jitterFunction);
		}
		if (temporal && relative.startsWith("programs/")
				&& (relative.substring(9).startsWith("gbuffers_") || relative.substring(9).startsWith("dh_"))) {
			String text = String.join("\n", original);
			int vertex = text.lastIndexOf("#ifdef VSH");
			if (vertex < 0) return original;
			String head = text.substring(0, vertex);
			String tail = text.substring(vertex).replaceFirst("void\\s+main\\s*\\(\\s*\\)", "void wynnDlssOriginalMain()");
			if (tail.equals(text.substring(vertex))) throw new IllegalStateException("Missing Solas vertex entry: " + relative);
			return (head + tail + "\n#ifdef VSH\nuniform vec2 wynnDlssClipJitter;\n"
					+ "void main() { wynnDlssOriginalMain(); gl_Position.xy += wynnDlssClipJitter * gl_Position.w; }\n#endif\n").lines().toList();
		}
		if (!relative.equals("lib/common.glsl")) return original;
		List<String> lines = new ArrayList<>(original);
		// The pack's own TAA and the reconstruction below both accumulate history, and two
		// accumulators over the same frame is a smear rather than a sharper image. A pack says
		// whether it wants its TAA left standing; Solas does not.
		if (disableTaa) lines.add("#undef TAA");
		return List.copyOf(lines);
	}

	/**
	 * Replaces the pack's own sub-pixel offset with the one the reconstruction asked for.
	 * <p>
	 * A pack that already offsets its vertices has solved the hard half of this: every geometry
	 * family that needs an offset already calls one function, so swapping what that function returns
	 * is enough. Leaving the pack's own offsets in place while the model supplies another would hand
	 * the reconstruction a vector that does not match the image it was given.
	 */
	private static List<String> replaceJitter(List<String> original, String function) {
		String text = String.join("\n", original);
		var pattern = java.util.regex.Pattern.compile("vec2\\s+" + java.util.regex.Pattern.quote(function)
				+ "\\s*\\(\\s*vec2\\s+\\w+\\s*,\\s*float\\s+\\w+\\s*\\)\\s*\\{[^}]*\\}");
		var matcher = pattern.matcher(text);
		if (!matcher.find()) {
			throw new IllegalStateException("The declared jitter function " + function
					+ " is not of the shape this adapter replaces (vec2 name(vec2, float))");
		}

		String replacement = "vec2 " + function + "(vec2 coord, float w) {\n\treturn coord + wynnDlssClipJitter * w;\n}";
		String patched = "uniform vec2 wynnDlssClipJitter;\n"
				+ matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement));

		return patched.lines().toList();
	}
}
