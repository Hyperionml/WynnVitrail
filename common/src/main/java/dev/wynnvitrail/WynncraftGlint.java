package dev.wynnvitrail;

/**
 * The glint library: what each of the numbered effects does to a fragment's colour.
 * <p>
 * <strong>A glint on Wynncraft is not the game's enchantment shimmer.</strong> The server's art is
 * drawn into the item's own texture and its animation is written in shader code the resource pack
 * injects; the mesh carries nothing but a number saying which of them to run. Decoding that number
 * is {@link WynncraftSignals}'s job and this is the other half: the code the number selects, which
 * is why a weapon's shine, its sweep and its tint all arrive here as cases of one switch.
 * <p>
 * <strong>These are WynnIris's own effects, taken over with their numbers and their coefficients
 * unchanged</strong> ({@code pipeline/transform/transformer/EntityPatcher.java:658-853}), because
 * the pack on the other end is the same pack and every number in it was measured against that pack
 * rather than chosen. A tint that reads one step off is a colour the server did not pick, and a
 * sweep that reads one step off is a band in the wrong place; the case bodies below are a
 * transcription rather than a reimplementation and are meant to be read beside the file they came
 * from.
 * <p>
 * Three things were changed on the way over, and each is forced by the engine rather than picked:
 * <ul>
 * <li><strong>The library's functions carry {@code wynn} where WynnIris's carried {@code irisW}.</strong>
 * The code is injected into a pack's own file and a pack is free to have written any name at all, so
 * a name that stands at file scope has to be one no pack would have written; Iris buys the same
 * property by spelling its own {@code iris_}. The parameters and the locals inside the functions
 * keep WynnIris's {@code iW_} untouched, and safely: each is read in the one function that declares
 * it, where a pack's global of the same name is shadowed rather than met.</li>
 * <li><strong>The texture is a parameter.</strong> WynnIris names {@code Sampler0}, because Iris
 * renames every pack's diffuse sampler to that on the way through. This engine keeps the pack's own
 * name ({@code gtexture}, {@code tex} and {@code texture} all appear in the corpus), so the
 * sampler is handed to the library instead of being spelled inside it, and the one function that
 * samples on its own - the chromatic aberration - takes it as its first argument for the same
 * reason.</li>
 * <li><strong>The two brightnesses are constants.</strong> WynnIris reads them from Iris's own
 * video settings, {@code iris_glintBrightness} and {@code iris_tintBrightness}; this engine has no
 * such settings and a uniform nothing fills would be a number the driver leaves at nought. The
 * constants below are those settings at their shipped defaults
 * ({@code gui/option/IrisVideoSettings.java:17-18}, one hundred and ten and seventy-five out of a
 * hundred, so one point one and nought point seven five), so a run with the effects on draws the
 * picture Iris draws with its sliders untouched. Growing them into real settings is a change to
 * this file and one uniform.</li>
 * </ul>
 * <p>
 * <strong>Every effect is a change to a colour the pack has already decided.</strong> None of them
 * is a pass of its own and none is additive over the frame: each takes the fragment as the pack
 * left it and returns what should stand in its place. That is why the caller is the wrapper's
 * epilogue and not a render stage, and why the library takes the pack's own value as its last
 * argument rather than reading an attachment.
 */
final class WynncraftGlint {

	/**
	 * The effect library's entry point, named here so the one caller cannot misspell it.
	 */
	static final String APPLY_NAME = "wynnApplyGlint";

	/**
	 * The coordinate the sweeps run along, which the caller works out once and hands to the library.
	 * <p>
	 * Named here as well as declared because the caller has to spell it twice - once for the item's
	 * own coordinate and once for the middle of its sprite, whose pair is what the sweep is measured
	 * between - and a second spelling is a second chance to be one character wrong.
	 */
	static final String SWEEP_UV_NAME = "wynnContinuousSweepUV";

	/**
	 * The sampler every function that reads the item's own texture takes as its first argument.
	 * <p>
	 * A name of the library's own rather than the pack's, and it has to be: the same text is emitted
	 * for every pack, into files that call their diffuse texture three different things. The call
	 * site passes the pack's name in, and inside the library there is only this one.
	 */
	static final String SAMPLER = "wynnTex";

	/**
	 * What the effects are allowed to be worth, which is WynnIris's pair of Iris settings at their
	 * defaults.
	 * <p>
	 * The glint's is above one, so a shiny weapon's highlight is brighter than its texture; the
	 * tint's is below one, so a tinted piece settles darker than the tint's own colour. Both are the
	 * shipped values and not round numbers, which is why they are named rather than folded into the
	 * two expressions that read them.
	 */
	private static final String BRIGHTNESS =
			"const float wynnGlintBrightness = 1.10;\n"
			+ "const float wynnTintBrightness = 0.75;";

	/** A colour written the way the pack lists effects write one, in bytes out of two hundred and fifty-five. */
	private static final String RGB =
			"vec3 wynnRgb(int r, int g, int b) {"
			+ " return vec3(float(r)/255.0, float(g)/255.0, float(b)/255.0); }";

	/** Hue, saturation and value into red, green and blue, for the one effect that sweeps a hue. */
	private static final String HSV_TO_RGB =
			"vec3 wynnHsvToRgb(vec3 c) {\n"
			+ "\tvec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);\n"
			+ "\tvec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);\n"
			+ "\treturn c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);\n"
			+ "}";

	/** Two hashes, and both are needed: the effects seed one from a time and the other from a coordinate. */
	private static final String RANDOM_FLOAT =
			"float wynnRandom(float seed) {"
			+ " return fract(57128.836 * sin(dot(vec2(seed), vec2(12.77251, 72.37871)))); }";

	private static final String RANDOM_VEC2 =
			"float wynnRandom(vec2 seed) {"
			+ " return fract(57128.836 * sin(dot(seed, vec2(12.77251, 72.37871)))); }";

	/** White noise, the corner values the smooth one interpolates between. */
	private static final String NOISE =
			"float wynnNoise(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }";

	/**
	 * The same noise smoothed across its lattice, which is what the distort effect warps a coordinate
	 * by: white noise would tear the sprite rather than ripple it.
	 */
	private static final String SMOOTH_NOISE =
			"float wynnSmoothNoise(vec2 p) {\n"
			+ "\tvec2 i = floor(p); vec2 f = fract(p);\n"
			+ "\tfloat a = wynnNoise(i); float b = wynnNoise(i + vec2(1.0, 0.0));\n"
			+ "\tfloat c = wynnNoise(i + vec2(0.0, 1.0)); float d = wynnNoise(i + vec2(1.0, 1.0));\n"
			+ "\tvec2 u = f * f * (3.0 - 2.0 * f);\n"
			+ "\treturn mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;\n"
			+ "}";

	private static final String ROTATE =
			"vec2 wynnRotate(vec2 coord, float angle) {\n"
			+ "\tfloat s = sin(angle); float c = cos(angle);\n"
			+ "\treturn mat2(c, -s, s, c) * coord;\n"
			+ "}";

	/**
	 * One distance melted into another over a band, which is how the aurora rounds its ridges
	 * without a step between them.
	 */
	private static final String SMOOTHEN =
			"float wynnSmoothen(float distA, float distB, float amt) {\n"
			+ "\tfloat blend = clamp(0.5 + 0.5 * (distB - distA) / amt, 0.0, 0.5);\n"
			+ "\treturn mix(distB, distA, blend) - amt * blend * (1.0 - blend);\n"
			+ "}";

	/** The colours of the pack's own texture mixed with an effect's, which most of the cases end on. */
	private static final String BLEND =
			"vec3 wynnBlend(vec4 a, vec4 b, float amt) { return mix(a, b, amt).rgb; }";

	private static final String GRAYSCALE =
			"vec4 wynnGrayscale(vec4 color) {\n"
			+ "\tfloat brightness = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));\n"
			+ "\treturn vec4(vec3(brightness), color.a);\n"
			+ "}";

	/**
	 * A tint's own colour laid over a grey, which is what makes every tinted item the same colour
	 * whatever its texture: the texture decides how bright a pixel is and the tint decides what hue
	 * that brightness is.
	 */
	private static final String TINT =
			"vec3 wynnTint(vec3 tex, vec3 tintColor, float contrast) {\n"
			+ "\tfloat brightness = pow(dot(tex, vec3(0.2126, 0.7152, 0.0722)), 0.7);\n"
			+ "\tvec3 color = tintColor * brightness;\n"
			+ "\tcolor = mix(color, vec3(1.0), smoothstep(0.7, 1.0, brightness) * contrast);\n"
			+ "\treturn color;\n"
			+ "}";

	/**
	 * The channels of a texture pulled apart sideways, which is the one effect whose look is a
	 * misregistration rather than a pattern.
	 * <p>
	 * It samples the texture itself and so takes it as an argument, where every other helper in this
	 * file is handed the sample the caller already took.
	 */
	private static final String ABERRATION =
			"vec3 wynnAberration(sampler2D " + SAMPLER + ", vec2 uv, float factor, float iW_t) {\n"
			+ "\tvec4 color = texture(" + SAMPLER + ", uv);\n"
			+ "\tcolor.x = texture(" + SAMPLER + ", vec2(uv.x + sin(iW_t * 500.0) * factor, uv.y)).x;\n"
			+ "\tcolor.y = texture(" + SAMPLER + ", vec2(uv.x + cos(iW_t * 500.0) * factor, uv.y)).y;\n"
			+ "\tcolor.z = texture(" + SAMPLER + ", uv).z;\n"
			+ "\treturn wynnBlend(texture(" + SAMPLER + ", uv), color, color.a);\n"
			+ "}";

	/**
	 * A coloured band travelling across the item, which is what the shiny and the ore glints are.
	 * <p>
	 * The band's edge is a smoothstep pair rather than a triangle wave, and the gate that switches
	 * whole sweeps off is a sum of three sines of the cycle index: three periods that share no
	 * common multiple make the pauses arrive without a pattern, where one sine would look like a
	 * pulse. The decision is taken once per cycle and not per fragment, so a band is never cut in
	 * half partway across the item.
	 */
	private static final String SHINY =
			"vec4 wynnShiny(vec3 iW_color, float iW_intensity, float iW_brightness, vec2 iW_sweepUV,"
			+ " bool iW_isAtlas, float iW_time, vec4 iW_tex) {\n"
			+ "\tvec2 iW_dir = iW_isAtlas ? vec2(0.3, 0.0) : vec2(0.3, -0.07);\n"
			+ "\tfloat iW_speed = iW_isAtlas ? 6.0 : 1.5;\n"
			+ "\tfloat iW_freq = iW_isAtlas ? 0.25 : 0.5;\n"
			+ "\tfloat iW_x = dot(iW_dir, iW_sweepUV) - iW_time * iW_speed;\n"
			+ "\tfloat iW_phase = 1.0 - fract(iW_x * iW_freq);\n"
			+ "\tfloat iW_wave = smoothstep(0.0, 0.05, iW_phase) * (1.0 - smoothstep(0.1, 0.4, iW_phase));\n"
			+ "\tfloat iW_cycle = floor(iW_x * iW_freq);\n"
			+ "\tfloat iW_gate = sin(iW_cycle * 1.7) + sin(iW_cycle * 0.73) + sin(iW_cycle * 0.31);\n"
			+ "\tiW_wave *= step(-0.3, iW_gate);\n"
			+ "\treturn vec4(iW_tex.rgb + iW_color * iW_wave * iW_intensity * iW_brightness, iW_tex.a);\n"
			+ "}";

	/** One of the ten tints: the texture's brightness carrying the tint's hue. */
	private static final String TINT_EFFECT =
			"vec4 wynnTintEffect(vec3 tintColor, vec4 texColor) {\n"
			+ "\tvec4 gs = wynnGrayscale(texColor);\n"
			+ "\tgs.rgb = wynnTint(gs.rgb, tintColor, 0.5);\n"
			+ "\treturn gs;\n"
			+ "}";

	/**
	 * The coordinate the sweeps run along, made continuous across sprite boundaries.
	 * <p>
	 * <strong>This is the one piece of the library that is about the ATLAS rather than about the
	 * effect.</strong> A weapon on Wynncraft is a sprite in the block atlas with its neighbours a
	 * few texels away, and a sweep computed on the sprite's own coordinate restarts at every sprite
	 * boundary: a weapon drawn across several sprites - and every multi-part item is - shows the
	 * band broken into as many pieces as it has sprites. The raw atlas coordinate runs on across
	 * them, so it is the raw coordinate the band is measured in, scaled by how much larger this
	 * atlas is than the thousand-and-twenty-four-pixel one the scale was chosen against.
	 * <p>
	 * A dedicated texture - armour, which is its own small image - has no neighbour to run into and
	 * keeps the per-sprite coordinate.
	 * <p>
	 * The middle of the sprite is what tells the two apart, and nought is what a pack that never
	 * declared the name hands over: a real sprite centre is never exactly the origin, so the pair
	 * can be tested for it. The caller zeroes it for the same reason.
	 */
	private static final String CONTINUOUS_SWEEP_UV =
			"vec2 " + SWEEP_UV_NAME + "(vec2 rawUV, vec2 midTex, vec2 texSize, vec2 eUV) {\n"
			+ "\tbool hasMidTex = !(midTex.x == 0.0 && midTex.y == 0.0);\n"
			+ "\tif (!hasMidTex) {\n"
			+ "\t\treturn eUV;\n"
			+ "\t}\n"
			+ "\tbool isAtlas = max(texSize.x, texSize.y) > 2000.0;\n"
			+ "\tif (isAtlas) {\n"
			+ "\t\tfloat atlasScale = max(texSize.x, texSize.y) / 1024.0;\n"
			+ "\t\treturn rawUV * 64.0 * atlasScale;\n"
			+ "\t}\n"
			+ "\treturn eUV;\n"
			+ "}";

	/**
	 * One effect, chosen by number, applied to the colour the pack has already decided.
	 * <p>
	 * <strong>The numbers are the server's and the list is not ours to renumber.</strong> One to
	 * fourteen are the patterns, fifteen to twenty-four the tints, twenty-five to thirty-one the
	 * coloured shines and thirty-two the piece the server draws with no fog on it. The caller masks
	 * the decoded signal to thirty-one before it gets here, so the highest number this ever sees is
	 * thirty-one and the thirty-second case is reached only when it is asked for directly.
	 * <p>
	 * The call is a switch and not a chain of tests for a reason that is about the shader rather
	 * than about style: a case that is not taken costs a branch the compiler can fold away once the
	 * number is known, where a chain of comparisons over an integer the driver does not know at
	 * compile time is a walk down every effect in turn, on every fragment of every item.
	 * <p>
	 * <strong>Three groups keep their texture's alpha and one does not.</strong> The patterns that
	 * write only {@code rgb} leave the pack's alpha alone; the ones that replace the whole colour
	 * with the texture's - a shiny, a tint - take the texture's alpha with it, because they are
	 * rebuilding the pixel rather than adjusting it and the server's own code does. What that
	 * interacts with is the translucency reduction, which the caller runs afterwards and not before:
	 * an effect that reset the alpha would undo a reduction applied first, and a clamp applied
	 * second is indifferent to where the alpha came from.
	 * <p>
	 * The second case is the odd one and reduces the alpha by half with no colour change at all,
	 * which is the server's translucent glint: it is a case here rather than a signal of its own
	 * because the mesh spells it with the same green as every other glint.
	 * <p>
	 * <strong>What follows the switch is a relight, and it is what keeps a glint out of the dark.</strong>
	 * An effect that adds brightness - a highlight, a band - adds it to a colour the pack has already
	 * lit, so an item in a cave would shine as brightly as one in the sun. The ratio below is how
	 * much the pack's own lighting took off this pixel's texture, and multiplying the effect's
	 * output by it puts the glint back under the scene's light.
	 * <p>
	 * It is the fallback form and not the one WynnIris prefers. On a forward pack whose body can be
	 * found assigning an albedo, WynnIris substitutes the tint into that assignment instead, before
	 * the pack lights anything, so the piece is lit and shaded exactly like the terrain beside it;
	 * this engine has no such anchor to substitute into, having no syntax tree to find one in, so
	 * every pack takes the relight. The difference shows on a tinted piece in a dark scene, where
	 * the relight reproduces the pack's brightness but not its grading - WynnIris's own note on the
	 * matter calls the result black armour turned brown ({@code EntityPatcher.java:855-868}) - and
	 * what it does NOT differ on is a torch-lit or a daylight scene, where the two agree.
	 */
	private static final String APPLY =
			"vec4 " + APPLY_NAME + "(sampler2D " + SAMPLER + ", int iW_id, vec2 iW_uv, vec2 iW_eUV,"
			+ " vec2 iW_sUV, vec2 iW_midTex, vec2 iW_rUV, vec2 iW_texSize, bool iW_isAtlas,"
			+ " float iW_time, vec4 iW_tex, vec4 iW_in) {\n"
			+ "\tvec4 iW_out = iW_in;\n"
			+ "\tbool iW_applyLighting = true;\n"
			+ "\tbool iW_isTint = (iW_id >= 15 && iW_id <= 24);\n"
			+ "\tbool iW_knownEffect = (iW_id >= 1 && iW_id <= 32);\n"
			+ "\tvec2 iW_shinySweep = " + SWEEP_UV_NAME + "(iW_uv, iW_midTex, iW_texSize, iW_eUV);\n"
			+ "\tswitch (iW_id) {\n"
			// A weapon's lit edge, in the pack's own gold.
			+ "\t\tcase 1: { iW_out = wynnShiny(wynnRgb(255, 200, 100), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }\n"
			// Half the alpha and no colour change: the server's translucent glint.
			+ "\t\tcase 2: { iW_out.a *= 0.5; iW_applyLighting = false; break; }\n"
			// A hue sweeping the item, its spatial term taken out of the radial coordinate.
			+ "\t\tcase 3: {\n"
			+ "\t\t\tiW_out = wynnGrayscale(iW_tex);\n"
			+ "\t\t\tfloat iW_spatial3 = 0.05 * (iW_rUV.x + iW_rUV.y);\n"
			+ "\t\t\tiW_out.rgb *= wynnHsvToRgb(vec3(iW_spatial3 - iW_time, 0.7, 1.0));\n"
			+ "\t\t\tbreak;\n"
			+ "\t\t}\n"
			// Rows of the sprite displaced by a hash, switching on and off ten times a unit of time.
			+ "\t\tcase 4: {\n"
			+ "\t\t\tfloat iW_intensity4 = iW_isAtlas ? 0.00015 : 0.015;\n"
			+ "\t\t\tfloat iW_colorOffset4 = iW_isAtlas ? 0.0001 : 0.995;\n"
			+ "\t\t\tfloat iW_sz = wynnRandom(iW_time);\n"
			+ "\t\t\tfloat iW_sp = 10.0;\n"
			+ "\t\t\tfloat iW_tf = float(wynnRandom(floor(iW_time * iW_sp)) < 0.5);\n"
			+ "\t\t\tfloat iW_off4 = (wynnRandom(floor(iW_uv.y * iW_sz) + iW_time) - 0.5) * iW_intensity4 * iW_tf;\n"
			+ "\t\t\tvec2 iW_gu = iW_uv + vec2(iW_off4);\n"
			+ "\t\t\tvec4 iW_gc = texture(" + SAMPLER + ", iW_gu);\n"
			+ "\t\t\tiW_gc.r = mix(iW_gc.r, texture(" + SAMPLER + ", iW_gu + vec2(iW_colorOffset4, 0.0)).r, iW_tf);\n"
			+ "\t\t\tiW_gc.b = mix(iW_gc.b, texture(" + SAMPLER + ", iW_gu - vec2(iW_colorOffset4, 0.0)).b, iW_tf);\n"
			+ "\t\t\tiW_out.rgb = wynnBlend(iW_tex, iW_gc, iW_gc.a); break;\n"
			+ "\t\t}\n"
			// Three rotated copies of the coordinate, their nearest lattice line lit.
			+ "\t\tcase 5: {\n"
			+ "\t\t\tvec2 iW_ru = iW_eUV * 2.0;\n"
			+ "\t\t\tmat3 iW_m = mat3(-2, -1, 2, 3, -2, 1, 1, 2, 2);\n"
			+ "\t\t\tvec3 iW_a = vec3(iW_ru, iW_time * 0.5) * iW_m;\n"
			+ "\t\t\tvec3 iW_b = iW_a * iW_m * 0.4; vec3 iW_c = iW_b * iW_m * 0.3;\n"
			+ "\t\t\tiW_out.rgb = iW_tex.rgb + vec3(pow(min(min(length(0.5 - fract(iW_a)), length(0.5 - fract(iW_b))), length(0.5 - fract(iW_c))), 7.0) * 30.0);\n"
			+ "\t\t\tbreak;\n"
			+ "\t\t}\n"
			+ "\t\tcase 6: { iW_out.rgb = wynnAberration(" + SAMPLER + ", iW_uv, 0.0025, iW_time); break; }\n"
			+ "\t\tcase 7: { iW_out = wynnGrayscale(iW_tex); break; }\n"
			+ "\t\tcase 8: { iW_out = vec4(vec3(1.0) - iW_tex.rgb, iW_tex.a); break; }\n"
			// The shadow sweep. WynnIris's own note asks that its numbers not be touched: the
			// waveform, the speed, the frequency and the fades were tuned together over many
			// passes and the look is the whole of them. Atlas and dedicated textures take
			// different ones because a weapon's sweep is read across sprites and armour's is not.
			// The base frequency was a slider once and is a constant now
			// (EntityPatcher.java:1434 substitutes two nought nought for the name), so the
			// dedicated texture's half of it is one and the atlas branch overrides both.
			+ "\t\tcase 9: {\n"
			+ "\t\t\tfloat iW_freq9 = 2.00;\n"
			+ "\t\t\tvec2 iW_sweepCoord9 = " + SWEEP_UV_NAME + "(iW_uv, iW_midTex, iW_texSize, iW_eUV);\n"
			+ "\t\t\tvec2 iW_dir9 = iW_isAtlas ? vec2(0.3, 0.0) : vec2(0.3, -0.07);\n"
			+ "\t\t\tfloat iW_speed9 = iW_isAtlas ? 5.0 : 1.0;\n"
			+ "\t\t\tfloat iW_effFreq9 = iW_isAtlas ? 0.125 : iW_freq9 * 0.5;\n"
			+ "\t\t\tfloat iW_x9 = dot(iW_dir9, iW_sweepCoord9) - iW_time * iW_speed9;\n"
			+ "\t\t\tfloat iW_phase9 = 1.0 - fract(iW_x9 * iW_effFreq9);\n"
			+ "\t\t\tfloat iW_fadeEnd9 = iW_isAtlas ? 0.20 : 0.30;\n"
			+ "\t\t\tfloat iW_fadeStart9 = iW_isAtlas ? 0.035 : 0.08;\n"
			+ "\t\t\tfloat iW_wave9 = smoothstep(0.0, 0.001, iW_phase9) * (1.0 - smoothstep(iW_fadeStart9, iW_fadeEnd9, iW_phase9));\n"
			+ "\t\t\tfloat iW_shine9 = 1.0 - iW_wave9 * 0.9;\n"
			+ "\t\t\tiW_out.rgb = iW_tex.rgb * iW_shine9; break;\n"
			+ "\t\t}\n"
			// The aurora: a sine field rotated by the distance from the sprite's own origin.
			+ "\t\tcase 10: {\n"
			+ "\t\t\tvec2 iW_aUV = iW_rUV;\n"
			+ "\t\t\tfloat iW_r = length(iW_aUV);\n"
			+ "\t\t\tvec2 iW_pa = sin(iW_aUV * iW_r);\n"
			+ "\t\t\tiW_pa = wynnRotate(iW_pa, -cos(iW_r * 5.0 + iW_time * 10.0));\n"
			+ "\t\t\tfloat iW_di = length(exp(-iW_pa * iW_pa));\n"
			+ "\t\t\tiW_di = wynnSmoothen(length(iW_pa), iW_di, 0.9);\n"
			+ "\t\t\tvec4 iW_auroraColor = sin(iW_di * vec4(4.0, 3.0, 2.0, 1.0)) * 0.5 + 0.5;\n"
			+ "\t\t\tiW_out.rgb = wynnBlend(iW_tex, iW_auroraColor, 0.5);\n"
			+ "\t\t\tbreak;\n"
			+ "\t\t}\n"
			// A diagonal band, repeating eight times across the sweep coordinate.
			+ "\t\tcase 11: {\n"
			+ "\t\t\tvec2 iW_contUV11 = " + SWEEP_UV_NAME + "(iW_uv, iW_midTex, iW_texSize, iW_eUV);\n"
			+ "\t\t\tvec2 iW_ru = iW_contUV11 / 8.0;\n"
			+ "\t\t\tfloat iW_bd = sin((iW_ru.x + iW_ru.y + iW_time) * 10.0) * 0.5 + 0.5;\n"
			+ "\t\t\tiW_out.rgb = iW_tex.rgb + vec3(smoothstep(0.7, 1.0, iW_bd) * 0.4);\n"
			+ "\t\t\tbreak;\n"
			+ "\t\t}\n"
			// Two sines beating against each other across the coordinate.
			+ "\t\tcase 12: {\n"
			+ "\t\t\tvec2 iW_contUV12 = " + SWEEP_UV_NAME + "(iW_uv, iW_midTex, iW_texSize, iW_eUV);\n"
			+ "\t\t\tvec2 iW_pu = iW_contUV12 * 4.0; float iW_pt = iW_time * 2.0;\n"
			+ "\t\t\tfloat iW_po = 0.1 + cos(iW_pu.y + sin(0.15 - iW_pt)) + iW_pt;\n"
			+ "\t\t\tfloat iW_pd = 0.9 + sin(iW_pu.x + cos(0.65 + iW_pt)) - iW_pt;\n"
			+ "\t\t\tfloat iW_pp = 8.0 * cos(length(iW_pu) + iW_pd) * sin(iW_po - iW_pd);\n"
			+ "\t\t\tiW_out.rgb = wynnBlend(iW_tex, -sin(iW_pp + vec4(0.5, 0.7, 0.8, 1.0)), 0.1);\n"
			+ "\t\t\tbreak;\n"
			+ "\t\t}\n"
			// The sprite warped by smooth noise. On the atlas the offset is a texel's fraction,
			// on a dedicated texture it is a tenth of the sprite, so the two use different
			// strengths, scales and speeds and are not the same effect at two sizes.
			+ "\t\tcase 13: {\n"
			+ "\t\t\tvec2 iW_distortCoord13 = iW_isAtlas ? (iW_uv * iW_texSize / max(iW_texSize.x, iW_texSize.y)) : iW_uv;\n"
			+ "\t\t\tfloat iW_strength13 = iW_isAtlas ? 0.00025 : 0.1 * sin(10.0) / 2.0;\n"
			+ "\t\t\tfloat iW_distortScale13 = iW_isAtlas ? 1000.0 : 10.0;\n"
			+ "\t\t\tfloat iW_distortSpeed13 = iW_isAtlas ? 4.0 : 2.0;\n"
			+ "\t\t\tfloat iW_noise13 = wynnSmoothNoise(iW_distortCoord13 * iW_distortScale13 + iW_time * iW_distortSpeed13);\n"
			+ "\t\t\tvec2 iW_offset13 = vec2(iW_noise13 - 0.5) * iW_strength13;\n"
			+ "\t\t\tvec4 iW_dc = texture(" + SAMPLER + ", iW_uv + iW_offset13);\n"
			+ "\t\t\tiW_out.rgb = wynnBlend(iW_tex, iW_dc, iW_dc.a); break;\n"
			+ "\t\t}\n"
			+ "\t\tcase 14: {\n"
			+ "\t\t\tvec2 iW_contUV14 = " + SWEEP_UV_NAME + "(iW_uv, iW_midTex, iW_texSize, iW_eUV);\n"
			+ "\t\t\tvec2 iW_cu = iW_contUV14 * 0.3; float iW_ct = iW_time * 4.0;\n"
			+ "\t\t\tvec4 iW_cc = vec4(0.5 + 0.5 * sin(10.0 * iW_cu.x + iW_ct),\n"
			+ "\t\t\t                  0.5 + 0.5 * sin(10.0 * iW_cu.y + iW_ct + 1.0),\n"
			+ "\t\t\t                  0.5 + 0.5 * sin(10.0 * (iW_cu.x + iW_cu.y) + iW_ct + 2.0), 1.0);\n"
			+ "\t\t\tiW_out.rgb = wynnBlend(iW_tex, pow(iW_cc, vec4(2.0)), 0.3);\n"
			+ "\t\t\tbreak;\n"
			+ "\t\t}\n"
			// The ten tints, in the server's own order.
			+ "\t\tcase 15: { iW_out = wynnTintEffect(wynnRgb(80,  130, 230), iW_tex); break; }\n"
			+ "\t\tcase 16: { iW_out = wynnTintEffect(wynnRgb(30,  230, 130), iW_tex); break; }\n"
			+ "\t\tcase 17: { iW_out = wynnTintEffect(wynnRgb(235, 70,  70 ), iW_tex); break; }\n"
			+ "\t\tcase 18: { iW_out = wynnTintEffect(wynnRgb(100, 190, 190), iW_tex); break; }\n"
			+ "\t\tcase 19: { iW_out = wynnTintEffect(wynnRgb(250, 120, 20 ), iW_tex); break; }\n"
			+ "\t\tcase 20: { iW_out = wynnTintEffect(wynnRgb(50,  50,  50 ), iW_tex); break; }\n"
			+ "\t\tcase 21: { iW_out = wynnTintEffect(wynnRgb(250, 230, 230), iW_tex); break; }\n"
			+ "\t\tcase 22: { iW_out = wynnTintEffect(wynnRgb(255, 150, 200), iW_tex); break; }\n"
			+ "\t\tcase 23: { iW_out = wynnTintEffect(wynnRgb(200, 60,  230), iW_tex); break; }\n"
			+ "\t\tcase 24: { iW_out = wynnTintEffect(wynnRgb(240, 240, 80 ), iW_tex); break; }\n"
			// The seven coloured shines, which are the first effect in white and in six hues.
			+ "\t\tcase 25: { iW_out = wynnShiny(wynnRgb(255, 255, 255), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }\n"
			+ "\t\tcase 26: { iW_out = wynnShiny(wynnRgb(85,  255, 85 ), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }\n"
			+ "\t\tcase 27: { iW_out = wynnShiny(wynnRgb(255, 255, 85 ), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }\n"
			+ "\t\tcase 28: { iW_out = wynnShiny(wynnRgb(255, 85,  255), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }\n"
			+ "\t\tcase 29: { iW_out = wynnShiny(wynnRgb(85,  255, 255), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }\n"
			+ "\t\tcase 30: { iW_out = wynnShiny(wynnRgb(255, 85,  85 ), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }\n"
			+ "\t\tcase 31: { iW_out = wynnShiny(wynnRgb(170, 0,   170), 0.4, 2.0, iW_shinySweep, iW_isAtlas, iW_time, iW_tex); break; }\n"
			// The piece the server draws without fog: the colour is left as it is and the
			// relight is skipped, because a fogless piece is one the pack's own scene lighting
			// is not meant to reach either.
			+ "\t\tcase 32: { iW_applyLighting = false; break; }\n"
			+ "\t}\n"
			+ "\tif (iW_applyLighting && iW_knownEffect) {\n"
			+ "\t\tfloat iW_texLuma = max(dot(iW_tex.rgb, vec3(0.2126, 0.7152, 0.0722)), 0.001);\n"
			+ "\t\tfloat iW_inLuma  = max(dot(iW_in.rgb,  vec3(0.2126, 0.7152, 0.0722)), 0.0);\n"
			+ "\t\tif (iW_isTint) {\n"
			// A tint's relight keeps the scene's own HUE where the tint has none of its own
			// to lose, which is what stops a black or grey piece taking on the colour of the
			// light it stands in: beside a blue night, a grey tint would read as brown. The
			// clamp is tight because the estimate is taken in a gamma-encoded space and a
			// near-black texel can divide wildly.
			+ "\t\t\tfloat iW_tintRatio = iW_inLuma / iW_texLuma * wynnTintBrightness;\n"
			+ "\t\t\tfloat iW_outMax = max(iW_out.r, max(iW_out.g, iW_out.b));\n"
			+ "\t\t\tfloat iW_outSat = (iW_outMax - min(iW_out.r, min(iW_out.g, iW_out.b))) / max(iW_outMax, 0.001);\n"
			+ "\t\t\tfloat iW_hueAdopt = 1.0 - smoothstep(0.12, 0.45, iW_outSat);\n"
			+ "\t\t\tvec3 iW_lightScale = vec3(1.0);\n"
			+ "\t\t\tif (iW_hueAdopt > 0.0 && iW_inLuma > 0.001) {\n"
			+ "\t\t\t\tvec3 iW_lightVec = iW_in.rgb / max(iW_tex.rgb, vec3(0.03));\n"
			+ "\t\t\t\tfloat iW_lightLuma = max(dot(iW_lightVec, vec3(0.2126, 0.7152, 0.0722)), 0.001);\n"
			+ "\t\t\t\tvec3 iW_lightHue = clamp(iW_lightVec / iW_lightLuma, vec3(0.70), vec3(1.30));\n"
			+ "\t\t\t\tiW_lightScale = mix(vec3(1.0), iW_lightHue, iW_hueAdopt);\n"
			+ "\t\t\t}\n"
			+ "\t\t\tiW_out.rgb *= clamp(iW_tintRatio, 0.0, 4.0) * iW_lightScale;\n"
			+ "\t\t} else {\n"
			+ "\t\t\tfloat iW_ratio = iW_inLuma / iW_texLuma * wynnGlintBrightness;\n"
			+ "\t\t\tiW_out.rgb *= min(iW_ratio, 1.0);\n"
			+ "\t\t}\n"
			+ "\t}\n"
			+ "\treturn iW_out;\n"
			+ "}";

	/**
	 * Every declaration above, in the order one may call another.
	 * <p>
	 * One list rather than a call per name at the one site that writes them, for the reason
	 * {@link WynncraftSignals#HELPERS} gives about itself: the language wants a function declared
	 * before its first call, and a helper missing from here is a stage that does not compile with
	 * the failure naming the pack's own file.
	 */
	static final String[] HELPERS = {
		BRIGHTNESS, RGB, HSV_TO_RGB, RANDOM_FLOAT, RANDOM_VEC2, NOISE, SMOOTH_NOISE, ROTATE, SMOOTHEN,
		BLEND, GRAYSCALE, TINT, ABERRATION, SHINY, TINT_EFFECT, CONTINUOUS_SWEEP_UV, APPLY};

	private WynncraftGlint() {
	}
}
