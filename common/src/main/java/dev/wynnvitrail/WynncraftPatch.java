package dev.wynnvitrail;

import dev.vitrail.glsl.EntityVertex;
import dev.vitrail.glsl.GlslTranslator;
import dev.vitrail.glsl.LegacyGlsl;
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
	 * fraction of the current Minecraft day, which WynnIris fills with
	 * {@code (level.getGameTime() % 24000 + partial) / 24000}
	 * ({@code IrisRenderingPipeline.java:1755-1767}). The effects' own scale of three hundred
	 * ({@code EntityPatcher.java:899}) is therefore three hundred units per game day, and the
	 * animation starts again at dawn.
	 * <p>
	 * <strong>This engine's day is the same number counted in ticks, and the fraction on it is why
	 * this name exists at all.</strong> {@code worldTime} is the day the packs read: an {@code int}
	 * of the whole ticks, published by {@code uniform/values/TimeValues}. A day is twenty ticks a
	 * second and a sky is twelve thousand units of it, so a sky driven from the integer steps half a
	 * unit twenty times a second where WynnIris's slides, and the glint's own three hundred steps
	 * with it. The transitions this engine draws over the finished picture already take the
	 * fraction ({@code render/WynncraftOverlay.java:393}), so a patch animating from the integer
	 * would have its two halves counting differently.
	 * <p>
	 * <strong>This engine's name rather than the pack's, so that nothing has to be taken.</strong>
	 * {@code worldTime} is taken into the block because a program gets a member only where its own
	 * pack declared one and the corpus is not unanimous: four of the five packs studied write
	 * {@code uniform int worldTime} and Solas never names it. A name no pack has written is declared
	 * by this engine on every program the effects reach, which is one member and no question.
	 * {@code GlslTranslator.takeDayClock} is what writes it.
	 */
	public static final String DAY_CLOCK = "wynnDayClock";

	/**
	 * Three hundred units of effect time per Minecraft day, which is three hundred over the
	 * twenty-four thousand ticks a day is.
	 */
	private static final String DAY_CLOCK_SCALE = "0.0125";

	/** The ticks in a Minecraft day, which is what turns the day clock back into a fraction of one. */
	private static final String DAY_TICKS = "24000.0";

	/**
	 * Twelve thousand units of sky time per day, which is the resource pack's own number and half
	 * of the glint's three hundred over the same day.
	 * <p>
	 * Kept as a second scale rather than folded into {@link #DAY_CLOCK_SCALE} because the two are
	 * the pack's numbers and not this engine's, and a reader comparing the skies against the glint
	 * has to be able to see that they are the numbers they are. WynnIris works its skies out of
	 * {@code fract(GameTime) * 12000.0} ({@code EntityPatcher.java:156}), and a day is a fraction
	 * there where it is ticks here, so the expression below divides the day back out before taking
	 * it up.
	 */
	private static final String SKY_TIME_UNITS = "12000.0";

	/**
	 * The sky the engine has decided is the current one, and the one it recently faded from.
	 * <p>
	 * Both stand at nought, which is WynnIris's own value for having decided on neither, because the
	 * CPU half that decides in WynnIris serves a sky that engine paints a second time. This engine
	 * paints only one, so the noughts cost nothing here; {@link WynncraftSkybox} carries the whole
	 * argument.
	 */
	static final String SKY_PRIMARY = "wynnSkyPrimaryId";

	/** @see #SKY_PRIMARY */
	static final String SKY_RECENT = "wynnSkyRecentId";

	/**
	 * The uniform that lifts an entity out of a scene one of the dark skies has darkened.
	 * <p>
	 * <strong>A uniform of this engine's own rather than the pack's, and the same shape as
	 * {@link #DAY_CLOCK}.</strong> The number moves with the sky and with how far into it the
	 * picture has come, so it cannot be a constant woven into the text, and it is not a name any
	 * pack has written, so it is declared on every program the effects reach rather than looked for
	 * in the ones that happen to have it. {@link WynncraftUniforms} is what fills it and
	 * {@link WynncraftSky#entityBoost} is the rule it is filled from.
	 * <p>
	 * <strong>One of the three things that rule answers is this program's own phase, and that is the
	 * one place this port departs from WynnIris.</strong> WynnIris withholds its light tweaks from a
	 * hand program at translation time ({@code EntityPatcher.java:1446}), which is a decision about
	 * the text of a translation and would have to be carried in the key that text is cached under.
	 * Here the hand is answered with a one as the value is written, which is one multiply by one and
	 * no key at all. {@code WynncraftSky#entityBoost} carries the whole argument.
	 */
	public static final String ENTITY_BOOST = "wynnEntityBoost";

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
	 * Whether this stage decodes the player emotes, which is the vertex stage alone.
	 * <p>
	 * <strong>The same gate as {@link #neutralises} and not the decode's own, because the emote is
	 * a change to what the pack's own reads see rather than a signal spent on a branch.</strong>
	 * The decode rewrites the position and the coordinate the pack draws with, which is the
	 * neutralisation's trick pointed the other way: that hides a value the mesh carries, this
	 * replaces one. A run with the patch off is therefore left with the mesh as stored, which is
	 * the state a pack without the patch would draw and the state the switch exists to restore.
	 * <p>
	 * The fragment stage asks the same question and is answered no by the stage test, which is
	 * what keeps the two halves told apart: the decode writes globals the fragment cannot name,
	 * and the fragment's half of the emote is the fade it was handed, spent in
	 * {@link #epilogue}.
	 */
	public static boolean emotes(ProgramStage stage, VertexInputs inputs) {
		return neutralises(stage, inputs);
	}

	/**
	 * The lines that point the pack's own position and coordinate reads at the decoded values, for
	 * the vertex header.
	 * <p>
	 * <strong>Two redefinitions of the names the head defines, and nothing else.</strong> The
	 * emote decode writes a pair of globals before the pack's own main is called, and these make
	 * the pack read that pair instead of the elements it was stored as. WynnIris reaches the same
	 * end by rewriting every read of {@code vaPosition} and {@code vaUV0} to its own pair
	 * ({@code VanillaCoreTransformer.java:445-514}), and the macro is this engine's cheaper
	 * spelling of it: the name is still a macro at the point the header is written, so one
	 * {@code #undef} and one {@code #define} replace a walk over every read in the body.
	 * <p>
	 * Written below the head that defines the two names and above the body that reads them, which
	 * is the one place the pair can stand and the same place the colour's neutralisation stands.
	 * The elements are spelled out rather than the macros for the reason that one gives: a macro
	 * cannot be expanded in the middle of its own redefinition.
	 * <p>
	 * The fade is not redirected because it is not a name the pack reads: it is a value the
	 * fragment spends, and it travels on the varying the decode was made to fill.
	 */
	public static List<String> emoteRedirect(ProgramStage stage, VertexInputs inputs) {
		if (!emotes(stage, inputs)) {
			return List.of();
		}

		return List.of(
				"// WynnVitrail: the pack's own geometry and texture reads see the decoded emote.",
				"#undef of_Vertex",
				"#define of_Vertex vec4(" + WynncraftEmote.POSITION_NAME + ", 1.0)",
				"#undef of_MultiTexCoord0",
				"#define of_MultiTexCoord0 vec4(" + WynncraftEmote.UV_NAME + ", 0.0, 1.0)");
	}

	/**
	 * The decode itself, for the vertex wrapper, ahead of the pack's own body.
	 * <p>
	 * <strong>Copies, and the pack's body reads the copies through the redirection above.</strong>
	 * The decode takes its arguments by reference, so what it is handed it rewrites: the position
	 * comes back as the limb's real Y and the coordinate as the limb's own region of the skin.
	 * WynnIris runs the same call on the same pair of globals at the head of its wrapped main
	 * ({@code EntityPatcher.java:1256-1261}), and the fade is a local here rather than the global
	 * it keeps because nothing but the varying assignment reads it.
	 * <p>
	 * The decode is a branch that returns on the first line for every ordinary vertex, so an
	 * ordinary mob pays one comparison; the copy of the elements into the globals is two
	 * assignments, which the wrapper was making anyway in some form.
	 *
	 * @return the statements, which run before everything else the wrapper does
	 */
	public static String emoteDecode() {
		return WynncraftEmote.POSITION_NAME + " = " + EntityVertex.POSITION + "; "
				+ WynncraftEmote.UV_NAME + " = " + EntityVertex.TEX_COORD + "; "
				+ "float " + WynncraftEmote.FADE_NAME + " = 1.0; "
				+ WynncraftEmote.APPLY_NAME + "(" + WynncraftEmote.POSITION_NAME + ", "
				+ WynncraftEmote.UV_NAME + ", " + WynncraftEmote.FADE_NAME + "); ";
	}

	/**
	 * The name the decode leaves the coordinate in, for the engine side that carries it.
	 * <p>
	 * The carried coordinate is the remapped one rather than the element's own, which is
	 * WynnIris's choice as well ({@code iris_wynncraft_texcoord}, written from the decoded pair at
	 * {@code EntityPatcher.java:1262}): a pack that samples the skin reads the limb's own texels
	 * through the redirection, and the effects have to sample the same ones or a glint would draw
	 * the head's region over an arm.
	 */
	public static String emoteUv() {
		return WynncraftEmote.UV_NAME;
	}

	/** The name the decode answers the fade in, for the engine side that carries it. */
	public static String emoteFade() {
		return WynncraftEmote.FADE_NAME;
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
	 * <strong>Three effect libraries rather than one, because the three signal families are read
	 * at different moments and one of them is built on another.</strong> The glint and the
	 * translucency level come off the carried colour, and {@link WynncraftGlint} is the nineteen
	 * effects they select; the skies are marked in a texel too but are their own dispatch and are
	 * built on the glint library's noise, so {@link WynncraftSkybox}'s are written after them; the
	 * unlit and self-lit markers come off a texel as well, and {@link WynncraftShading} is the two
	 * corrections those call for. All three are written on the one condition, that the program is
	 * given an application, and all three stand above the wrapper that calls them.
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

		// The vertex stage alone, because the emote is a decode of the mesh rather than an effect
		// on a colour: its library is the one thing in the patch that names the vertex index,
		// which no fragment stage has, and its output is a pair of globals and a fade that only
		// the wrapper and the varying between the two stages read. See WynncraftEmote.
		if (emotes(stage, inputs)) {
			lines.add("// WynnVitrail: the player emote decode. See WynncraftEmote.");
			for (String helper : WynncraftEmote.HELPERS) {
				lines.addAll(helper.lines().toList());
			}
		}

		if (applies(stage, inputs)) {
			lines.add("// WynnVitrail: the Wynncraft glint effects. See WynncraftGlint.");
			for (String helper : WynncraftGlint.HELPERS) {
				lines.addAll(helper.lines().toList());
			}
			// After the glint's and not merely beside them: the skies are built on the lattice the
			// glint library declares, so a header holding one and not the other names a callee
			// nothing wrote. See WynncraftSkybox.
			lines.add("// WynnVitrail: the Wynncraft skies. See WynncraftSkybox.");
			for (String helper : WynncraftSkybox.HELPERS) {
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
	 * <strong>Seven steps, and the order among them is a result rather than a preference.</strong> It
	 * is WynnIris's own order ({@code EntityPatcher.java:1479-1513}), and every adjacency has a
	 * reason:
	 * <ol>
	 * <li><strong>A limb faded to nothing is thrown away before any of them</strong>
	 * ({@code EntityPatcher.java:1408}), because everything below it would draw a picture the
	 * discard then takes off the screen.</li>
	 * <li><strong>The sky first</strong>, because it replaces the colour rather than adjusting it,
	 * and everything after it is guarded on the flag it sets. {@link #skybox} carries the whole of
	 * that argument.</li>
	 * <li><strong>The unlit correction next</strong>, before anything else rewrites the colour. It
	 * reads the shading out of the colour by division, so it has to see the colour the pack
	 * produced: run after a glint it would be dividing a pixel the effect had rebuilt out of the
	 * texture, and dividing an effect's own output by a light is not what "painted unlit" meant.</li>
	 * <li><strong>The glint third and the reduction fourth.</strong> Seven of the effects rebuild
	 * the whole pixel, alpha included, out of the texture they read - a shine and a tint both end at
	 * the texture's own alpha - so a reduction applied first would be undone by any of them, where a
	 * clamp applied second holds whatever the effect left and brings it down only if it is above the
	 * target.</li>
	 * <li><strong>The item tint fifth.</strong> A dyed item the game is drawing has its tint in the
	 * pair the pack reads as the vertex colour and the colour modulator, and a pack that lights its
	 * own output without one of the two loses the tint where the vanilla shader would have kept it;
	 * the mix is luma preserving, so a pack that applied the tint itself is re-normalised rather
	 * than tinted twice.</li>
	 * <li><strong>The fade sixth.</strong> A translucent limb is faded AND reduced, so the two
	 * multiply rather than one standing in for the other, and it is guarded on the sky because a
	 * sky is a colour the drawing chose and not a limb at a distance.</li>
	 * <li><strong>The light tweaks last.</strong> Two arms of one block: the self-lit mark's lift,
	 * which reads the decoded number and the texture and has both settled by now, and the
	 * compensation for a sky that darkened the scene, which is the arm every unmarked fragment
	 * takes. It is the one step of the seven that answers to a second switch as well as to the sky:
	 * the mesh's own flags can ask for the light tweaks to stand, and a mesh that asked has been lit
	 * by the server already.</li>
	 * </ol>
	 * <p>
	 * <strong>Four of the seven carry a guard on the sky and three do not, which is WynnIris's own
	 * split rather than an oversight.</strong> The unlit correction, the item tint, the fade and
	 * the lift are guarded because each would otherwise act on a colour the drawing chose and did
	 * not produce. The glint and the reduction are not, and do not need to be: both read the
	 * carried colour, and a skybox mesh carries neither an effect number nor a translucency level,
	 * so each is already a branch that is not taken. Guarding them would be the same picture with
	 * two comparisons more. The third that is not guarded is the sky itself, which stands first and
	 * has nothing above it to be guarded against.
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
	 * six steps want one of them and the self-lit lift wants both, and a decode repeated is a decode
	 * that can drift. The effect's number is only read where there is a sampler to draw with, so a
	 * program that has none is left with no local it does not use.
	 *
	 * @param output  the name the pack's first colour output ended up with, which is its own where it
	 *                declared one and this engine's {@code ofFragData0} where it did not
	 * @param second  the name the pack's second colour output ended up with, or {@code null} where
	 *                it declared none, which withholds the one step that writes one
	 * @param vlAlbedo whether the pack's own body names {@code vlAlbedo}, which with the second
	 *                output is WynnIris's own gate for the suppression and is BSL's spelling of the
	 *                albedo its later stages light entities out of
	 * @param sampler the name this program's diffuse atlas is declared under, or {@code null} where
	 *                it declares none, which withholds everything that samples and keeps the
	 *                reduction
	 * @return the statements, or empty where this stage gets no application
	 */
	public static String epilogue(ProgramStage stage, VertexInputs inputs, String output,
			String second, boolean vlAlbedo, String sampler) {
		if (!applies(stage, inputs)) {
			return "";
		}

		String colour = GlslTranslator.ENTITY_VERTEX_COLOR;
		String fade = GlslTranslator.ENTITY_VERTEX_NEAR_FADE;

		StringBuilder statements = new StringBuilder("{ ");

		// A limb faded to nothing is thrown away before anything else runs, which is WynnIris's own
		// place for it ({@code EntityPatcher.java:1408}, the first statement of its fragment) and
		// the one place the ORDER is visible: everything below would draw a picture the discard
		// then takes off the screen, so it is said once here rather than paid for below.
		statements.append("if (").append(fade).append(" <= 0.01) { discard; } ");

		// Declared here rather than inside the skybox step, because the fade's multiplication below
		// guards on it wherever the sky ran and wherever it did not: a program with no diffuse
		// sampler is refused the sky and still owes the fade, and a second declaration inside the
		// branch would be one the block already holds.
		statements.append("bool wynnSkyApplied = false; ");
		if (sampler != null) {
			statements.append(skybox(sampler, output));
		}

		// The two decoded numbers, read whatever became of the sampler: the item tint below asks
		// whether the number is nought and never samples a texel, so a program that draws no
		// texture is still told which limb of the question it is on.
		statements.append("int wynnEffect = ").append(WynncraftSignals.GLINT_NAME).append("(")
				.append(colour).append("); ");
		statements.append("int wynnLevel = ").append(WynncraftSignals.TRANSLUCENCY_NAME).append("(")
				.append(colour).append("); ");

		// The flags WynnIris reads out of the block entity's own identifier
		// ({@code EntityPatcher.java:1409-1412}): one or three skip the item tint, and two and above
		// ask for the entity light tweaks to stand - which is one tweak here, the self-lit lift, and
		// it is withheld on the same flag WynnIris withholds its own pair on. The identifier is
		// unsigned on this mesh and nought six five five three five where WynnIris reads minus one,
		// and minus one divided by the radix is nought where six five five three five divided by it
		// is three - so the one spelling that keeps the two engines answering alike on a draw
		// nothing mapped is the one that puts the minus one back before it divides.
		statements.append("int wynnInfoFlags = (blockEntityId == 65535 ? -1 : blockEntityId) / 16384; ");
		statements.append("bool wynnSkipTint = wynnInfoFlags == 1 || wynnInfoFlags == 3; ");
		statements.append("bool wynnSkipLights = wynnInfoFlags >= 2; ");

		// The tint the game asked for, out of the pair a vanilla shader multiplies together and a
		// pack multiplies only the first of: the vertex colour this engine carries raw, and the
		// modulator the game's own transforms block holds. How far it is from white, worked out
		// once here because two of the steps below ask and a formula repeated is one that can
		// drift between its askers.
		statements.append("vec3 wynnTintColor = clamp(").append(colour).append(".rgb * ")
				.append(LegacyGlsl.GAME_COLOR_MODULATOR).append(".rgb, vec3(0.0), vec3(1.0)); ");
		statements.append("vec3 wynnTintDelta = abs(wynnTintColor - vec3(1.0)); ");
		statements.append("float wynnTintStrength = clamp(max(max(wynnTintDelta.r, wynnTintDelta.g),")
				.append(" wynnTintDelta.b) * 4.0, 0.0, 1.0); ");

		if (sampler != null) {
			// Guarded, because a sky is a colour the drawing chose rather than a shading of one, and
			// dividing a light back out of it would take the sky's own brightness off. WynnIris
			// guards it and the self-lit lift the same way and for the same reason
			// ({@code EntityPatcher.java:1001}, {@code :1497}).
			//
			// The braces are the helpers' own: both of {@link WynncraftShading}'s write a block, so
			// the guard is a statement with one body rather than a second pair around the first.
			statements.append("if (!wynnSkyApplied) ")
					.append(WynncraftShading.shadeless(output, sampler));
			statements.append(glint(sampler, output));
		}

		statements.append("if (wynnLevel > 0) { ").append(output).append(".a = min(").append(output)
				.append(".a, ").append(WynncraftSignals.ALPHA_NAME).append("(wynnLevel)); } ");

		statements.append(itemTint(output));

		// The fade, spent after the reduction and before the lift, which is WynnIris's own order
		// ({@code EntityPatcher.java:1494}): a translucent limb is faded AND reduced, so the two
		// multiply rather than one standing in for the other, and the lift comes last because it
		// reads the texture and the number both. Guarded on the sky for the reason the two above
		// it are ({@code WynncraftShading}'s own argument): a sky is a colour the drawing chose,
		// and fading one by the distance of a box it was never an emote of is a change to it.
		statements.append("if (!wynnSkyApplied) { ").append(output).append(" *= ").append(fade)
				.append("; } ");

		// The light tweaks last of everything that touches the colour, and they are the one step
		// asked of every program rather than only of one that declared a texture: the two marks are
		// a property of a sprite and go with the sprite when there is none, and the compensation for
		// the sky's own darkening is a property of the scene and is withheld from nothing.
		// WynncraftShading.skyCompensation carries why the split falls there and not around the
		// whole block.
		//
		// Two guards and not one, which is the pair WynnIris closes its whole light-tweak block
		// with ({@code EntityPatcher.java:1497}). The first is the one the unlit correction above
		// carries: a sky is a colour the drawing chose, and lifting it to the art's own colour - or
		// to half again its own - is a change to a colour no pack produced. The second is the mesh's
		// own flags, and what it is for is an art the server has already lit itself: a lift there
		// would be the second helping of a brightness that was painted on.
		statements.append("if (!wynnSkyApplied && !wynnSkipLights) ")
				.append(sampler != null
						? WynncraftShading.lightTweaks(output, sampler, "wynnEffect", ENTITY_BOOST)
						: WynncraftShading.skyCompensation(output, ENTITY_BOOST));

		if (second != null && vlAlbedo) {
			statements.append(suppressVlAlbedo(second));
		}

		statements.append("} ");

		return statements.toString();
	}

	/**
	 * The tint the game asked of an item it is drawing, put back over the colour the pack lit.
	 * <p>
	 * <strong>What it is for is a tint a pack drops rather than one it applies.</strong> The vanilla
	 * entity shader multiplies two things a pack may multiply one of: the vertex colour and the
	 * colour modulator, and the game delivers a dyed item's tint through either. A pack that lights
	 * its own output and multiplies only the vertex colour loses the tint wherever the game chose
	 * the modulator for it, and this puts the tint back - not as the raw colour but as the HUE of
	 * the tint at the LUMA the pack's lit output already has, mixed in by how far the tint is from
	 * white. That shape is what makes it safe on a pack that applied the tint itself: the result it
	 * moves toward is the tint the pack already drew, and the mix is toward the same picture.
	 * WynnIris's own words for the same code are at {@code EntityPatcher.java:919-935}.
	 * <p>
	 * <strong>Four things hold it back, and three of them are the same branch not being taken.</strong>
	 * A sky is a colour the drawing chose; the flags say the mapping asked for the tint to stand;
	 * a signal - an effect number or a translucency level - means the mesh is a Wynncraft effect
	 * rather than an item with a tint on it; and {@code currentRenderedItemId} nought means the
	 * draw is not an item the game told the id of at all, which is the gate that keeps a mob or a
	 * block out of a question about items.
	 *
	 * @param output the name the pack's first colour output ended up with
	 * @return the statements, which run after the reduction and before the fade
	 */
	private static String itemTint(String output) {
		StringBuilder code = new StringBuilder();
		code.append("if (!wynnSkyApplied && !wynnSkipTint && wynnEffect == 0 && wynnLevel == 0")
				.append(" && currentRenderedItemId > 0) { ");
		code.append("if (wynnTintStrength > 0.001) { ");
		code.append("float wynnTintLuma = dot(wynnTintColor, vec3(0.2126, 0.7152, 0.0722)); ");
		code.append("float wynnOutLuma = dot(max(").append(output).append(".rgb, vec3(0.0)),")
				.append(" vec3(0.2126, 0.7152, 0.0722)); ");
		code.append("if (wynnOutLuma > 0.001) { ");
		code.append("vec3 wynnTintHue = wynnTintLuma > 0.001 ? wynnTintColor / wynnTintLuma")
				.append(" : vec3(0.0); ");
		code.append("vec3 wynnPreservedTint = clamp(wynnTintHue * wynnOutLuma, vec3(0.0),")
				.append(" vec3(1.0)); ");
		code.append(output).append(".rgb = mix(").append(output).append(".rgb, wynnPreservedTint,")
				.append(" wynnTintStrength); } } } ");

		return code.toString();
	}

	/**
	 * The second colour output zeroed, for the one pack that lights entities out of it.
	 * <p>
	 * <strong>BSL's entity stage is where this lives, and the condition is the pack's own
	 * spelling.</strong> A BSL entity program that names {@code vlAlbedo} and writes a second
	 * output is one whose later stages blend a "vanilla light" contribution over entities using
	 * what that output holds, and a tint - the shader's own, ids fifteen to twenty-four, or the
	 * item tint above - is a colour that contribution would double-correct. WynnIris zeroes the
	 * output's colour for exactly those fragments ({@code EntityPatcher.java:936-948}), gated the
	 * same two ways: the pack must name the identifier, and it must write the second output at
	 * all.
	 * <p>
	 * <strong>Inert on a pack configured as BSL ships, and that is worth saying rather than
	 * hiding.</strong> The second output of BSL's entity stage exists only under its selective
	 * TAA or its advanced materials, and under the first of those it holds a mask whose colour is
	 * nought already; under the second it holds smoothness and sky occlusion, which is what the
	 * zero takes, exactly as it does under WynnIris. A pack that names neither the identifier nor
	 * the output is not asked the question at all.
	 *
	 * @param second the name the pack's second colour output ended up with
	 * @return the statements, which run last of everything the application does
	 */
	private static String suppressVlAlbedo(String second) {
		StringBuilder code = new StringBuilder();
		code.append("if (!wynnSkyApplied) { ");
		code.append("bool wynnShaderTint = wynnEffect >= 15 && wynnEffect <= 24; ");
		code.append("bool wynnItemTint = !wynnSkipTint && wynnEffect == 0 && wynnLevel == 0")
				.append(" && currentRenderedItemId > 0 && wynnTintStrength > 0.001; ");
		code.append("if (wynnShaderTint || wynnItemTint) { ").append(second)
				.append(".rgb = vec3(0.0); } } ");

		return code.toString();
	}

	/**
	 * The sky a fragment belongs to, drawn in place of the box Wynncraft put it on.
	 * <p>
	 * <strong>First of everything, because a sky is not a correction to the pack's colour but a
	 * replacement of it.</strong> Wynncraft has no sky the game knows about: it wraps the player in
	 * a very large box, paints the faces with the drawing a sky is made of, and marks them, and a
	 * pack reads that box as geometry and shades it - a lit, fogged, shadowed cube where the sky
	 * should be. Everything below this point adjusts the colour a pack produced, and there is no
	 * sense in adjusting a colour that is about to be thrown away, so the drawing runs first and
	 * everything that reads the colour is guarded on the flag it sets.
	 * <p>
	 * <strong>The flag is what makes the guard possible, and it is not the same thing as the
	 * identity being nought.</strong> The two would agree here, since a sky of identity nought does
	 * not exist and the flag is set exactly where the identity is not nought - but the reduction
	 * below is deliberately unguarded, because it reads the carried colour and a skybox mesh
	 * carries no translucency level, so it is already a nought branch. That is the shape WynnIris
	 * has as well and the reason its comment gives: the glint and the reduction skip of their own
	 * accord, and the two steps that would not have been given the flag.
	 * <p>
	 * <strong>Two of the four things the drawing is told are constants, and they are the noughts
	 * that leave its one branch never taken.</strong> WynnIris compares the identity against
	 * {@code iris_wynncraftPrimarySkyboxId} and {@code iris_wynncraftRecentSkyboxId} and discards on
	 * a match, which is how a dome is kept out of the way of a sky the pipeline is painting over the
	 * same pixels - in world space, across the whole screen, over terrain as well. This engine paints
	 * no such second sky: the fragment this runs in is the only one there is, so a discard here
	 * would not suppress a duplicate, it would take the sky away and leave the clear colour. Both
	 * stand at WynnIris's own "nothing decided" value, matching no identity a signal carries, so the
	 * branch is never taken and the box is replaced rather than hidden. {@link WynncraftSkybox}
	 * carries the CPU half and the whole of the argument.
	 * <p>
	 * <strong>The premultiplied form of the same drawing is left out, and it is the engine's
	 * business rather than this patch's.</strong> WynnIris has two spellings of the replacement and
	 * picks between them on whether the pack's output looks premultiplied, which it decides by
	 * reading the pack's own source: an output it found by scanning a {@code layout} declaration is
	 * assumed to be blended one-to-one
	 * ({@code EntityPatcher.java:2079-2088}, {@code :2146}). This engine does not infer that from a
	 * program, and does not need to: the composition is the pipeline's state and is known where the
	 * draw is recorded rather than where the text is translated. A sky here is therefore written in
	 * the straight form, and a pack that premultiplies its translation units would carry a sky that
	 * is brighter than it should be by its own alpha.
	 *
	 * @param sampler the name this program's diffuse atlas is declared under
	 * @param output  the name the pack's first colour output ended up with
	 * @return the statements, which run before every other step of the application
	 */
	private static String skybox(String sampler, String output) {
		StringBuilder code = new StringBuilder();

		// The two ids, at WynnIris's own value for having decided on neither. See the method
		// comment: this engine has no second sky for them to keep out of the way.
		code.append("const int ").append(SKY_PRIMARY).append(" = 0; const int ")
				.append(SKY_RECENT).append(" = 0; ");
		code.append("int wynnSkyId = ").append(WynncraftSkybox.SIGNAL_NAME).append("(")
				.append(sampler).append(", ").append(GlslTranslator.ENTITY_VERTEX_UV).append("); ");
		code.append("if (wynnSkyId > 0) { ");
		code.append("if (wynnSkyId == ").append(SKY_PRIMARY).append(" || wynnSkyId == ")
				.append(SKY_RECENT).append(") { discard; } ");
		code.append("float wynnSkyTime = fract(").append(DAY_CLOCK).append(" / ")
				.append(DAY_TICKS).append(") * ").append(SKY_TIME_UNITS).append("; ");
		code.append(output).append(" = ").append(WynncraftSkybox.APPLY_NAME)
				.append("(wynnSkyId, wynnSkyTime, normalize(")
				.append(GlslTranslator.ENTITY_VERTEX_POSITION).append(")); ");
		code.append("wynnSkyApplied = true; } ");

		return code.toString();
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
		code.append("float wynnTime = ").append(DAY_CLOCK).append(" * ")
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
