package dev.wynnvitrail;

import dev.vitrail.glsl.GlslTranslator;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place the engine and the Wynncraft features meet.
 * <p>
 * <strong>Everything WynnIris does it does by editing the pack's own source</strong>, which is a
 * sentence worth reading twice: the effects are not an overlay pass and not a second shader. A pack
 * writes {@code gbuffers_entity.fsh}, and the mod appends to it the code that recognises a
 * Wynncraft weapon and draws its glint. That is why the effects work under any pack, and it is why
 * this port cannot copy WynnIris's code and be done: WynnIris edits a source tree through
 * {@code glsl-transformer}, an AST library, and this engine edits its own text with its own passes.
 * What can be taken over whole is the payload, which is ordinary GLSL against names both engines
 * provide.
 * <p>
 * <strong>The seam is the {@code main} this engine already wraps for its own reasons.</strong> The
 * translator renames the pack's entry point and writes its own around it when a pass needs the alpha
 * test or the coverage mask, and that wrapper runs statements before and after the pack's body. The
 * Wynncraft patch asks for one more reason to be wrapped: a fragment stage of the entity family
 * whose effects are switched on. Nothing else moves. The header gains the decode, the wrapper gains
 * the application, and a pack that draws no Wynncraft item is unchanged because every helper
 * returns nought on its colours before anything is applied.
 * <p>
 * <strong>It is asked of the fragment stage and of the entity family and of nothing else.</strong>
 * The signals live in the entity mesh, so a terrain or sky pass has nothing to decode from, and the
 * vertex stage only carries the colour forward. The text family is where WynnIris puts the
 * transition screens and is the next thing to open here; it is deliberately not opened by this
 * patch today, because the transitions are not written yet and a gate with nothing behind it is a
 * gate nobody can check.
 * <p>
 * <strong>The two halves are a pair and are told apart on purpose.</strong> The vertex stage is
 * where the colour is, so it is there that {@link #neutralisation} takes the signal out of the
 * pack's own reads and {@link #carriesColour} puts it on the wire; the fragment stage is where the
 * value arrives and where {@link #epilogue} spends it. Iris splits them the same way, its
 * neutralisation standing beside its {@code vaColor} rename in the vertex pass
 * ({@code VanillaCoreTransformer.java:462-506}) and its application appended to the fragment's
 * {@code main} ({@code EntityPatcher.appendTranslucencyAlpha}).
 */
public final class WynncraftPatch {

	/**
	 * The uniform the effects animate on, which is the game's own day rather than a frame clock.
	 * <p>
	 * <strong>WynnIris drives every effect from the day and not from real time</strong>, which is
	 * the one thing about the effects that is easy to get wrong and impossible to see in a still
	 * picture. Its {@code iris_globalInfo.GameTime} reads like a clock and is not one: it is the
	 * fraction of the current Minecraft day, which vanilla publishes in its own {@code Globals}
	 * block and which WynnIris fills with {@code (level.getGameTime() % 24000 + partial) / 24000}
	 * ({@code IrisRenderingPipeline.java:1755-1767}). The effects' own scale of three hundred
	 * ({@code EntityPatcher.java:899}) is therefore three hundred units per game day, and the
	 * animation starts again at dawn.
	 * <p>
	 * This engine's day is the same number counted in ticks: {@code render/FrameState.java:595}
	 * takes {@code clock % 24000} and {@code uniform/values/TimeValues.java:33} publishes it as
	 * {@code worldTime}. So the scale is three hundred over twenty-four thousand, and the two
	 * engines' effects advance together rather than merely both advancing.
	 * <p>
	 * <strong>Taken into the block rather than assumed</strong>, because a program gets a block
	 * member only when its pack declares one and the corpus is not unanimous: four of the five
	 * packs studied write {@code uniform int worldTime} and Solas never names it.
	 * {@code GlslTranslator.takeDayClock} is what reads this name and takes it.
	 */
	public static final String DAY_CLOCK = "worldTime";

	/**
	 * Three hundred units of effect time per Minecraft day, which is three hundred over the
	 * twenty-four thousand ticks a day is.
	 */
	private static final String DAY_CLOCK_SCALE = "0.0125";

	private WynncraftPatch() {
	}

	/**
	 * Whether the entity colour has to reach the fragment stage for this program.
	 * <p>
	 * Both stages, and that is the point: the varying is declared on both sides or on neither, and
	 * the vertex stage is the one that writes it. A stage that was left out would leave the other
	 * declaring a location nothing fills, which shifts every location after it without a word.
	 * <p>
	 * Asked of the decode rather than of the effects, so that the two stages agree even where only
	 * one of them has something to do with the value: the gate has to answer the same question for
	 * the pair, and the question is whether the signals are being read at all.
	 */
	public static boolean carriesColour(ProgramStage stage, VertexInputs inputs) {
		if (!WynncraftSettings.decode()) {
			return false;
		}

		return inputs.overlay()
				&& (stage == ProgramStage.VERTEX || stage == ProgramStage.FRAGMENT);
	}

	/**
	 * Whether this stage is given the application: the effects, run after the pack's own colour.
	 * <p>
	 * One stage only, and the rest of the reasoning is in the class comment. The effects are what
	 * the decoder is for, so this asks for them and not for the decode.
	 */
	public static boolean applies(ProgramStage stage, VertexInputs inputs) {
		return WynncraftSettings.effects() && stage == ProgramStage.FRAGMENT
				&& carriesColour(stage, inputs);
	}

	/**
	 * Whether the alpha of the stage's first colour output may be written - false for a pack that
	 * declared it as anything but a {@code vec4}.
	 * <p>
	 * The same gate and the same reason the alpha test carries one
	 * ({@code GlslTranslator.planAlphaEpilogue}): the alpha of a self declared slot nought is not an
	 * alpha, it is whatever the pack packed there, and {@code .a} on a {@code vec3} is not even a
	 * name the language has. A pack that declares one keeps its picture and loses the reduction,
	 * which is the right way round for an effect that is decoration on top of one.
	 *
	 * @param declared the type the pack declared its slot nought output under, or {@code null} where
	 *                 it declared none and the header writes a {@code vec4} of this engine's own
	 */
	public static boolean mayWriteAlpha(String declared) {
		return declared == null || declared.equals("vec4");
	}

	/**
	 * Whether this stage hides the signals from the pack's own colour reads.
	 * <p>
	 * The vertex stage alone, because that is where the colour is read: a pack's own tint comes off
	 * the attribute, and a fragment stage reading the name this engine defines for it would be
	 * reading a value nothing wrote. Iris's own replacement is in its vertex pass for the same
	 * reason.
	 */
	public static boolean neutralises(ProgramStage stage, VertexInputs inputs) {
		return WynncraftSettings.effects() && stage == ProgramStage.VERTEX
				&& carriesColour(stage, inputs);
	}

	/**
	 * The decode, for the header, and the effect libraries behind it where the effects are on.
	 * <p>
	 * <strong>Two groups and not one, because they answer to different switches.</strong> The
	 * decode is what reads the signals and costs a handful of comparisons, so it is written wherever
	 * {@link #carriesColour} is; the effect libraries are the picture, and only the fragment stage
	 * that is really given an application has anything to call them with. A program translated with
	 * the effects off keeps the decode - which is what the offline test reads back - and carries none
	 * of the effects' text.
	 * <p>
	 * <strong>Two effect libraries rather than one, because the two signal families are read at
	 * different moments.</strong> The glint and the translucency level come off the carried colour,
	 * and {@link WynncraftGlint} is the nineteen effects they select; the unlit and self-lit markers
	 * come off a texel, and {@link WynncraftShading} is the two corrections those call for. Both are
	 * written on the one condition, that the program is given an application, and both stand above
	 * the wrapper that calls them.
	 * <p>
	 * They are written even where {@link #epilogue} ends up withholding the calls, which happens on
	 * a program declaring no diffuse sampler: an unused function is a few hundred lines the compiler
	 * discards, and the alternative is a header that depends on the program's samplers as well as on
	 * the switch, which is one more thing for the translation cache to be wrong about.
	 */
	public static List<String> helpers(ProgramStage stage, VertexInputs inputs) {
		if (!carriesColour(stage, inputs)) {
			return List.of();
		}

		List<String> lines = new ArrayList<>();
		lines.add("// WynnVitrail: the Wynncraft signal decode. See WynncraftSignals.");
		for (String helper : WynncraftSignals.HELPERS) {
			lines.addAll(helper.lines().toList());
		}

		if (applies(stage, inputs)) {
			lines.add("// WynnVitrail: the Wynncraft glint effects. See WynncraftGlint.");
			for (String helper : WynncraftGlint.HELPERS) {
				lines.addAll(helper.lines().toList());
			}
			lines.add("// WynnVitrail: what a texture's alpha says. See WynncraftShading.");
			for (String helper : WynncraftShading.HELPERS) {
				lines.addAll(helper.lines().toList());
			}
		}

		return List.copyOf(lines);
	}

	/**
	 * The lines that hide the signals from the pack's own body, for the vertex header.
	 * <p>
	 * <strong>A redefinition of the name the pack reads and not a rewrite of every read.</strong>
	 * That name is a macro of the head rather than a variable of the pack's
	 * ({@link GlslTranslator#VERTEX_COLOUR}, which the entity head points at the format's
	 * {@code Color} element), so taking the signal out is one {@code #undef} and one {@code #define}
	 * and needs no pass over the body at all. Iris has to rewrite each read because its name is a
	 * real one by then; here the macro is still a macro.
	 * <p>
	 * It has to be written after the head that defines the name and before the body that reads it,
	 * which is a place the header has and the body does not: the whole of the header stands above
	 * the pack's own text with nothing between them.
	 * <p>
	 * The attribute is spelled out rather than the macro, because the macro is the thing being
	 * redefined: {@code #define of_Color wynnNeutralColour(of_Color)} would ask the preprocessor to
	 * expand a name it is in the middle of defining, which it refuses.
	 */
	public static List<String> neutralisation(ProgramStage stage, VertexInputs inputs) {
		if (!neutralises(stage, inputs)) {
			return List.of();
		}

		return List.of(
				"// WynnVitrail: the pack's own colour reads see the signals as white.",
				"#undef of_Color",
				"#define of_Color " + WynncraftSignals.NEUTRALISE_NAME + "(Color)");
	}

	/**
	 * The application, for the wrapper, after the pack's own body has run and left its colour in
	 * {@code output}.
	 * <p>
	 * <strong>After and not before, because there is nothing to apply before.</strong> A Wynncraft
	 * item's glint is not something the pack's own program knows about and not something it left a
	 * place for: it is a change to the colour the pack has already decided. Iris reaches the same
	 * place from the other direction, its glint walking the pack's AST and rewriting the assignment
	 * that writes the output, which is the same statement this appends one to.
	 * <p>
	 * <strong>Four steps, and the order among them is a result rather than a preference.</strong> It
	 * is WynnIris's own order ({@code EntityPatcher.java:1484-1513}), and each of the three
	 * adjacencies has a reason:
	 * <ol>
	 * <li><strong>The unlit correction first</strong>, before anything rewrites the colour. It reads
	 * the shading out of the colour by division, so it has to see the colour the pack produced: run
	 * after a glint it would be dividing a pixel the effect had rebuilt out of the texture, and
	 * dividing an effect's own output by a light is not what "painted unlit" meant.</li>
	 * <li><strong>The glint second and the reduction third.</strong> Seven of the effects rebuild the
	 * whole pixel, alpha included, out of the texture they read - a shine and a tint both end at the
	 * texture's own alpha - so a reduction applied first would be undone by any of them, where a
	 * clamp applied second holds whatever the effect left and brings it down only if it is above the
	 * target.</li>
	 * <li><strong>The self-lit lift last.</strong> It reads the same two things the glint does - the
	 * decoded number and the texture - and both have to be settled before it can decide whether this
	 * fragment is a tint, which is the one case it excuses.</li>
	 * </ol>
	 * <p>
	 * The alpha is clamped to the target rather than multiplied by it, and the difference is
	 * WynnIris's own correction rather than a preference: a pack that already carried the reduced
	 * alpha through its own arithmetic has an alpha at or below the target and the minimum leaves it
	 * alone, while a pack that wrote its alpha back to one has the target restored. Multiplying
	 * would take the second case below the target and the first case further below it still, and
	 * low-level VFX disappeared under it ({@code EntityPatcher.java:2183-2186}).
	 * <p>
	 * The branches are kept because the level of an ordinary fragment decodes to nought and the
	 * effect of one to nought, and a branch that is not taken costs a fragment of the corpus nothing;
	 * the alpha the reduction would write is one the minimum would not move in any case.
	 * <p>
	 * <strong>The two decoded numbers are read once, into locals of this block.</strong> Three of the
	 * four steps want one of them and the self-lit lift wants both, and a decode repeated is a decode
	 * that can drift. The effect's number is only read where there is a sampler to draw with, so a
	 * program that has none is left with no local it does not use.
	 *
	 * @param output  the name the pack's first colour output ended up with, which is its own where it
	 *                declared one and this engine's {@code ofFragData0} where it did not
	 * @param sampler the name this program's diffuse atlas is declared under, or {@code null} where
	 *                it declares none, which withholds everything that samples and keeps the
	 *                reduction
	 * @return the statements, or empty where this stage gets no application
	 */
	public static String epilogue(ProgramStage stage, VertexInputs inputs, String output,
			String sampler) {
		if (!applies(stage, inputs)) {
			return "";
		}

		String colour = GlslTranslator.ENTITY_VERTEX_COLOR;

		StringBuilder statements = new StringBuilder("{ ");
		if (sampler != null) {
			statements.append("int wynnEffect = ").append(WynncraftSignals.GLINT_NAME).append("(")
					.append(colour).append("); ");
		}

		statements.append("int wynnLevel = ").append(WynncraftSignals.TRANSLUCENCY_NAME).append("(")
				.append(colour).append("); ");

		if (sampler != null) {
			statements.append(WynncraftShading.shadeless(output, sampler));
			statements.append(glint(sampler, output));
		}

		statements.append("if (wynnLevel > 0) { ").append(output).append(".a = min(").append(output)
				.append(".a, ").append(WynncraftSignals.ALPHA_NAME).append("(wynnLevel)); } ");

		if (sampler != null) {
			statements.append(WynncraftShading.emissive(output, sampler, "wynnEffect"));
		}

		statements.append("} ");

		return statements.toString();
	}

	/**
	 * One item's glint, drawn over the colour the pack left.
	 * <p>
	 * <strong>Six coordinates are worked out here and none of them is the effect's own business.</strong>
	 * WynnIris computes the same six at the same place ({@code EntityPatcher.java:894-917}) and hands
	 * them to the library as one call, and the split is the same: this half is arithmetic about
	 * where a sprite lies in an atlas, and the library half is what to do with it once found. They
	 * are here rather than inside the library because they are read off the MESH - the coordinate,
	 * the middle of the sprite - and the library takes values.
	 * <p>
	 * The number itself is not decoded here: the wrapper reads it once into {@code wynnEffect}, which
	 * the self-lit lift reads as well, and this is the branch on it.
	 * <ul>
	 * <li><strong>The item's own coordinate</strong>, which is the mesh's and not the pack's: see
	 * {@link GlslTranslator#ENTITY_VERTEX_UV}.</li>
	 * <li><strong>The middle of its sprite</strong>, which is what says whether the sprite's
	 * neighbours are a few texels away: see {@link GlslTranslator#ENTITY_VERTEX_MID_TEX}.</li>
	 * <li><strong>The size of the texture</strong>, taken from the sampler itself, which is what
	 * tells an atlas from an armour sheet at all: Minecraft's atlases are at least two thousand and
	 * forty-eight pixels on a side and a dedicated texture is not.</li>
	 * <li><strong>A per-sprite coordinate</strong>, which is the atlas coordinate folded back into
	 * the sprite the fragment lies in - a sixteenth of the atlas each way, which is the size every
	 * item sprite is - and then scaled. It is what the two effects that read a coordinate rather
	 * than a sweep use.</li>
	 * <li><strong>A radial coordinate</strong>, which is the first coordinate measured from the
	 * middle of the polygon rather than from the sprite's origin, quartered and pushed out to eight.
	 * What it buys is the aurora: a pattern that reads as a shape on the item rather than as a
	 * pattern on its sprite, so that the four sides of a block of equipment carry one figure between
	 * them instead of four.</li>
	 * <li><strong>A screen-rate coordinate</strong>, whose scale is the derivative of the
	 * coordinate rather than a constant, so that a pattern measured by it keeps its size on screen
	 * however far away the item is. <strong>Nothing reads it today</strong>: it is the fifth
	 * argument of WynnIris's own call ({@code EntityPatcher.java:910,915}) and the library's switch
	 * never mentions it, so it is carried because the call has the same shape on both engines and
	 * not because an effect is waiting for it. Dropping it is a change to both to make at once.</li>
	 * </ul>
	 * <p>
	 * The time is the caller's own arithmetic and the reason is in {@link #DAY_CLOCK}. WynnIris's
	 * three hundred is against a day expressed as a fraction; here the day is in ticks, so the scale
	 * is that three hundred over twenty-four thousand.
	 * <p>
	 * The number is masked to thirty-one, which is WynnIris's own mask and not a guard against a
	 * decode that ran away: five bits is what the signal's red can carry, and the mask is what makes
	 * the thirty-second effect - the fogless piece - reachable only through the armour path that
	 * names it directly rather than through a signal. The self-lit lift reads the unmasked number,
	 * which is WynnIris's own choice and does not matter here: the tint range it tests is below the
	 * mask's edge, so both spellings agree over it.
	 */
	private static String glint(String sampler, String output) {
		String uv = GlslTranslator.ENTITY_VERTEX_UV;
		String mid = GlslTranslator.ENTITY_VERTEX_MID_TEX;

		StringBuilder code = new StringBuilder();
		code.append("if (wynnEffect != 0) { ");
		code.append("vec2 wynnSize = vec2(textureSize(").append(sampler).append(", 0)); ");
		code.append("bool wynnAtlas = max(wynnSize.x, wynnSize.y) > 2000.0; ");
		code.append("vec2 wynnUv = ").append(uv).append("; ");
		code.append("float wynnTime = float(").append(DAY_CLOCK).append(") * ")
				.append(DAY_CLOCK_SCALE).append("; ");
		code.append("vec4 wynnSample = texture(").append(sampler).append(", wynnUv); ");
		code.append("vec2 wynnEuv; if (wynnAtlas) { ");
		code.append("vec2 wynnSprite = fract(wynnUv * wynnSize / 16.0); ");
		code.append("wynnEuv = (wynnSprite - 1.0) * vec2(wynnSize.x / wynnSize.y, 1.0) / 5.0; } ");
		code.append("else { wynnEuv = (wynnUv - 1.0) * vec2(wynnSize.x / wynnSize.y, 1.0); } ");
		code.append("vec2 wynnSuv = fract(wynnUv / (max(max(abs(dFdx(wynnUv)), abs(dFdy(wynnUv))), ")
				.append("vec2(1e-6)) * 50.0)) * 4.0; ");
		code.append("vec2 wynnFull = ").append(WynncraftGlint.SWEEP_UV_NAME).append("(wynnUv, ")
				.append(mid).append(", wynnSize, wynnEuv); ");
		code.append("vec2 wynnMid = ").append(WynncraftGlint.SWEEP_UV_NAME).append("(")
				.append(mid).append(", ").append(mid).append(", wynnSize, vec2(0.5)); ");
		code.append("vec2 wynnRuv = (wynnFull - wynnMid) * 0.25 + vec2(8.0); ");
		code.append(output).append(" = ").append(WynncraftGlint.APPLY_NAME).append("(")
				.append(sampler).append(", wynnEffect & 31, wynnUv, wynnEuv, wynnSuv, ").append(mid)
				.append(", wynnRuv, wynnSize, wynnAtlas, wynnTime, wynnSample, ").append(output)
				.append("); } ");

		return code.toString();
	}
}
