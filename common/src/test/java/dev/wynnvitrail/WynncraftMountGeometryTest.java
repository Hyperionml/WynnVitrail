package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unpacks the number Wynncraft writes into a mount quad's height, off-game, from numbers written by
 * hand.
 * <p>
 * <strong>This is the half of the mount overlay that can be checked without the game and the half
 * that everything else depends on.</strong> A limb read out of the wrong digit puts a chestplate on a
 * leg; a Steve read as an Alex widens an arm that was never slim; and a stand-off taken from the
 * wrong layer buries the plate inside the body it was meant to cover. None of those is an error
 * anywhere - the geometry draws, the picture is simply wrong in a way that only somebody who knows
 * what a Wynncraft mount looks like could name.
 * <p>
 * The numbers below are built the way the server builds them rather than copied from a log: a limb,
 * whether the body is a Steve or an Alex, and how far the limb has faded are written into one
 * counter in mixed radix, and the counter is added to two of the first radix above the threshold.
 * Writing them out makes the test say what the encoding is, which is the thing a reader of the
 * divisions in {@link WynncraftMountGeometry#limbIndex} has to hold in their head otherwise.
 */
class WynncraftMountGeometryTest {

	/**
	 * The height a counter of one limb, one body type and one fade is written at, as the server
	 * writes it: the counter in mixed radix, one of the first radix above the threshold - which is
	 * what puts the counters's own zero exactly at the threshold, and is why the threshold is two of
	 * that radix rather than one.
	 */
	private static float encoded(int limb, int bodyType, int fade) {
		int counter = (limb * WynncraftMountGeometry.STEVE_ALEX_RADIX
				+ bodyType) * WynncraftMountGeometry.LIMB_FADE_RADIX + fade;

		return WynncraftMountGeometry.FAKE_PLAYER_Y + counter * WynncraftMountGeometry.Y_RADIX;
	}

	/**
	 * The threshold, which is the one thing here that is a threshold rather than a division: below it
	 * the number is the height it looks like and every ordinary model in the world lives there.
	 */
	@Test
	void aHeightBelowTheThresholdIsAnOrdinaryModelAndOneAboveItIsNot() {
		assertFalse(WynncraftMountGeometry.isFakePlayerVertex(
						WynncraftMountGeometry.FAKE_PLAYER_Y - 0.1F),
				"a vertex just under the threshold was taken for a mount's");
		assertTrue(WynncraftMountGeometry.isFakePlayerVertex(WynncraftMountGeometry.FAKE_PLAYER_Y),
				"a vertex at the threshold was not taken for a mount's");
		assertFalse(WynncraftMountGeometry.isFakePlayerVertex(72.0F),
				"an ordinary model's height was taken for a mount's");
	}

	/** Every limb of the five the server uses comes back out of the counter it was written into. */
	@Test
	void everyLimbComesBackOutOfItsOwnDigit() {
		int[] limbs = {WynncraftMountGeometry.HEAD, WynncraftMountGeometry.LEFT_ARM,
				WynncraftMountGeometry.RIGHT_ARM, WynncraftMountGeometry.LEFT_LEG,
				WynncraftMountGeometry.RIGHT_LEG};

		for (int limb : limbs) {
			assertEquals(limb, WynncraftMountGeometry.limbIndex(encoded(limb, 0, 0)),
					"limb " + limb + " did not come back out of its own digit");
		}
	}

	/**
	 * The two digits under the limb are somebody else's business, and this is the assertion that says
	 * so: a body drawn as an Alex, and a limb part way through its fade, are the same limb.
	 * <p>
	 * Read as a pair rather than as two tests because the two are the same mistake - a division
	 * dropped, or two of them run in the other order - and one assertion that walks every combination
	 * is the one that cannot pass by accident on the combination a hasty fix happened to try.
	 */
	@Test
	void theBodyTypeAndTheFadeDoNotChangeWhichLimbItIs() {
		for (int limb = 0; limb < WynncraftMountGeometry.LIMB_INDEX_RADIX; limb++) {
			for (int bodyType = 0; bodyType < WynncraftMountGeometry.STEVE_ALEX_RADIX; bodyType++) {
				for (int fade = 0; fade < WynncraftMountGeometry.LIMB_FADE_RADIX; fade++) {
					assertEquals(limb, WynncraftMountGeometry.limbIndex(encoded(limb, bodyType, fade)),
							"limb " + limb + " moved when the body type was " + bodyType
									+ " and the fade was " + fade);
				}
			}
		}
	}

	/**
	 * And the modulus, which is what makes the limb a digit rather than a number that grows: a
	 * counter whose limb digit has run past the five names wraps, and that is the encoding rather
	 * than a vertex to refuse.
	 */
	@Test
	void aLimbDigitPastTheLastLimbWrapsRatherThanRunningAway() {
		assertEquals(WynncraftMountGeometry.HEAD,
				WynncraftMountGeometry.limbIndex(encoded(WynncraftMountGeometry.LIMB_INDEX_RADIX, 0, 0)),
				"a limb digit of six did not wrap back to the first name");
		assertEquals(WynncraftMountGeometry.LEFT_ARM,
				WynncraftMountGeometry.limbIndex(encoded(WynncraftMountGeometry.LIMB_INDEX_RADIX + 2, 0, 0)),
				"a limb digit of eight did not wrap to the third name");
	}

	/** An ordinary height is not a limb, and the answer for one is the one a caller skips. */
	@Test
	void aVertexThatIsNotAMountHasNoLimb() {
		assertEquals(-1, WynncraftMountGeometry.limbIndex(72.0F),
				"an ordinary vertex was given a limb");
	}

	/**
	 * The two thresholds coincide, and that is the encoding rather than a coincidence: the counter's
	 * zero stands exactly where the height stops being a height, so the lowest vertex a mount has is
	 * its head.
	 * <p>
	 * Asserted because it is the one height where the two answers could reasonably have been made to
	 * disagree - one test asking "is this a mount" and another asking "which limb" - and a reader who
	 * made the limb's threshold the higher of the two would drop the head off every mount in the
	 * world without anything to say so.
	 */
	@Test
	void theEncodingsOwnZeroIsTheThresholdAndItIsAHead() {
		assertEquals(WynncraftMountGeometry.HEAD,
				WynncraftMountGeometry.limbIndex(WynncraftMountGeometry.FAKE_PLAYER_Y),
				"the encoding's zero is not the first limb");
		assertTrue(WynncraftMountGeometry.isFakePlayerVertex(WynncraftMountGeometry.FAKE_PLAYER_Y),
				"the height the encoding starts at is not a mount's");
	}

	/**
	 * The stand-off, which is the difference between a plate lying on the mount and a plate inside
	 * it, and which is the only place the three layers are told apart.
	 */
	@Test
	void everyLayerStandsOffTheLimbItLiesOverByItsOwnAmount() {
		assertEquals(0.75F, WynncraftMountGeometry.Layer.OUTER.expand(WynncraftMountGeometry.LEFT_ARM),
				0.0001F, "the outer layer's body stand-off is not the one it is drawn with");
		assertEquals(0.65F, WynncraftMountGeometry.Layer.OUTER.expand(WynncraftMountGeometry.LEFT_LEG),
				0.0001F, "the outer layer's leg stand-off is not the one it is drawn with");
		assertEquals(1.25F, WynncraftMountGeometry.Layer.BOOTS.expand(WynncraftMountGeometry.RIGHT_LEG),
				0.0001F, "the boots stand off by something other than the most of the three");
		assertEquals(0.25F, WynncraftMountGeometry.Layer.LEGGINGS.expand(WynncraftMountGeometry.HEAD),
				0.0001F, "the leggings' body stand-off is not the smallest of the six");

		// The leg figure is the one a boot and a legging change places on, and the arm and the leg
		// are the two the question is asked with.
		assertTrue(WynncraftMountGeometry.Layer.OUTER.expand(WynncraftMountGeometry.RIGHT_LEG)
						< WynncraftMountGeometry.Layer.OUTER.expand(WynncraftMountGeometry.RIGHT_ARM),
				"the outer layer does not stand further off an arm than off a leg");
	}

	/** The outer layer alone has its plates widened, and the reason is the order the client draws in. */
	@Test
	void onlyTheOutermostLayerIsWidenedForASlimArm() {
		assertTrue(WynncraftMountGeometry.Layer.OUTER.widensSlimArms(),
				"the layer whose plate is a slim arm's own silhouette is not the one widened");
		assertFalse(WynncraftMountGeometry.Layer.BOOTS.widensSlimArms(),
				"the boots were widened for an arm");
		assertFalse(WynncraftMountGeometry.Layer.LEGGINGS.widensSlimArms(),
				"the leggings were widened for an arm");
	}

	/**
	 * The width of a limb, and the two ways there is none to have.
	 * <p>
	 * A limb nothing was measured for has no span at all, and a limb measured at one point has a span
	 * of nothing; both are the case a push has to answer nought for, and a span that reported itself
	 * as having width in either would push a plate sideways off the limb it belongs to.
	 */
	@Test
	void aLimbWithNoSpanHasNoWidthAndNoMiddle() {
		WynncraftMountGeometry.LimbBounds empty = new WynncraftMountGeometry.LimbBounds();
		assertFalse(empty.hasWidth(), "a limb nothing was measured for claims a width");

		empty.include(3.0F);
		assertFalse(empty.hasWidth(), "a limb measured at one point claims a width");
		assertEquals(3.0F, empty.centreX(), 0.0001F, "one point is not its own middle");

		empty.include(5.0F);
		assertTrue(empty.hasWidth(), "a limb measured across two points claims no width");
		assertEquals(4.0F, empty.centreX(), 0.0001F, "the middle of two points is not between them");
	}

	/**
	 * The sideways push, which is what a slim arm needs and what every other limb must not get.
	 * <p>
	 * The side comes from the vertex's own x and not from the face it lies on, and the vertex exactly
	 * on the middle is the case that tells the two apart: a face's normal would push it, and a
	 * signum against the middle leaves it.
	 */
	@Test
	void aSlimArmsPlateIsPushedAwayFromTheMiddleOfTheArm() {
		WynncraftMountGeometry.LimbBounds bounds = new WynncraftMountGeometry.LimbBounds();
		bounds.include(-2.0F);
		bounds.include(2.0F);

		assertEquals(-WynncraftMountGeometry.SLIM_ARM_WIDTH,
				WynncraftMountGeometry.slimArmPush(-1.0F, bounds), 0.0001F,
				"a vertex on the near side of the arm was pushed the wrong way");
		assertEquals(WynncraftMountGeometry.SLIM_ARM_WIDTH,
				WynncraftMountGeometry.slimArmPush(1.0F, bounds), 0.0001F,
				"a vertex on the far side of the arm was pushed the wrong way");
		assertEquals(0.0F, WynncraftMountGeometry.slimArmPush(0.0F, bounds), 0.0001F,
				"a vertex on the middle of the arm was pushed off it");

		assertEquals(0.0F, WynncraftMountGeometry.slimArmPush(1.0F, null), 0.0001F,
				"a limb that was never measured was pushed anyway");

		WynncraftMountGeometry.LimbBounds flat = new WynncraftMountGeometry.LimbBounds();
		flat.include(1.0F);
		assertEquals(0.0F, WynncraftMountGeometry.slimArmPush(1.0F, flat), 0.0001F,
				"a limb with no span was pushed anyway");
	}
}
