package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Drives the sky selection off-game, a frame at a time, and checks what it settles on.
 * <p>
 * <strong>This is the check the port cannot do by building, and it cannot do by looking
 * either.</strong> Everything about the selection is a duration - three frames of agreement, half a
 * second to arrive, four seconds to leave, ten seconds of grace - and a picture taken at the end of
 * any of them is the same picture whether the durations are right or whether the sky simply
 * appeared. What a frame-by-frame reading can say and a photograph cannot is which frame the answer
 * changed on.
 * <p>
 * <strong>The frames are driven here rather than waited for</strong>, which is the whole reason the
 * class measures its durations in the frame's own step rather than against a wall clock: a test that
 * had to sit through ten seconds of grace to read the far side of it would be a test nobody runs,
 * and one that reads it by moving a clock would be testing the clock.
 * <p>
 * The patch switch is set explicitly by every test, because the state machine is inside it and the
 * tests share a JVM: {@code WynncraftEntityProgramTest} moves the same switch and reads it through
 * the file as well.
 */
class WynncraftSkyTest {

	/** A frame at sixty frames a second, which is the step every test below integrates over. */
	private static final float FRAME = 1.0F / 60.0F;

	/** The height a primary dome stands at, which is the one the selection prefers. */
	private static final float PRIMARY_Y = -601.6F;

	/**
	 * The first identity is taken at once, because there is nothing for it to be wrong about: the
	 * debounce exists to keep a passing dome from replacing a standing sky, and a sky replacing
	 * nothing is the case it is not for.
	 */
	@Test
	void theFirstSkyIsTakenWithoutWaitingForTheDebounce() {
		withPatch(true, () -> {
			noteFrames(1, 3);

			assertEquals(3, WynncraftSky.id(),
					"the first sky was made to wait for a debounce it has nothing to be wrong about");
			assertTrue(WynncraftSky.fade() > 0.0F && WynncraftSky.fade() < 0.1F,
					"the first sky arrived at full strength rather than fading in: "
							+ WynncraftSky.fade());
		});
	}

	/**
	 * Two seconds of a sky standing gets it to about ninety-eight per cent, which is WynnIris's own
	 * half-second time constant and the number the scene's tint is scaled by.
	 */
	@Test
	void aSkyThatStandsReachesFullStrengthInAboutTwoSeconds() {
		withPatch(true, () -> {
			noteFrames(1, 3);
			float afterOneFrame = WynncraftSky.fade();

			noteFrames(120, 3);

			assertTrue(WynncraftSky.fade() > 0.97F,
					"two seconds of one sky did not settle it: " + WynncraftSky.fade());
			assertTrue(WynncraftSky.fade() <= 1.0F,
					"the fade went past full strength: " + WynncraftSky.fade());
			assertTrue(afterOneFrame < WynncraftSky.fade(),
					"the fade did not advance with the frames");
		});
	}

	/**
	 * A new identity has to be seen three frames running, and a frame of one on its own is a dome
	 * being passed rather than a region being entered.
	 * <p>
	 * Both halves are asserted in one test because the second is the reason for the first: a
	 * debounce that let a single frame through would be indistinguishable from no debounce at all in
	 * a reading that only ever fed it one frame.
	 * <p>
	 * The third identity is a third and not the sky that was replaced, which it has to be: a sky
	 * just left is inside its grace and is refused before the debounce is ever reached, so a
	 * candidate counted against it would be testing the grace twice over.
	 */
	@Test
	void aNewSkyNeedsThreeFramesOfAgreementAndOneIsNotEnough() {
		withPatch(true, () -> {
			noteFrames(120, 3);

			noteFrames(2, 5);
			assertEquals(3, WynncraftSky.id(), "two frames of a new sky were enough to replace one");

			noteFrames(1, 5);
			assertEquals(5, WynncraftSky.id(), "three frames of a new sky did not replace the old one");

			// And a candidate is forgotten the moment it is not seen, so that two domes passed in
			// turn are not one switch between them.
			noteFrames(2, 7);
			noteFrames(1, 5);
			noteFrames(2, 7);

			assertEquals(5, WynncraftSky.id(),
					"a candidate survived a frame that did not show it");
		});
	}

	/**
	 * A departure is a fade and then nothing, and the identity just left is remembered while it goes.
	 * <p>
	 * The fade is read rather than assumed to be monotone, because the two-phase decay is the part of
	 * this that a slower or a faster reading would agree with at either end: what the middle frames
	 * say is the only place the shape shows.
	 */
	@Test
	void aSkyThatLeavesFadesOutAndEndsWithNothing() {
		withPatch(true, () -> {
			noteFrames(120, 3);
			float settled = WynncraftSky.fade();

			idleFrames(60);
			float afterOneSecond = WynncraftSky.fade();
			assertTrue(afterOneSecond < settled && afterOneSecond > 0.4F,
					"the first second of the fade out was not the slow one: " + afterOneSecond);

			idleFrames(400);

			assertEquals(0, WynncraftSky.id(), "a sky that faded out is still the one being drawn");
			assertEquals(0.0F, WynncraftSky.fade(), "the fade stopped somewhere above nothing");
			assertEquals(3, WynncraftSky.recentId(), "the sky just left was not remembered");
		});
	}

	/**
	 * The dome of the region just left is still loaded, still submitted and still marked, so without
	 * the grace the sky would come back on the very next frame - and the grace is what that is for.
	 * <p>
	 * The far side is read as well, and it is the half that says the grace is a window rather than a
	 * refusal: a region walked into a second time is entered a second time.
	 */
	@Test
	void theDomeJustLeftIsIgnoredUntilItsGraceRunsOut() {
		withPatch(true, () -> {
			noteFrames(120, 3);
			idleFrames(500);
			assertEquals(0, WynncraftSky.id(), "the fixture did not finish its fade out");

			noteFrames(30, 3);
			assertEquals(0, WynncraftSky.id(),
					"the dome just left was believed again inside its own grace");

			// The grace is ten seconds of world time, and the frames above spent half a second of
			// it. Six hundred more takes it past the end.
			idleFrames(600);
			noteFrames(1, 3);

			assertEquals(3, WynncraftSky.id(),
					"a region entered again after its grace could not be entered");
		});
	}

	/**
	 * The preferred dome is the one nearest the height a primary sky stands at, and a dome anywhere
	 * else is the fallback - which is what keeps a sky from vanishing when the camera drifts out of
	 * the band, and is WynnIris's own pair of rules.
	 */
	@Test
	void theDomeNearestThePrimaryHeightWinsAndAnyDomeIsBetterThanNone() {
		withPatch(true, () -> {
			WynncraftSky.reset();

			// A dome outside the band first and one inside it second, so that the answer cannot be
			// the last one noted.
			WynncraftSky.note(2, -400.0F);
			WynncraftSky.note(6, -605.0F);
			WynncraftSky.advance(FRAME);
			assertEquals(6, WynncraftSky.id(), "the fallback beat a dome the player is under");

			// And with nothing in the band, the fallback is what there is.
			WynncraftSky.reset();
			WynncraftSky.note(2, -400.0F);
			WynncraftSky.advance(FRAME);
			assertEquals(2, WynncraftSky.id(), "a dome outside the band was refused rather than fallen back on");

			// Two in the band and the nearer to the target wins, whichever was noted first.
			WynncraftSky.reset();
			WynncraftSky.note(4, -601.6F);
			WynncraftSky.note(7, -660.0F);
			WynncraftSky.advance(FRAME);
			assertEquals(4, WynncraftSky.id(), "the dome nearest the primary height did not win");
		});
	}

	/** A world change is the one moment none of this can mean anything, and everything goes. */
	@Test
	void aWorldChangeForgetsTheSkyTheFadeAndTheGrace() {
		withPatch(true, () -> {
			noteFrames(120, 3);
			idleFrames(30);

			WynncraftSky.reset();

			assertEquals(0, WynncraftSky.id(), "the sky of the world just left is still standing");
			assertEquals(0.0F, WynncraftSky.fade(), "the fade of the world just left is still running");
			assertEquals(0, WynncraftSky.recentId(), "the world just left is still being kept out");
		});
	}

	/**
	 * And the switch, which is the whole reason a state machine rather than a reading is worth
	 * testing: with the patch off the answer is nothing, and a detection still in hand is dropped
	 * rather than resolved on the frame the patch is turned back on.
	 */
	@Test
	void thePatchOffLeavesNoSkyAndDropsWhatWasDetected() {
		withPatch(true, () -> {
			noteFrames(120, 3);
			assertTrue(WynncraftSky.fade() > 0.0F, "the fixture never had a sky");

			withPatch(false, () -> {
				WynncraftSky.advance(FRAME);

				assertEquals(0, WynncraftSky.id(), "a sky survived the patch being turned off");
				assertEquals(0.0F, WynncraftSky.fade(), "a fade survived the patch being turned off");
			});
		});
	}

	/**
	 * The compensation a dark sky owes the entities under it, and the three cases that owe one.
	 * <p>
	 * Read as a number rather than as a picture for the reason the rest of this file is: half again
	 * at full strength, arriving and leaving with the fade, is a value that a photograph of a storm
	 * cannot be asked about - what a photograph shows is a scene somebody has already judged, and
	 * the judgement is the number.
	 */
	@Test
	void aDarkSkyCompensatesItsEntitiesAndAPaleOneDoesNot() {
		withPatch(true, () -> {
			noteFrames(240, 4);

			assertEquals(1.5F, WynncraftSky.entityBoost(0.0F, false), 0.01F,
					"a dark sky at full strength does not lift its entities by the half WynnIris uses");
			assertEquals(1.0F, WynncraftSky.entityBoost(1.0F, false), 0.0F,
					"night vision was compensated for on top of itself");

			WynncraftSky.reset();
			noteFrames(240, 1);

			assertEquals(1.0F, WynncraftSky.entityBoost(0.0F, false), 0.0F,
					"a pale sky compensated for a darkening it does not do");
		});
	}

	/**
	 * And the hand, which is the one piece on screen that is not in the world the sky is over.
	 * <p>
	 * WynnIris answers this by withholding the whole light-tweak step from a hand program
	 * ({@code EntityPatcher.java:1446}); this port answers it as the value is written, so what is
	 * checked here is the rule and not a translation. A hand that brightened with the weather would
	 * be the one thing on screen that did.
	 */
	@Test
	void theHandIsNeverCompensatedForTheSkyItIsHeldUnder() {
		withPatch(true, () -> {
			noteFrames(240, 4);

			assertEquals(1.0F, WynncraftSky.entityBoost(0.0F, true), 0.0F,
					"the hand was lifted along with the world it is held in front of");
		});
	}

	/**
	 * What a text display under a dark sky is owed, and the two cases that are owed nothing.
	 * <p>
	 * The rain is the one gate the entity boost has not got, and it is WynnIris's own: a storm is dark
	 * enough on its own and letters lifted inside one read as letters glowing. It is checked here
	 * because it is the only input to this rule that does not come off the sky.
	 * <p>
	 * Night vision is the other way round and is asserted by the signature rather than by a case:
	 * there is no argument for it, because WynnIris's text half has no check for it. A player wearing
	 * a filter over the whole screen still has to read a sign the sky darkened, where the entities
	 * around them are already lifted by the other rule and would be washed out by a second helping.
	 */
	@Test
	void aDarkSkyLiftsTheLettersOutOfTheDarkUnlessItIsRaining() {
		withPatch(true, () -> {
			noteFrames(240, 4);

			assertEquals(1.0F, WynncraftSky.textLightBoost(0.0F), 0.01F,
					"a dark sky did not lift the letters under it");
			assertEquals(0.0F, WynncraftSky.textLightBoost(0.5F), 0.0F,
					"the letters were lifted inside a storm");

			WynncraftSky.reset();
			noteFrames(240, 1);

			assertEquals(0.0F, WynncraftSky.textLightBoost(0.0F), 0.0F,
					"a pale sky lifted the letters for a darkening it does not do");
		});
	}

	/** One frame's detection of one sky, which is the shape every test above feeds on. */
	private static void noteFrames(int frames, int skyId) {		for (int frame = 0; frame < frames; frame++) {
			WynncraftSky.note(skyId, PRIMARY_Y);
			WynncraftSky.advance(FRAME);
		}
	}

	/** The same with nothing detected, which is what a frame that submits no dome looks like. */
	private static void idleFrames(int frames) {
		for (int frame = 0; frame < frames; frame++) {
			WynncraftSky.advance(FRAME);
		}
	}

	/**
	 * Runs the body with the patch on or off and puts the switch back afterwards.
	 * <p>
	 * The state is dropped on the way in as well as on the way out, because it is held in statics
	 * and the tests share a JVM: a test that read a frame without saying where it started would be
	 * reading whatever the test before it left.
	 */
	private static void withPatch(boolean on, Runnable body) {
		String previous = System.getProperty("wynnvitrail.enabled");
		System.setProperty("wynnvitrail.enabled", Boolean.toString(on));
		WynncraftSky.reset();
		try {
			body.run();
		} finally {
			if (previous == null) {
				System.clearProperty("wynnvitrail.enabled");
			} else {
				System.setProperty("wynnvitrail.enabled", previous);
			}

			WynncraftSky.reset();
		}
	}
}
