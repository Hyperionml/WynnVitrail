package dev.wynnvitrail;

import dev.vitrail.glsl.GlslTranslator;

/**
 * What Wynncraft writes into a texture's alpha, and the two things it means for a lit fragment.
 * <p>
 * <strong>A second signal family, carried the other way round.</strong> The glint and the
 * translucency level ride in the mesh's vertex colour ({@link WynncraftSignals}) and are read before
 * anything is sampled; these two ride in the texel the pack has already sampled, and are read after.
 * Wynncraft writes an alpha of two hundred and fifty-one out of two hundred and fifty-five to say
 * that a piece of art is painted unlit, and one of two hundred and fifty-four to say that a piece
 * lights itself. It writes them there because a texture's alpha is the one channel nothing reads as
 * a number, and what that costs a pack is not a misread value but a missing instruction: art drawn
 * without baked light is shaded like any other surface, and art drawn to glow is darkened like any
 * other surface.
 * <p>
 * <strong>The two are WynnIris's, thresholds and formulas unchanged</strong>
 * ({@code EntityPatcher.java:1000-1018} for the unlit half, {@code :138-143} for the self-lit one,
 * and the application at {@code :1496-1513}).
 * <ul>
 * <li><strong>Unlit</strong> is the shading divided back out: Wynncraft bakes no directional light
 * into such a texture, but the vertex colour the mesh still carries is a light, so the pack has
 * multiplied the art by it and the fix is one division, floored so that a mesh whose colour is
 * black does not turn the division into an infinity.</li>
 * <li><strong>Self-lit</strong> is the art lifted to its own brightness, and nothing is divided
 * out: a lit piece keeps whatever the pack did to it and is raised to at least the art's own colour.
 * With the strength at its default of one this reads as "the texture wins where it is brighter",
 * which is what makes a lit torch on a mob's model stay lit in a dark scene.</li>
 * </ul>
 * <p>
 * <strong>The strength is a constant, as the glint's two brightnesses are</strong> and for the same
 * reason: WynnIris reads it from Iris's own video settings ({@code iris_wynncraftEntityEmissivity},
 * {@code IrisVideoSettings.java:21}, shipped at a hundred out of a hundred), and this engine has no
 * such settings, so a uniform nothing fills would be a number the driver leaves at nought and a
 * self-lit piece that stayed dark. The mix is written out rather than folded into the
 * {@code max} it resolves to at one, so that the day this becomes a setting the change is the
 * declaration and not the formula.
 * <p>
 * <strong>Two things WynnIris pairs with these are deliberately absent.</strong> Its brightness
 * boost compensates for a dark skybox and is worth one whenever no dark skybox is on screen, so
 * there is nothing to port until the skyboxes are written; its item tint is gated on the id of the
 * item being drawn, which this engine does not publish on an entity mesh at all
 * ({@code render/EngineOptions.java:161}).
 */
final class WynncraftShading {

	/**
	 * Says whether a texel is marked as painted unlit.
	 * <p>
	 * A tolerance rather than an equality, which is WynnIris's own spelling
	 * ({@code EntityPatcher.java:1003}) and is kept as it stands: the marking is one byte of a
	 * texture read back through a sampler, and a pack that filters its atlas rather than snapping to
	 * a texel lands between two rather than on one.
	 */
	static final String IS_SHADELESS_NAME = "wynnIsShadeless";

	/**
	 * Says whether a texel is marked as lighting itself.
	 * <p>
	 * The green is read as well, and that is not belt and braces: a skybox is marked by a green of
	 * two hundred and fifty-one under an alpha of two hundred and fifty-four, so the alpha alone
	 * would claim every skybox texel as a self-lit one. WynnIris excludes the skybox's green here
	 * ({@code EntityPatcher.java:139-143}) and the same exclusion is kept, which is what lets the
	 * two families be told apart by the signal rather than by which stage is reading it.
	 */
	static final String IS_EMISSIVE_NAME = "wynnIsEmissive";

	/**
	 * The strength a self-lit texel is lifted by, which is WynnIris's setting at its shipped default
	 * of a hundred out of a hundred.
	 */
	private static final String EMISSIVITY = "const float wynnEntityEmissivity = 1.0;";

	private static final String IS_SHADELESS =
			"bool " + IS_SHADELESS_NAME + "(vec4 texel) {\n"
			+ "\treturn abs(texel.a * 255.0 - 251.0) < 0.5;\n"
			+ "}";

	private static final String IS_EMISSIVE =
			"bool " + IS_EMISSIVE_NAME + "(vec4 texel) {\n"
			+ "\tint a = int(round(texel.a * 255.0));\n"
			+ "\tint g = int(round(texel.g * 255.0));\n"
			+ "\treturn a == 254 && g != 251;\n"
			+ "}";

	/**
	 * Every helper above, in the order one may call another.
	 * <p>
	 * One list rather than a call per name at the one site that writes them, for the reason
	 * {@link WynncraftSignals#HELPERS} gives: a helper added below is added here or not at all, and a
	 * callee the header never wrote is a stage that does not compile.
	 */
	static final String[] HELPERS = {EMISSIVITY, IS_SHADELESS, IS_EMISSIVE};

	private WynncraftShading() {
	}

	/**
	 * The unlit piece's shading, divided back out of the colour the pack has already decided.
	 * <p>
	 * <strong>The divisor is the neutralised colour and not the carried one, and that is the one
	 * thing about this half that is not a straight transcription.</strong> WynnIris divides by
	 * {@code iris_vertexColor} ({@code EntityPatcher.java:1004}), which is white on any mesh that
	 * carries a signal - its vertex stage sets it to {@code vec4(1.0)} the moment it recognises one
	 * ({@code :1264}) - so on such a mesh its division is a division by one and changes nothing.
	 * This engine carries the RAW colour and hands the pack the white one through
	 * {@link WynncraftSignals#NEUTRALISE_NAME}, so dividing by the raw colour here would divide by
	 * the signal itself: a glint's number is a red of about nought point nought three and a blue of
	 * nought, and the floor below would turn both into a twentyfold blowout. Naming the neutraliser
	 * is what makes the two engines' divisions the same number rather than merely the same
	 * expression.
	 * <p>
	 * <strong>The floor on the divisor is the rest of the care here.</strong> A Wynncraft mesh whose
	 * vertex colour is black would divide the fragment to an infinity and then to a black pixel, and
	 * black is the one answer that is certainly wrong for a piece marked unlit; five per cent is
	 * WynnIris's own floor and is kept rather than re-chosen, because a floor that is a shade
	 * different is a picture that is a shade different.
	 * <p>
	 * The texel is sampled again rather than taken from the pack, which looks wasteful and is not:
	 * what the marking says is a property of the SPRITE, and the pack's own sample may have been
	 * taken at a different coordinate, or not taken at all on a program that draws a colour without
	 * reading a texture.
	 *
	 * @param output  the name the pack's first colour output ended up with
	 * @param sampler the name this program's diffuse atlas is declared under
	 * @return the statements, for the wrapper, after the pack's own body
	 */
	static String shadeless(String output, String sampler) {
		return "{ vec4 wynnShadingTexel = texture(" + sampler + ", "
				+ GlslTranslator.ENTITY_VERTEX_UV + "); if (" + IS_SHADELESS_NAME
				+ "(wynnShadingTexel)) { " + output + ".rgb /= max("
				+ WynncraftSignals.NEUTRALISE_NAME + "(" + GlslTranslator.ENTITY_VERTEX_COLOR
				+ ").rgb, vec3(0.05)); } } ";
	}

	/**
	 * The self-lit piece's own colour, lifted into the fragment the pack has already decided.
	 * <p>
	 * <strong>It is a mix rather than an assignment, and that is what keeps a lit piece from
	 * becoming a flat one.</strong> Lifting to the art's own colour would throw away every darkening
	 * the pack meant - a piece in shadow, a piece under water, a piece in a boss's purple light
	 * - and the strength at one still reads as a lift because {@code max} takes the brighter of the
	 * two. What the setting buys when it is not one is the ability to settle somewhere between the
	 * two colours, which is why the argument is not folded away.
	 * <p>
	 * <strong>The shader tints are excused, and the range is WynnIris's.</strong> Numbers fifteen to
	 * twenty-four are the ten colour tints, and a tinted piece is being drawn with a colour the
	 * server picked; lifting its art over that would take the tint off, so the tint wins. The test
	 * is against the number as decoded rather than against the masked one the effect switch takes,
	 * which is WynnIris's own choice ({@code EntityPatcher.java:1500}) and the same number the
	 * library dispatches on once it is masked.
	 *
	 * @param output  the name the pack's first colour output ended up with
	 * @param sampler the name this program's diffuse atlas is declared under
	 * @param effect  the name of the local holding this fragment's glint number
	 * @return the statements, for the wrapper, after the pack's own body
	 */
	static String emissive(String output, String sampler, String effect) {
		return "{ vec4 wynnShadingTexel = texture(" + sampler + ", "
				+ GlslTranslator.ENTITY_VERTEX_UV + "); if (" + IS_EMISSIVE_NAME
				+ "(wynnShadingTexel) && !(" + effect + " >= 15 && " + effect + " <= 24)) { " + output
				+ ".rgb = mix(" + output + ".rgb, max(" + output + ".rgb, wynnShadingTexel.rgb), "
				+ "wynnEntityEmissivity); } } ";
	}
}
