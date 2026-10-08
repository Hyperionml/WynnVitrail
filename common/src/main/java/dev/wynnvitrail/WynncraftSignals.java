package dev.wynnvitrail;

/**
 * Wynncraft's signal decode, as GLSL, and the effects that hang off it.
 * <p>
 * <strong>Wynncraft does not tell an engine what it is drawing; it hides the answer in the mesh.</strong>
 * The resource pack the server hands out takes channels a shader would otherwise scale by and puts
 * an identifier in them: a weapon's vertex colour carries the glint effect's number in red, a
 * translucent model carries its translucency level in red under a green that no ordinary tint
 * reaches, and a skybox entity carries its variant in the blue of its own texture. Nothing in the
 * game's data marks any of it, which is why the decode reads numbers rather than names, and why the
 * ranges are written as tightly as they are: each one is narrow enough that a tint the game itself
 * could produce does not land inside it.
 * <p>
 * <strong>These are WynnIris's own thresholds, taken over unchanged</strong>
 * ({@code pipeline/transform/transformer/EntityPatcher.java:88-113}), because the pack on the other
 * end is the same pack: a range widened here would start matching ordinary items, and a range
 * narrowed would stop matching the weapons it was measured against.
 * <p>
 * The names are spelled {@code wynn} rather than {@code iris}: every one of them is injected into a
 * pack's own file, where a pack is free to have used any name it likes, so they have to be ones no
 * pack would have written. Iris buys the same property by spelling its own {@code iris_}.
 * <p>
 * <strong>Two further families are marked in a texture's alpha rather than in the mesh</strong> -
 * one says a piece of art is painted unlit, the other that it lights itself - and they are not
 * decoded here. They are read off a texel rather than off a carried colour, and what they call for
 * is a correction to the colour rather than a value spent on a branch, so they live beside it in
 * {@link WynncraftShading}.
 */
final class WynncraftSignals {

	/** The function the translucency level comes out of, named here so one caller cannot misspell it. */
	static final String TRANSLUCENCY_NAME = "wynnTranslucency";

	/** The function the glint effect's number comes out of, and the effect library's entry point. */
	static final String GLINT_NAME = "wynnGlintId";

	/** The function the alpha a translucent fragment is brought down to comes out of. */
	static final String ALPHA_NAME = "wynnTranslucentAlpha";

	/** The function the pack's own colour reads go through in place of a signal. */
	static final String NEUTRALISE_NAME = "wynnNeutralColour";

	/**
	 * The glint signal.
	 * <p>
	 * {@code G} is the whole of one channel at 255/255 and {@code B} is nothing, which together are
	 * a colour no tint produces. Red carries the effect: 1 to 32 are the numbers that mean something,
	 * and the upper bound below is what rejects the display entities whose vertex colours happen to
	 * have a high green and a low blue but a red far above the glint range.
	 */
	private static final String IS_GLINT =
			"bool wynnIsGlint(vec4 colour) {\n"
			+ "\treturn colour.g > 0.998 && colour.b < 0.01 && colour.r > 0.002 && colour.r < 0.13;\n"
			+ "}";

	/**
	 * The translucency signal.
	 * <p>
	 * {@code G} is 254/255 here where the glint's is 255/255, and that one step is the whole of what
	 * tells the two apart: the ranges do not overlap, so a fragment can be one or the other and never
	 * both. Red times 255 is the level, applied as an alpha reduction.
	 */
	private static final String IS_TRANSLUCENT =
			"bool wynnIsTranslucent(vec4 colour) {\n"
			+ "\treturn colour.g > 0.994 && colour.g < 0.998 && colour.b < 0.01"
			+ " && colour.r > 0.002 && colour.r < 0.998;\n"
			+ "}";

	/** The effect's number out of the glint signal's red, or nought where this is not one. */
	private static final String GLINT_ID =
			"int " + GLINT_NAME + "(vec4 colour) {\n"
			+ "\treturn wynnIsGlint(colour) ? int(round(colour.r * 255.0)) : 0;\n"
			+ "}";

	/** The level out of the translucency signal's red, or nought where this is not one. */
	private static final String TRANSLUCENCY =
			"int " + TRANSLUCENCY_NAME + "(vec4 colour) {\n"
			+ "\treturn wynnIsTranslucent(colour) ? int(round(colour.r * 255.0)) : 0;\n"
			+ "}";

	/**
	 * The alpha a translucent fragment is brought down to, as a function of the decoded level.
	 * <p>
	 * <strong>Nought point one is a floor and not a step, and it was measured rather than chosen.</strong>
	 * The pack's own expression is {@code color.a = mix(color.a, 0.0, level / 100.0)}, which at a
	 * level of a hundred is invisible; WynnIris found that too aggressive against real VFX entities
	 * and floors the result, {@code VanillaCoreTransformer.java:483} and
	 * {@code EntityPatcher.java:2185} carrying the same {@code max(0.10, ...)}. It is one function
	 * rather than two copies because it is read twice, by the neutralisation in the vertex stage and
	 * by the application in the fragment stage, and two copies of a formula are two formulas the day
	 * one of them is tuned.
	 */
	private static final String TRANSLUCENT_ALPHA =
			"float " + ALPHA_NAME + "(int level) {\n"
			+ "\treturn max(0.10, 1.0 - float(level) / 100.0);\n"
			+ "}";

	/**
	 * The colour the pack's own body sees where the mesh carries a signal.
	 * <p>
	 * <strong>This is the half of the port that makes the picture rather than the picture's
	 * subject.</strong> A Wynncraft weapon's vertex colour IS its glint number, and a pack that
	 * multiplies its texture by that colour - which is what every pack does, the vertex colour being
	 * a tint - draws the number as a tint. Iris replaces every read of it
	 * ({@code VanillaCoreTransformer.java:486-492} and {@code :503-504}); this does the same from the
	 * other end, by redefining the name the pack reads, and what it costs is one word of macro.
	 * <p>
	 * A glint becomes white, which is to say the tint is taken off and the glint's own effect is
	 * left to draw it. A translucent model becomes white with the reduced alpha, so that a pack
	 * carrying that alpha through its own arithmetic ends up at the same place as the application
	 * appended at the end of its main. Everything else is handed back untouched, which is what keeps
	 * a dyed leather chestplate the colour it was.
	 * <p>
	 * The other signal families WynnIris knows are not here - the effect and movement greens, and
	 * the mount armour overlay's alpha markers - because none of them is decoded yet. Neutralising a
	 * channel this engine cannot read would be asserting a format the same way an effect written
	 * against it would, and the effects come next. The two that ride in a texture's alpha are
	 * decoded, and are corrected rather than neutralised for a reason worth naming: a texture's
	 * alpha is a channel the pack blends by on purpose, so taking the marker out of it would take
	 * the blending with it. {@link WynncraftShading} leaves the alpha alone and fixes the colour.
	 */
	private static final String NEUTRALISE =
			"vec4 " + NEUTRALISE_NAME + "(vec4 colour) {\n"
			+ "\tif (wynnIsGlint(colour)) {\n"
			+ "\t\treturn vec4(1.0);\n"
			+ "\t}\n"
			+ "\tif (wynnIsTranslucent(colour)) {\n"
			+ "\t\treturn vec4(1.0, 1.0, 1.0, " + ALPHA_NAME + "(" + TRANSLUCENCY_NAME + "(colour)));\n"
			+ "\t}\n"
			+ "\treturn colour;\n"
			+ "}";

	/**
	 * Every helper above, in the order one may call another.
	 * <p>
	 * One list rather than a call per name at the one site that writes them, so that a helper added
	 * below is added here or not at all: a function whose callee the header never wrote is a stage
	 * that does not compile, and the failure would name the pack's own file.
	 */
	static final String[] HELPERS = {
		IS_GLINT, IS_TRANSLUCENT, GLINT_ID, TRANSLUCENCY, TRANSLUCENT_ALPHA, NEUTRALISE};

	private WynncraftSignals() {
	}
}
