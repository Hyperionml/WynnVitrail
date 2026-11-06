package dev.wynnvitrail;

import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.model.ProgramStage;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

public final class WynnVfx {
	private static boolean building;
	private static boolean drawing;

	private WynnVfx() {}
	public static void drawing(boolean value) { drawing = value; }
	public static boolean drawing() { return drawing && dev.vitrail.render.PackChain.supportsWynnVfx(); }
	public static boolean building() { return building; }
	public static void end() { building = false; }

	public static void begin(int[] tints) {
		building = false;
		if (!dev.vitrail.render.PackChain.supportsWynnVfx() || !WynncraftSettings.effects() || tints == null) return;
		for (int color : tints) {
			int red = (color >>> 16) & 255;
			if ((color & 65535) == 0xfe00 && red > 0 && red < 255) {
				building = true;
				return;
			}
		}
	}

	public static Map<ProgramStage, ExpandedUnit> program(boolean premultiply) {
		String vertex = """
				#version 120
				varying vec4 layerColor;
				varying vec2 uv;
				varying vec2 lightUV;
				void main() {
					gl_Position = ftransform();
					uv = gl_MultiTexCoord0.xy;
					lightUV = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
					layerColor = gl_Color;
					int level = wynnTranslucency(of_VertexColor);
					if (level > 0) layerColor.a = max(0.105, 1.0 - float(level) / 100.0);
				}
				""";
		String fragment = """
				#version 120
				uniform sampler2D gtexture;
				uniform sampler2D lightmap;
				varying vec4 layerColor;
				varying vec2 uv;
				varying vec2 lightUV;
				void main() {
					vec4 color = texture2D(gtexture, uv) * layerColor;
					if (color.a < 0.1) discard;
					color.rgb *= texture2D(lightmap, lightUV).rgb;
				""" + (premultiply ? "color.rgb *= color.a;\n" : "") + "gl_FragColor = color;\n}";
		return Map.of(ProgramStage.VERTEX, unit("wynn_vfx.vsh", vertex),
				ProgramStage.FRAGMENT, unit("wynn_vfx.fsh", fragment));
	}

	static ExpandedUnit unit(String name, String text) {
		List<String> lines = text.lines().toList();
		BitSet live = new BitSet();
		live.set(0, lines.size()+1);
		return new ExpandedUnit(name, lines, "120", ExpansionStats.NONE, live, Map.of());
	}
}
