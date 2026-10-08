package dev.wynnvitrail;

import dev.vitrail.glsl.LegacyGlsl;

/**
 * The player emote decode, as GLSL: Wynncraft's dances and waves and snow angels, read back out
 * of the mesh the game was handed.
 * <p>
 * <strong>A Wynncraft emote is not an animation the game plays but a player the server draws.</strong>
 * When a player nearby uses an emote, the server wraps the real player in a model built out of
 * the player skin's own six limbs, and it has two things to tell the shader that no channel of
 * the game's own data can carry: which limb a vertex belongs to, and how the limb is to fade with
 * distance. It hides both in the vertex's own Y position, stacked above two thousand and
 * forty-eight: below that is an ordinary vertex and above it the position is a NUMBER, the real Y
 * plus two hundred and fifty-five plus the limb's index and model and fade mode each shifted into
 * its own radix. The resource pack's own shader decodes the number, and without a decode the
 * model renders as it was stored - displaced a thousand units up, with the head's texture mapped
 * onto every limb - which is the "emotes go invisible" the pack's users see on a shader pack
 * ({@code pipeline/transform/transformer/EntityPatcher.java:1023-1028} carrying the whole of it).
 * <p>
 * <strong>The decode moves the geometry, and that is what makes it a vertex-half and not a
 * fragment-half.</strong> The function takes the position and the coordinate by reference and
 * rewrites both: the position comes back as the Y the limb really stands at, and the coordinate
 * comes back mapped out of the head's region of the skin and into the limb's own. The pack's own
 * reads of both are redirected onto the decoded values
 * ({@link WynncraftPatch#emoteRedirect}), so what the pack draws with is the player the server
 * meant and not the number it travelled as. WynnIris reaches the same end by rewriting every read
 * of {@code vaPosition} and {@code vaUV0} to its decoded pair
 * ({@code VanillaCoreTransformer.java:445-514}); here the names are macros of the head and the
 * redirection is one {@code #undef} each, which is the same trick the signal neutralisation
 * already uses.
 * <p>
 * <strong>The third thing the decode answers is the fade, and it is read rather than stored.</strong>
 * A limb's fade mode says whether it softens with distance, vanishes past a distance, or does
 * neither, and the distance itself is measured from the decoded position through the draw's own
 * model view - which is why the model view is named in the identity check below. The head and
 * the body never fade, whatever their mode says: a dance with a torso that blinked out at range
 * would be a head floating over feet, and the pack's own author made the same exception
 * (the {@code headBody} line of {@code applyPlayer}, {@code EntityPatcher.java:1193-1195}).
 * <p>
 * <strong>Two things stop the decode before it starts, and both are the game's own numbers.</strong>
 * A Y below two thousand and forty-eight is an ordinary vertex, and a draw whose model view is
 * the identity is one the decode has no business in: WynnIris guards on both
 * ({@code EntityPatcher.java:1151-1153}) and the second keeps the decode out of the interface
 * and the paper doll, where the model view is the identity because the whole transform of the
 * draw was put in the pose. {@link LegacyGlsl#GAME_MODEL_VIEW} is this engine's name for the
 * matrix the game prepared the draw with, which is the same matrix WynnIris reads as
 * {@code iris_transforms.ModelViewMat}, and {@code WynncraftPatch} takes the block it lives in
 * for exactly this read ({@code GlslTranslator.planWynncraft}).
 * <p>
 * <strong>The vertex index is the one thing the decode reads that is not an argument.</strong>
 * The limb's six faces are twenty-four vertices and the overlay layer follows the base one, so
 * the face a vertex belongs to and the layer it is in come off the index modulo twenty-four
 * and twenty-four again - which is why this is a vertex-half in a second sense, the index being
 * a name only that stage has. The engine's compiler maps {@code gl_VertexID} onto the Vulkan
 * name at compile ({@code mixin/GlslCompilerMixin.java:82-85}), so the spelling here is the
 * OpenGL one the pack's own body would use, and a draw that reaches here through a base vertex
 * would read the index off by that base - which no draw of the game's does.
 * <p>
 * The names are {@code wynn} spelled as everywhere else in the patch, for the reason
 * {@link WynncraftSignals} gives: they are injected into a pack's own file, where a pack is free
 * to have used any name it likes, so they have to be ones no pack would have written.
 */
final class WynncraftEmote {

	/** The decode itself, named here so the wrapper cannot misspell it. */
	static final String APPLY_NAME = "wynnApplyPlayer";

	/**
	 * The globals the decode writes and the pack's own reads are redirected onto.
	 * <p>
	 * Globals and not locals of the wrapper, because the redirection is a macro and a macro
	 * expands where the pack's body reads it, which is a different function from the one the
	 * decode ran in. WynnIris declares the same pair as globals for the same reason
	 * ({@code EntityPatcher.java:1234-1235}). They are written before the pack's own main is
	 * called and read nowhere else, so there is no moment at which either holds a value from the
	 * vertex before it.
	 */
	static final String POSITION_NAME = "wynnEmotePos";

	/** @see #POSITION_NAME */
	static final String UV_NAME = "wynnEmoteUv";

	/** The fade the decode answers, held beside the two it rewrites. */
	static final String FADE_NAME = "wynnEmoteFade";

	/**
	 * The limbs of the player skin, the numbers the decode works with, and the decode itself.
	 * <p>
	 * The table is Wynncraft's own, transcribed from the resource pack's {@code player.glsl} as
	 * WynnIris transcribed it ({@code EntityPatcher.java:1029-1106}): six limbs in the game's own
	 * order - head, body, left arm, right arm, left leg, right leg - and then the Alex model's two
	 * arms behind the Steve ones, reached by the offsets the model bit picks between. Each limb
	 * is six faces of a size and an origin, and the overlay layer's own offset, all in the skin's
	 * sixty-four by sixty-four texels.
	 */
	private static final String DATA = """
			struct wynnLimbUv {
			    vec2 faceSizes[6];
			    vec2 faceOrigins[6];
			    vec2 overlayOffset;
			};

			const int WYNN_EMOTE_Y_RADIX = 512;
			const int WYNN_EMOTE_STEVE_ALEX_RADIX = 2;
			const int WYNN_EMOTE_LIMB_FADE_RADIX = 3;
			const int WYNN_EMOTE_LIMB_INDEX_RADIX = 6;
			const float WYNN_EMOTE_SKIN_SIZE = 64.0;
			const float WYNN_EMOTE_SKIN_SIZE_INV = 1.0 / 64.0;
			const float WYNN_EMOTE_SOFT_FADE_START_SQ = 0.5;
			const float WYNN_EMOTE_SOFT_FADE_END_SQ = 1.0;
			const float WYNN_EMOTE_HARD_FADE_SQ = 6.0;

			const wynnLimbUv WYNN_EMOTE_LIMB_UVS[8] = wynnLimbUv[](
			    wynnLimbUv(
			        vec2[](vec2(8.0, 8.0), vec2(8.0, 8.0), vec2(8.0, 8.0), vec2(8.0, 8.0), vec2(8.0, 8.0), vec2(8.0, 8.0)),
			        vec2[](vec2(16.0, 0.0), vec2(24.0, 8.0), vec2(8.0, 8.0), vec2(16.0, 8.0), vec2(24.0, 8.0), vec2(32.0, 8.0)),
			        vec2(32.0, 0.0)),
			    wynnLimbUv(
			        vec2[](vec2(8.0, 4.0), vec2(8.0, 4.0), vec2(4.0, 12.0), vec2(8.0, 12.0), vec2(4.0, 12.0), vec2(8.0, 12.0)),
			        vec2[](vec2(28.0, 16.0), vec2(36.0, 20.0), vec2(20.0, 20.0), vec2(28.0, 20.0), vec2(32.0, 20.0), vec2(40.0, 20.0)),
			        vec2(0.0, 16.0)),
			    wynnLimbUv(
			        vec2[](vec2(4.0, 4.0), vec2(4.0, 4.0), vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0)),
			        vec2[](vec2(40.0, 48.0), vec2(44.0, 52.0), vec2(36.0, 52.0), vec2(40.0, 52.0), vec2(44.0, 52.0), vec2(48.0, 52.0)),
			        vec2(16.0, 0.0)),
			    wynnLimbUv(
			        vec2[](vec2(4.0, 4.0), vec2(4.0, 4.0), vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0)),
			        vec2[](vec2(48.0, 16.0), vec2(52.0, 20.0), vec2(44.0, 20.0), vec2(48.0, 20.0), vec2(52.0, 20.0), vec2(56.0, 20.0)),
			        vec2(0.0, 16.0)),
			    wynnLimbUv(
			        vec2[](vec2(4.0, 4.0), vec2(4.0, 4.0), vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0)),
			        vec2[](vec2(24.0, 48.0), vec2(28.0, 52.0), vec2(20.0, 52.0), vec2(24.0, 52.0), vec2(28.0, 52.0), vec2(32.0, 52.0)),
			        vec2(-16.0, 0.0)),
			    wynnLimbUv(
			        vec2[](vec2(4.0, 4.0), vec2(4.0, 4.0), vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0), vec2(4.0, 12.0)),
			        vec2[](vec2(8.0, 16.0), vec2(12.0, 20.0), vec2(4.0, 20.0), vec2(8.0, 20.0), vec2(12.0, 20.0), vec2(16.0, 20.0)),
			        vec2(0.0, 16.0)),
			    wynnLimbUv(
			        vec2[](vec2(3.0, 4.0), vec2(3.0, 4.0), vec2(4.0, 12.0), vec2(3.0, 12.0), vec2(4.0, 12.0), vec2(3.0, 12.0)),
			        vec2[](vec2(39.0, 48.0), vec2(42.0, 52.0), vec2(36.0, 52.0), vec2(39.0, 52.0), vec2(43.0, 52.0), vec2(46.0, 52.0)),
			        vec2(16.0, 0.0)),
			    wynnLimbUv(
			        vec2[](vec2(3.0, 4.0), vec2(3.0, 4.0), vec2(4.0, 12.0), vec2(3.0, 12.0), vec2(4.0, 12.0), vec2(3.0, 12.0)),
			        vec2[](vec2(47.0, 16.0), vec2(50.0, 20.0), vec2(44.0, 20.0), vec2(47.0, 20.0), vec2(51.0, 20.0), vec2(54.0, 20.0)),
			        vec2(0.0, 16.0))
			);

			const int WYNN_EMOTE_STEVE_OFFSETS[6] = int[](0, 0, 0, 0, 0, 0);
			const int WYNN_EMOTE_ALEX_OFFSETS[6] = int[](0, 0, 4, 4, 0, 0);
			const int WYNN_EMOTE_FACE_RIGHT = 2;
			const int WYNN_EMOTE_FACE_FRONT = 3;
			const int WYNN_EMOTE_FACE_LEFT = 4;
			const int WYNN_EMOTE_HEAD = 0;
			const int WYNN_EMOTE_BODY = 1;""";

	/**
	 * The decode, which takes the position and the coordinate by reference and the fade by
	 * value.
	 * <p>
	 * Transcribed from the pack's own {@code applyPlayer} as WynnIris carries it
	 * ({@code EntityPatcher.java:1147-1196}), with the one name that is an engine's rather than
	 * the pack's changed: {@code iris_transforms.ModelViewMat} becomes
	 * {@link LegacyGlsl#GAME_MODEL_VIEW}, which is the same matrix under this engine's own
	 * naming. The right face of a limb and its left are swapped across the divide of the head's
	 * own texture, because the head's region is mirrored where the limbs' are not and the pack's
	 * author mapped the coordinate accordingly; the arithmetic is the pack's own and is not
	 * paraphrased here.
	 */
	private static final String APPLY = ("""
			void wynnApplyPlayer(inout vec3 pos, inout vec2 uv, out float nearFade) {
			    nearFade = 1.0;
			    if (pos.y < 2.0 * float(WYNN_EMOTE_Y_RADIX)) return;
			    if (WYNN_EMOTE_MODEL_VIEW == mat4(1.0)) return;

			    int metadata = int(pos.y) - 2 * WYNN_EMOTE_Y_RADIX;

			    int steveAlex = (metadata / WYNN_EMOTE_Y_RADIX) % WYNN_EMOTE_STEVE_ALEX_RADIX;
			    int limbFade = (metadata / WYNN_EMOTE_Y_RADIX / WYNN_EMOTE_STEVE_ALEX_RADIX) % WYNN_EMOTE_LIMB_FADE_RADIX;
			    int limbIndex = (metadata / WYNN_EMOTE_Y_RADIX / WYNN_EMOTE_STEVE_ALEX_RADIX / WYNN_EMOTE_LIMB_FADE_RADIX) % WYNN_EMOTE_LIMB_INDEX_RADIX;

			    pos.y = mod(pos.y, float(WYNN_EMOTE_Y_RADIX)) - (float(WYNN_EMOTE_Y_RADIX) / 2.0 - 1.0);

			    int face = (gl_VertexID % 24) / 4;
			    int overlay = (gl_VertexID / 24) % 2;

			    int limbUvOffset = steveAlex == 0 ? WYNN_EMOTE_STEVE_OFFSETS[limbIndex] : WYNN_EMOTE_ALEX_OFFSETS[limbIndex];
			    wynnLimbUv limbUv = WYNN_EMOTE_LIMB_UVS[limbIndex + limbUvOffset];
			    wynnLimbUv headUv = WYNN_EMOTE_LIMB_UVS[0];

			    uv -= float(overlay) * headUv.overlayOffset * WYNN_EMOTE_SKIN_SIZE_INV;

			    float faceDivideX = (headUv.faceOrigins[WYNN_EMOTE_FACE_RIGHT].x + headUv.faceOrigins[WYNN_EMOTE_FACE_LEFT].x) / 2.0;
			    int divide = int(uv.x >= faceDivideX * WYNN_EMOTE_SKIN_SIZE_INV);

			    face += divide * int(face == WYNN_EMOTE_FACE_RIGHT) * (WYNN_EMOTE_FACE_LEFT - WYNN_EMOTE_FACE_RIGHT);
			    face -= (1 - divide) * int(face == WYNN_EMOTE_FACE_LEFT) * (WYNN_EMOTE_FACE_LEFT - WYNN_EMOTE_FACE_RIGHT);

			    vec2 sizeRatio = limbUv.faceSizes[face] / headUv.faceSizes[face];
			    vec2 originOffset = limbUv.faceOrigins[face] - (headUv.faceOrigins[face] * sizeRatio);

			    uv *= sizeRatio;
			    uv += originOffset * WYNN_EMOTE_SKIN_SIZE_INV;

			    uv += float(overlay) * limbUv.overlayOffset * WYNN_EMOTE_SKIN_SIZE_INV;

			    vec4 blockPos = WYNN_EMOTE_MODEL_VIEW * vec4(pos, 1.0);
			    float blockDistSq = dot(blockPos.xyz, blockPos.xyz);

			    float softFade = smoothstep(WYNN_EMOTE_SOFT_FADE_START_SQ, WYNN_EMOTE_SOFT_FADE_END_SQ, blockDistSq);
			    float hardFade = step(WYNN_EMOTE_HARD_FADE_SQ, blockDistSq);

			    nearFade = mix(1.0, mix(softFade, hardFade, limbFade == 2), limbFade != 0);

			    int headBody = int(limbIndex == WYNN_EMOTE_HEAD || limbIndex == WYNN_EMOTE_BODY);
			    nearFade = mix(1.0, nearFade, headBody);
			}""").replace("WYNN_EMOTE_MODEL_VIEW", LegacyGlsl.GAME_MODEL_VIEW);

	/** The globals the wrapper fills, the decode, and the data it reads, in that order. */
	static final String[] HELPERS = {
			"vec3 " + POSITION_NAME + ";\nvec2 " + UV_NAME + ";",
			DATA,
			APPLY};

	private WynncraftEmote() {
	}
}
