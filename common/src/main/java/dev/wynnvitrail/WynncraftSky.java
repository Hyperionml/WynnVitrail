package dev.wynnvitrail;

import dev.vitrail.Vitrail;

/**
 * Which of Wynncraft's skies the player is under, and how far into it the picture has come.
 * <p>
 * <strong>A Wynncraft sky is an object, and the object is the only place its identity is
 * written.</strong> The server has no sky the game knows about: it wraps the player in a very large
 * box, textures its faces with the animated art the drawing is made of, and marks one texel of that
 * art with three bytes at once - a green of two hundred and fifty-one, an alpha of two hundred and
 * fifty-four, and a blue of one to seven which is the identity. {@link WynncraftSkybox} reads that
 * marking in the entity program and draws the sky it means, which is the whole of what a pack with
 * no second pass can do. What a program cannot do is change the world AROUND the sky: a dome's
 * fragment knows its own direction and nothing about the terrain twenty blocks away, so the tint,
 * the darkening and the fog that WynnIris spreads over the scene have to be a pass of their own, and
 * a pass of its own needs the identity on the CPU.
 * <p>
 * <strong>So the identity is read twice, from the same three bytes, on two sides of the
 * machine.</strong> The marking is in the centre texel of the dome's own sprite, and
 * {@code ItemStackLayerMixin} reads it there while the item layer is being submitted - the one
 * moment the quad and the pose that placed it are both in hand. This class is what it reads it
 * into.
 * <p>
 * <strong>Several domes are submitted and only one of them matters.</strong> A region's skybox is
 * pinned to its own height, and the one the player is under stands about six hundred blocks below
 * the camera; the others are neighbours whose regions are still loaded, and Wynncraft's own art for
 * them is what is drawn under the terrain rather than over it. WynnIris prefers the dome whose
 * height is closest to {@code -601.6} and falls back to any dome at all when none is in the band,
 * and both halves of that are kept here: the band is wide enough for the terrain to rise and fall
 * under a region, and the fallback is what stops a sky from vanishing because the camera drifted
 * out of a band that a server update moved.
 * <p>
 * <strong>The identity then has to be settled, because a marking is read per item and an item is
 * not a decision.</strong> Three things stand between a frame's detections and the sky this class
 * reports, and each answers a failure of its own:
 * <ul>
 * <li><strong>Three frames of agreement</strong> before a new identity replaces the standing one.
 * Passing through a wrong region's dome is one or two frames of the wrong marking, which is a flash
 * of red over a green valley; the debounce is what makes a switch mean something.</li>
 * <li><strong>A fade in and a fade out</strong> rather than a step, so that the scene's tint
 * arrives and leaves instead of cutting. The way in has a time constant of half a second; the way
 * out has two, and it is two on purpose: above half strength the fade is slow, because a dome that
 * goes unsubmitted for a few frames - an entity culled at the edge of the view - must not read as a
 * departure, and below half it is quick, so that a real departure ends rather than lingers.</li>
 * <li><strong>Ten seconds of grace for the identity just left.</strong> The dome of the region
 * behind the player is still loaded, so it is still submitted and still carries its marking, and
 * without the grace the sky that was just faded out would be detected again on the very next frame
 * and fade back in over a region it does not belong to.</li>
 * </ul>
 * <p>
 * <strong>The grace is counted in frames of world time and not against a wall clock</strong>, which
 * is one place this port departs from WynnIris and does so on purpose: its {@code
 * System.currentTimeMillis()} keeps running while the game is paused, so a pause of ten seconds
 * spends the whole grace on a world that did not advance a tick, and the dome that was waiting to
 * be forgotten is forgotten. The frame's own step is what the rest of this engine measures a
 * duration in ({@code render/FrameState}), and it is the same number the two fades above integrate
 * over, so one clock answers all three questions rather than two of them answering two.
 * <p>
 * <strong>The detections consumed here are the frame before's, and that is the engine's shape
 * rather than a lag that was chosen.</strong> WynnIris consumes them at the end of the frame that
 * produced them, in {@code finalizeLevelRendering}; this engine takes its snapshot of the world at
 * the head of a frame ({@code FrameState.advance}), before the level has been drawn, so what a
 * frame settles on is what the frame before it saw. It costs one frame of delay on a fade whose
 * fastest time constant is half a second, and it buys the pass a value that cannot change between
 * the draws of one frame.
 * <p>
 * <strong>Everything here is switched with the patch</strong>, because none of it has a meaning
 * when the effects are withheld: an identity with no pass behind it is a number nothing reads, and a
 * detection still being gathered would be work done to feed it. {@link #advance} is what does the
 * switching, so a session that turns the file on gets a state machine that starts from nothing
 * rather than from whatever the last session left.
 */
public final class WynncraftSky {

	/** The number of skies Wynncraft has, which is the range of the blue a marking carries. */
	private static final int SKY_COUNT = 7;

	/** The height a primary dome stands at, in blocks below the camera, which is the one to prefer. */
	private static final float TARGET_DELTA_Y = -601.6F;

	/**
	 * The heights either side of that one a dome still counts as one the player is under.
	 * <p>
	 * WynnIris's own pair, and asymmetric around the target rather than a band: a hundred blocks
	 * below it and fifty above. The reason is the shape of the thing being measured - the dome hangs
	 * under the region it belongs to and a region's terrain rises above the height its sky is pinned
	 * at, so the camera is further from the target on the way up than on the way down.
	 */
	private static final float DELTA_Y_LOW = -700.0F;
	private static final float DELTA_Y_HIGH = -550.0F;

	/** The frames of agreement a new identity needs before it replaces the standing one. */
	private static final int SWITCH_FRAMES = 3;

	/** The world seconds the identity just left is kept out of the detection. */
	private static final float GRACE_SECONDS = 10.0F;

	/** The time constant of the way in, in seconds: WynnIris's own, and about two seconds to settle. */
	private static final float FADE_IN_TAU = 0.5F;

	/** And the two of the way out: slow above half strength, quick below it. */
	private static final float FADE_OUT_SLOW_TAU = 2.0F;
	private static final float FADE_OUT_FAST_TAU = 0.6F;
	private static final float FADE_OUT_SLOW_ABOVE = 0.5F;

	/** Below this a fade is over rather than small, and the identity goes with it. */
	private static final float FADE_DONE = 0.005F;

	/**
	 * How much brighter an entity is drawn at full strength under one of the dark skies.
	 * <p>
	 * WynnIris's own half, which its entity brightness and scene darkening sliders scale and which
	 * this fork has no sliders for: both ship at the hundred out of a hundred that leaves this
	 * number alone, so the two factors are not carried.
	 */
	private static final float BOOST_AT_FULL_STRENGTH = 0.5F;

	/** Where night vision is bright enough that WynnIris stops compensating for the dark. */
	private static final float NIGHT_VISION_SEES = 0.5F;

	/** A frame's step, banded, so that a pause or a hitch cannot jump a fade by seconds. */
	private static final float MIN_STEP = 0.001F;
	private static final float MAX_STEP = 0.25F;

	/**
	 * What the world's own draw detected, in the frame that is still being drawn.
	 * <p>
	 * The preferred pair is the dome nearest the height a primary stands at; the fallback is any
	 * dome at all, and it is the last one seen rather than the best one, because there is nothing to
	 * rank them by outside the band.
	 */
	private static volatile int preferredId;
	private static volatile float preferredDeltaY = Float.MAX_VALUE;
	private static volatile int fallbackId;

	/** The identity this engine has settled on, and how far into it the picture has come. */
	private static volatile int id;
	private static volatile float fade;

	/** The identity just left, and the world seconds of grace it has left. */
	private static volatile int recentId;
	private static volatile float grace;

	/** The identity waiting to be agreed on, and how many frames it has been waiting. */
	private static int candidateId;
	private static int candidateFrames;

	private WynncraftSky() {
	}

	/**
	 * Notes one dome the world's draw found, and the height its own pose put it at.
	 * <p>
	 * Called once per item layer that carries a marking, on the render thread, from
	 * {@code ItemStackLayerMixin}. Nothing is decided here: a frame may note several domes and the
	 * choice between them is {@link #advance}'s, which is the boundary between the part of this
	 * class that reads the world and the part that decides.
	 *
	 * @param skyId  the identity the marking carried, one to seven
	 * @param deltaY the height the layer's own pose placed it at, in blocks against the camera
	 */
	public static void note(int skyId, float deltaY) {
		if (skyId < 1 || skyId > SKY_COUNT) {
			return;
		}

		// Any dome is better than none, so every one of them is kept as the fallback and the band
		// below only decides which is preferred.
		fallbackId = skyId;

		if (deltaY < DELTA_Y_LOW || deltaY > DELTA_Y_HIGH) {
			return;
		}

		if (preferredId == 0 || Math.abs(deltaY - TARGET_DELTA_Y)
				< Math.abs(preferredDeltaY - TARGET_DELTA_Y)) {
			preferredId = skyId;
			preferredDeltaY = deltaY;
		}
	}

	/**
	 * Settles the identity for one frame: consumes what the last frame's draw detected, moves the
	 * fade by this frame's step, and says so when the answer changes.
	 * <p>
	 * Called once a frame from {@code render/FrameState}, which is the walk that already knows the
	 * frame's own step. The order of the three below is WynnIris's and is not free: the detection is
	 * resolved against the grace BEFORE the fade moves, so a frame that both leaves a sky and
	 * detects the dome it left resolves to a departure rather than to a return.
	 *
	 * @param dt how long the frame took, in seconds, as the frame state measured it
	 */
	public static void advance(float dt) {
		if (!WynncraftSettings.effects()) {
			forget();
			return;
		}

		int preferred = takePreferred();
		int fallback = takeFallback();
		float step = Math.min(MAX_STEP, Math.max(MIN_STEP, dt));
		boolean inGrace = recentId > 0 && grace > 0.0F;
		grace = Math.max(0.0F, grace - step);

		int detected;
		if (preferred > 0 && !(preferred == recentId && inGrace)) {
			detected = preferred;
		} else if (fallback > 0 && id == 0 && !(fallback == recentId && inGrace)) {
			detected = fallback;
		} else {
			detected = 0;
		}

		if (detected > 0) {
			stepTowards(detected, step);
		} else if (id > 0) {
			fadeOut(step);
		}
	}

	/**
	 * The steps a detection that arrived takes: the debounce, the switch, and the fade in.
	 * <p>
	 * A detection that disagrees with the standing identity is a candidate and nothing more until it
	 * has been seen three frames running, and the fade in advances only once the two agree - which
	 * is what keeps an uncommitted candidate from moving the picture. The first identity is taken at
	 * once and without the debounce, because there is nothing for it to be wrong about: the
	 * alternative is a sky that waits three frames to appear over an empty one.
	 */
	private static void stepTowards(int detected, float step) {
		if (detected != id) {
			if (detected == candidateId) {
				candidateFrames++;
			} else {
				candidateId = detected;
				candidateFrames = 1;
			}

			if (id != 0 && candidateFrames < SWITCH_FRAMES) {
				return;
			}

			int before = id;
			if (before > 0) {
				recentId = before;
				grace = GRACE_SECONDS;
			}

			id = detected;
			candidateId = 0;
			candidateFrames = 0;
			say(before, detected);
		} else {
			candidateId = 0;
			candidateFrames = 0;
		}

		fade += (1.0F - fade) * (1.0F - (float) Math.exp(-step / FADE_IN_TAU));
	}

	/**
	 * The steps no detection takes, which is a departure and not a gap.
	 * <p>
	 * <strong>The grace is refreshed on every frame of the way out</strong>, which is WynnIris's own
	 * shape and the thing that makes it work: the dome that was just left is still loaded and still
	 * submitted, so a detection of it is coming, and the grace has to be standing when it arrives.
	 * Refreshing it only at the start of a fade would end the grace in the middle of one and let the
	 * sky back in under a region it had already left.
	 */
	private static void fadeOut(float step) {
		float tau = fade > FADE_OUT_SLOW_ABOVE ? FADE_OUT_SLOW_TAU : FADE_OUT_FAST_TAU;
		fade *= (float) Math.exp(-step / tau);

		recentId = id;
		grace = GRACE_SECONDS;

		if (fade < FADE_DONE) {
			fade = 0.0F;
			int before = id;
			id = 0;
			say(before, 0);
		}
	}

	/**
	 * The sky the player is under, or nought where there is none.
	 * <p>
	 * Nought is a real answer and not a missing one: it is what a region with no sky, a player in a
	 * cave, and a session whose domes have all been culled have in common, and every reader of this
	 * is expected to leave the picture alone for it.
	 */
	public static int id() {
		return id;
	}

	/**
	 * How far into the sky the picture has come, nought to one.
	 * <p>
	 * Read by the pass that tints the scene and by the entity boost, and it reaches one
	 * asymptotically rather than in finite time, which is why the departure test above is a floor
	 * rather than an equality.
	 */
	public static float fade() {
		return fade;
	}

	/** The identity just left, kept out of the detection while its grace runs. */
	public static int recentId() {
		return recentId;
	}

	/**
	 * How much brighter an entity under this sky is drawn, which is one wherever it is not.
	 * <p>
	 * <strong>It is compensation and not decoration.</strong> A dark sky darkens the scene it hangs
	 * over - that is what {@link #fade} is spent on by the pass that tints it - and an entity is part
	 * of that scene: a mob in a storm over Wynncraft's War Surface is darkened along with the ground
	 * it stands on, and at night the two of them close on black. The boost lifts the entities back
	 * towards what they were, so that a fight in a storm is a fight a player can read. WynnIris
	 * computes the same number for the same reason ({@code uniforms/CommonUniforms.java:86-107}) and
	 * its shape is kept: half again at full strength, scaled by the fade so that it arrives and
	 * leaves with the sky, and scaled by two sliders this fork does not have and therefore reads at
	 * the hundred out of a hundred they ship at.
	 * <p>
	 * <strong>Three things answer one, and each is a case a reader would otherwise have to guess
	 * at.</strong>
	 * <ul>
	 * <li><strong>The sky is not one of the dark four.</strong> Two of the seven are a pale mist and
	 * one is a bright day; a scene under those is not darkened, so there is nothing to compensate
	 * for.</li>
	 * <li><strong>The player can already see in the dark.</strong> A boost on top of night vision is
	 * a scene washed out rather than read, and WynnIris's own setting for it ships on.</li>
	 * <li><strong>The piece being drawn is the player's own hand.</strong> WynnIris withholds its
	 * light tweaks from a hand program whole ({@code EntityPatcher.java:1446}), and the reason is
	 * that the hand is not in the world the sky is over: it is held in front of the camera and lit
	 * by the game rather than by the region, and a hand that brightened with the weather would be
	 * the one thing on screen that did.</li>
	 * </ul>
	 * <p>
	 * <strong>The hand is answered here rather than by withholding the step from its program</strong>,
	 * which is where WynnIris answers it and is the one place this port departs from it. Withholding
	 * a step from a program is a decision about the text of a translation, and that text is cached
	 * under a key which would then have to carry the decision as well; answering a number here is a
	 * value the runtime writes, and a program ever drawn both as a hand and as a body is answered for
	 * the draw it is in rather than for the file it came from. What it costs is one multiply by one
	 * on the hand.
	 *
	 * @param nightVision how far into night vision the player is, nought to one
	 * @param hand        whether the pass being drawn is the player's own hand
	 * @return the multiplier an entity's colour is taken up by
	 */
	public static float entityBoost(float nightVision, boolean hand) {
		if (hand || !isDark() || nightVision > NIGHT_VISION_SEES) {
			return 1.0F;
		}

		return 1.0F + BOOST_AT_FULL_STRENGTH * fade;
	}

	/** Whether the standing sky is one of the four whose scene is darkened. */
	private static boolean isDark() {
		return id == 3 || id == 4 || id == 5 || id == 7;
	}

	/** Forgets the identity, the fade and the grace. For a world or dimension change. */
	public static void reset() {
		forget();
	}

	/**
	 * Everything back to nothing, and the frame's own detections with it.
	 * <p>
	 * The detections are dropped rather than left standing because a stale one is a detection of a
	 * world that is no longer there: a dome submitted while a level was unloading would otherwise be
	 * resolved on the first frame of the next one, which is the frame every value here is supposed
	 * to be re-read on.
	 */
	private static void forget() {
		preferredId = 0;
		preferredDeltaY = Float.MAX_VALUE;
		fallbackId = 0;
		id = 0;
		fade = 0.0F;
		recentId = 0;
		grace = 0.0F;
		candidateId = 0;
		candidateFrames = 0;
	}

	private static int takePreferred() {
		int taken = preferredId;
		preferredId = 0;
		preferredDeltaY = Float.MAX_VALUE;
		return taken;
	}

	private static int takeFallback() {
		int taken = fallbackId;
		fallbackId = 0;
		return taken;
	}

	/**
	 * Says what the sky is, once per change.
	 * <p>
	 * <strong>Said rather than left to be inferred, for the reason the patch switch says its own
	 * state.</strong> A sky that never arrived, a sky that arrived in the wrong place and a sky that
	 * is being drawn correctly look the same from a chair and leave the same trace, which is none;
	 * the line is what tells a reading taken with the feature from a reading taken without it, and
	 * it is the thing a player can quote back when a region's sky is the wrong colour.
	 */
	private static void say(int before, int after) {
		if (after == 0) {
			Vitrail.logger().info("The Wynncraft sky has faded out, so the scene under it is the "
					+ "pack's own again; it was {}.", before);
			return;
		}

		Vitrail.logger().info("The Wynncraft sky is now {}: the scene under it is tinted from it "
				+ "for as long as it stands.", after);
	}
}
