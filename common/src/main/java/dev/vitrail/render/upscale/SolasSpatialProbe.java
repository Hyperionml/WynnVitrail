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
		return patch(relative, original, false, true);
	}

	public static List<String> patch(String relative, List<String> original, boolean temporal, boolean disableTaa) {
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
}
