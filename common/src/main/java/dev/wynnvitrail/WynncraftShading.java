package dev.wynnvitrail;

import dev.vitrail.glsl.GlslTranslator;

/**
 * What Wynncraft writes into a texture's alpha, and what the two marks it makes there mean for a lit
 * fragment - beside the lift that compensates for a sky the scene was darkened by.
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
 * <strong>Those two strengths are constants, as the glint's two brightnesses are</strong> and for
 * the same reason: WynnIris reads them from Iris's own video settings
 * ({@code iris_wynncraftEntityEmissivity}, {@code IrisVideoSettings.java:21}, shipped at a hundred
 * out of a hundred), and this engine has no such settings, so a uniform nothing fills would be a
 * number the driver leaves at nought and a self-lit piece that stayed dark. The mix is written out
 * rather than folded into the {@code max} it resolves to at one, so that the day either becomes a
 * setting the change is the declaration and not the formula.
 * <p>
 * <strong>The lift WynnIris pairs with the self-lit mark is here, and it is that mark's other
 * arm.</strong> Where no mark is on a texel the fragment is not left alone: a scene one of the dark
 * skies has darkened takes its entities with it, and {@link WynncraftSky#entityBoost} is how much
 * they are lifted back. It is one wherever no such sky is fading in, so the arm is a multiply by one
 * nearly always - and the two arms exclude each other because only one of them can be about the
 * same piece of art. The number comes off the sky rather than off a threshold here, which is why it
 * is a name the caller passes and a member of the block rather than a constant.
 * <p>
 * The item tint WynnIris pairs with all of these is drawn beside them in
 * {@code WynncraftPatch.epilogue}: it reads the item's own identifier and is gated on the draw being
 * an item the game told the id of, which this engine publishes on the entity mesh like every other
 * identifier of the three ({@code GlslTranslator.ENTITY_IDS}).
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
	 * The two corrections a marked texel calls for, and the compensation for a dark sky where
	 * neither mark is on it.
	 * <p>
	 * <strong>One block with two arms, because the marks and the compensation are exclusive.</strong>
	 * A self-lit piece is drawn at its own brightness and a piece under a storm is drawn brighter
	 * than the pack left it; doing both would lift a lamp that the sky had already been compensated
	 * for. WynnIris writes the pair as an {@code if} and an {@code else} of one block
	 * ({@code EntityPatcher.java:1496-1513}) and samples the texel once for both, which is why this
	 * is one method rather than the two it was: the sample a shape test needs is the same sample the
	 * branch needs, and a second call would take it twice.
	 * <p>
	 * <strong>The shader tints are excused from the mark, and the range is WynnIris's.</strong>
	 * Numbers fifteen to twenty-four are the ten colour tints, and a tinted piece is being drawn with
	 * a colour the server picked; lifting its art over that would take the tint off, so the tint
	 * wins. The test is against the number as decoded rather than against the masked one the effect
	 * switch takes, which is WynnIris's own choice ({@code EntityPatcher.java:1500}) and the same
	 * number the library dispatches on once it is masked. The mark is excused and the compensation is
	 * not: a tinted piece under a storm is still a piece under a storm.
	 * <p>
	 * <strong>The strength is a constant, as the glint's two brightnesses are</strong> and for the
	 * same reason: WynnIris reads {@code iris_wynncraftEntityEmissivity} from its own video settings
	 * ({@code IrisVideoSettings.java:21}, shipped at a hundred out of a hundred), and this engine has
	 * no such settings, so a uniform nothing fills would be a number the driver leaves at nought and
	 * a self-lit piece that stayed dark. The mix is written out rather than folded into the
	 * {@code max} it resolves to at one, so that the day it becomes a setting the change is the
	 * declaration and not the formula.
	 *
	 * @param output  the name the pack's first colour output ended up with
	 * @param sampler the name this program's diffuse atlas is declared under
	 * @param effect  the name of the local holding this fragment's glint number
	 * @param boost   the name of the member holding how far this draw's entities are lifted
	 * @return the statements, for the wrapper, after the pack's own body
	 */
	static String lightTweaks(String output, String sampler, String effect, String boost) {
		return "{ vec4 wynnShadingTexel = texture(" + sampler + ", " + GlslTranslator.ENTITY_VERTEX_UV
				+ "); if (" + IS_EMISSIVE_NAME + "(wynnShadingTexel) && !(" + effect + " >= 15 && "
				+ effect + " <= 24)) { " + output + ".rgb = mix(" + output + ".rgb, max(" + output
				+ ".rgb, wynnShadingTexel.rgb), wynnEntityEmissivity); } else { " + compensation(output, boost)
				+ "} } ";
	}

	/**
	 * The compensation for a dark sky on its own, for a program with no diffuse atlas to read a mark
	 * from.
	 * <p>
	 * <strong>Withheld from nothing, where the two marks above are withheld from a program that
	 * declares no atlas.</strong> A mark is a property of a sprite and there is no sprite here to
	 * read one out of, so the two arms that need one are dropped; the compensation is about the scene
	 * the fragment stands in, which such a program is as much a part of as any other, and WynnIris
	 * gives it to every program it patches. Dropping it here would leave the one family of entity
	 * draws that has no texture outside the compensation, which is a dark sky's whole point.
	 *
	 * @param output the name the pack's first colour output ended up with
	 * @param boost  the name of the member holding how far this draw's entities are lifted
	 * @return the statements, for the wrapper, after the pack's own body
	 */
	static String skyCompensation(String output, String boost) {
		return "{ " + compensation(output, boost) + "} ";
	}

	/**
	 * What the compensation does to a colour, as the statements both callers above emit.
	 * <p>
	 * <strong>A luma ramp and not a flat multiply.</strong> A colour already bright is left exactly
	 * as it was, and the boost is mixed in over the band below that, so a scene the sky darkened
	 * gains its shadows back rather than gaining a wash. <strong>And a ceiling, because half again
	 * past one is a colour the target cannot hold:</strong> left to the hardware, three channels
	 * crossing the top together would clamp one by one and shift the hue of whatever crossed first,
	 * so the multiplier is cut back to whatever keeps the brightest of them inside the range. The
	 * floor on the divisor is what keeps a black fragment from dividing by nought, and is WynnIris's
	 * own.
	 */
	private static String compensation(String output, String boost) {
		return "float wynnBoostLuma = dot(" + output + ".rgb, vec3(0.2126, 0.7152, 0.0722)); "
				+ "float wynnBoostScale = mix(" + boost + ", 1.0, smoothstep(0.3, 0.8, wynnBoostLuma)); "
				+ "float wynnBoostMax = max(max(" + output + ".r, " + output + ".g), " + output
				+ ".b); if (wynnBoostMax * wynnBoostScale > 1.0) { wynnBoostScale = 1.0 /"
				+ " max(wynnBoostMax, 1e-5); } " + output + ".rgb *= wynnBoostScale; ";
	}
}
