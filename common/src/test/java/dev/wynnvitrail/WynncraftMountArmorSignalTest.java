package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Reads the colour an effect is written in, off-game, from colours written by hand.
 * <p>
 * <strong>The one thing this has to get right is refusing a colour that is not a signal.</strong>
 * The decode is called on every dye colour and every custom model data colour a player's armour
 * carries, and a false positive is a chestplate drawing an effect the player never had - which is a
 * picture, and a picture that looks like something the server meant. The green and the blue are what
 * make the signal unmistakable, so both are tested at the values either side of the ones that match.
 * <p>
 * The four ways an effect can be spent are tested as a partition as well as one at a time, because
 * the mistake that matters is not a wrong answer to one question but two questions answering yes for
 * one number: an effect that is both a tint and a base is an effect drawn twice.
 */
class WynncraftMountArmorSignalTest {

	/** A signal for effect ten, which is an aurora and is one of the base effects. */
	private static final int SIGNAL = 0x0AFF00;

	/**
	 * A colour is a signal only where the red carries a number and the green and the blue could not
	 * have been a dye, and each of the three is checked at the values either side.
	 */
	@Test
	void aColourIsASignalOnlyWhereAllThreeChannelsAgree() {
		assertEquals(SIGNAL, WynncraftMountArmorSignal.decode(SIGNAL), "a signal was not read");

		assertEquals(WynncraftMountArmorSignal.NO_EFFECT,
				WynncraftMountArmorSignal.decode(0x0AFE00),
				"a colour one step off the signal's green was read as one");
		assertEquals(WynncraftMountArmorSignal.NO_EFFECT,
				WynncraftMountArmorSignal.decode(0x0AFF01),
				"a colour with any blue in it was read as a signal");
		assertEquals(WynncraftMountArmorSignal.NO_EFFECT,
				WynncraftMountArmorSignal.decode(0x00FF00),
				"a signal with no number in it was read as one");
		assertEquals(WynncraftMountArmorSignal.NO_EFFECT,
				WynncraftMountArmorSignal.decode(0x21FF00),
				"a number past the last effect was read as one");

		// The alpha is the game's and not the server's: a dye carries whatever it likes there.
		assertEquals(SIGNAL, WynncraftMountArmorSignal.decode(0xFF000000 | SIGNAL),
				"an opaque signal was not read");
		assertEquals(SIGNAL, WynncraftMountArmorSignal.decode(0x00000000 | SIGNAL),
				"a transparent signal was not read");
	}

	/** And the number comes out of the red, with nought for everything that is not one. */
	@Test
	void theNumberIsTheRedChannelAndNoughtIsNoEffect() {
		assertEquals(10, WynncraftMountArmorSignal.effectOf(SIGNAL), "the number is not the red");
		assertEquals(0, WynncraftMountArmorSignal.effectOf(WynncraftMountArmorSignal.NO_EFFECT),
				"no effect did not read as nought");
		assertEquals(0, WynncraftMountArmorSignal.effectOf(0x00FF00),
				"a signal with no number read as an effect");
	}

	/**
	 * The glint question is the complement of the other three, and a tint is never one of the rest.
	 * <p>
	 * <strong>This test found a mistake in the class it reads, which is worth saying.</strong> The
	 * first version of it asserted that no effect is two of the three named ways at once - a partition
	 * - and that is not true: the glitch and the distortion are both rebuilt on the CPU and carried
	 * through to the program, deliberately, because the part of those two effects that moves is the
	 * part a texture cannot hold. So what is asserted is the pair of things that ARE true, and the two
	 * overlaps are named rather than tolerated: a tint against the rest is the partition, and the glint
	 * answer is exactly what none of the three claims.
	 */
	@Test
	void anEffectIsAGlintExactlyWhereTheOtherThreeDoNotClaimIt() {
		for (int effectId = 0; effectId <= WynncraftMountArmorSignal.MAX_EFFECT; effectId++) {
			boolean tint = WynncraftMountArmorSignal.isStaticTint(effectId);
			boolean base = WynncraftMountArmorSignal.isDynamicBase(effectId);
			boolean overlay = WynncraftMountArmorSignal.usesShaderOverlay(effectId);

			assertFalse(tint && (base || overlay),
					"effect " + effectId + " is a tint and something else at once");

			assertEquals(!(tint || base || overlay) && effectId >= 1 && effectId <= 31,
					WynncraftMountArmorSignal.hasAnimatedGlint(effectId),
					"effect " + effectId + " is not a glint and not one of the three either");
		}

		// And the two overlaps are the two the comment above names, so that a third one added by
		// accident fails here rather than drawing an effect twice.
		for (int effectId = 0; effectId <= WynncraftMountArmorSignal.MAX_EFFECT; effectId++) {
			boolean both = WynncraftMountArmorSignal.isDynamicBase(effectId)
					&& WynncraftMountArmorSignal.usesShaderOverlay(effectId);

			assertEquals(effectId == 4 || effectId == 13, both,
					"effect " + effectId + " is spent in two ways at once and is not one of the two");
		}
	}

	/** The two states and the two ranges, at their own edges. */
	@Test
	void theStatesAndTheRangesAreReadAtTheirEdges() {
		assertTrue(WynncraftMountArmorSignal.isHideArmor(2), "the hiding number is not the hiding number");
		assertFalse(WynncraftMountArmorSignal.isHideArmor(3), "an ordinary effect was read as hiding");

		assertFalse(WynncraftMountArmorSignal.isStaticTint(14), "the effect before the tints is a tint");
		assertTrue(WynncraftMountArmorSignal.isStaticTint(15), "the first tint is not a tint");
		assertTrue(WynncraftMountArmorSignal.isStaticTint(24), "the last tint is not a tint");
		assertFalse(WynncraftMountArmorSignal.isStaticTint(25), "the shiny after the tints is a tint");

		assertTrue(WynncraftMountArmorSignal.isDynamicBase(13), "the last base effect is not one");
		assertFalse(WynncraftMountArmorSignal.isDynamicBase(14), "a glint was read as a base effect");

		assertTrue(WynncraftMountArmorSignal.usesShaderOverlay(5), "a shader overlay was not read");
		assertFalse(WynncraftMountArmorSignal.usesShaderOverlay(9), "a glint was read as an overlay");

		// The last number is a state rather than a pattern, so it is not a glint; the first is.
		assertTrue(WynncraftMountArmorSignal.hasAnimatedGlint(1), "the first effect is not a glint");
		assertFalse(WynncraftMountArmorSignal.hasAnimatedGlint(32),
				"the last number of all was read as a glint");
	}

	/**
	 * The two alphas the overlay's own geometry carries, which are the pair the program reads back.
	 * <p>
	 * Read as whole colours rather than as alphas, because the white under them is part of the
	 * protocol: the program multiplies a plate by what it finds, and a marker with a number in its
	 * colour would tint every plate it marks.
	 */
	@Test
	void theOverlaysSignalIsAnAlphaOverTheSameWhite() {
		assertEquals(0xFCFFFFFF, WynncraftMountArmorSignal.overlaySignal(WynncraftMountArmorSignal.OUTER_ALPHA),
				"the outer layers' marker is not the one the program reads");
		assertEquals(0xFAFFFFFF,
				WynncraftMountArmorSignal.overlaySignal(WynncraftMountArmorSignal.LEGGINGS_ALPHA),
				"the leggings' marker is not the one the program reads");
		assertFalse(WynncraftMountArmorSignal.OUTER_ALPHA == WynncraftMountArmorSignal.LEGGINGS_ALPHA,
				"the two layers are marked the same and cannot be told apart");
	}
}
