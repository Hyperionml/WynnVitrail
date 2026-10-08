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

	/** The decode, for the header. Empty where this stage does not get it. */
	public static List<String> helpers(ProgramStage stage, VertexInputs inputs) {
		if (!carriesColour(stage, inputs)) {
			return List.of();
		}

		List<String> lines = new ArrayList<>();
		lines.add("// WynnVitrail: the Wynncraft signal decode. See WynncraftSignals.");
		for (String helper : WynncraftSignals.HELPERS) {
			lines.addAll(helper.lines().toList());
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
	 * The alpha is clamped to the target rather than multiplied by it, and the difference is
	 * WynnIris's own correction rather than a preference: a pack that already carried the reduced
	 * alpha through its own arithmetic has an alpha at or below the target and the minimum leaves it
	 * alone, while a pack that wrote its alpha back to one has the target restored. Multiplying
	 * would take the second case below the target and the first case further below it still, and
	 * low-level VFX disappeared under it ({@code EntityPatcher.java:2183-2186}).
	 * <p>
	 * The test is kept because the level of an ordinary fragment decodes to nought, and a branch
	 * that is not taken costs a fragment of the corpus nothing; the alpha it would write is one the
	 * minimum would not move in any case.
	 *
	 * @param output the name the pack's first colour output ended up with, which is its own where it
	 *               declared one and this engine's {@code ofFragData0} where it did not
	 * @return the statement, or empty where this stage gets no application
	 */
	public static String epilogue(ProgramStage stage, VertexInputs inputs, String output) {
		if (!applies(stage, inputs)) {
			return "";
		}

		return "{ int wynnLevel = " + WynncraftSignals.TRANSLUCENCY_NAME + "("
				+ GlslTranslator.ENTITY_VERTEX_COLOR + "); if (wynnLevel > 0) { " + output
				+ ".a = min(" + output + ".a, " + WynncraftSignals.ALPHA_NAME
				+ "(wynnLevel)); } } ";
	}
}
