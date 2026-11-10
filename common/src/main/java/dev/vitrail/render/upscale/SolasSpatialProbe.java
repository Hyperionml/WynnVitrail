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

	public static void validatePasses(List<String> passes) {
		int boundary = passes.indexOf(SolasDlssProfile.BEFORE_PROGRAM);
		if (boundary < 0 || !passes.subList(boundary, passes.size())
				.equals(List.of("composite13", "composite14", "composite15", "final"))) {
			throw new IllegalArgumentException("Solas DLSS probe requires composite13, composite14, composite15 and final in order");
		}
	}

	/** Applied only after the complete original Solas manifest matched, before include expansion. */
	public static List<String> patch(String relative, List<String> original) {
		return patch(relative, original, false);
	}

	public static List<String> patch(String relative, List<String> original, boolean temporal) {
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
		// Keep composite15 and its colortex2.r writes. This diagnostic disables the old jitter
		// with TAA; the subsequent temporal integration must supply jitter to all geometry.
		lines.add("#undef TAA");
		return List.copyOf(lines);
	}
}
