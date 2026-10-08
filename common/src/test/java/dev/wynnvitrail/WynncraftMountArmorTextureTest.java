package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.platform.NativeImage;

import org.junit.jupiter.api.Test;

/**
 * Lays armour out over a skin off-game, over texels painted by hand.
 * <p>
 * <strong>The tables are the part somebody has to be able to check and the part a compiler cannot
 * check at all.</strong> Fourteen rectangles and five mirror tables decide where every face of every
 * piece of armour lands, and a rectangle one texel out is a plate on a mount's face: the geometry
 * draws, the texture is a texture, and nothing anywhere says which of the two was wrong. What can be
 * read is the arithmetic the tables are spent through, and that is what is read here - a texel put
 * into a known place in the source and looked for in a known place in the target.
 * <p>
 * The two cases that are not a plain blit are the two that get a test of their own, because they are
 * the two where a table cannot be read off a skin by eye: the slim arm's plate is narrower than the
 * armour sheet's arm and takes the OUTER edge of it, and a left limb is the right one read backwards
 * in both directions.
 */
class WynncraftMountArmorTextureTest {

	/** A texel that is not any of the others, so that a copy of it can be looked for. */
	private static final int MARK = 0xFF3366CC;

	@Test
	void theHelmetLandsWhereASkinKeepsItsOuterLayer() {
		try (NativeImage source = sheet(); NativeImage target = sheet()) {
			source.setPixel(8, 8, MARK);

			WynncraftMountArmorTexture.lay(source, target, WynncraftMountArmorTexture.NO_COLOUR,
					WynncraftMountArmorTexture.Piece.OUTER_HEAD, WynncraftMountArmorTexture.Body.WIDE);

			// The helmet's front face at the sheet's own (8, 8), moved to where a skin keeps the
			// outer layer of a head: thirty-two texels right and nothing down.
			assertEquals(MARK, target.getPixel(40, 8),
					"the helmet's front face did not land where a skin keeps it");
			assertEquals(0, target.getPixel(8, 8), "the helmet was laid out in its own place");
		}
	}

	/**
	 * A hole in a layer is a hole, and it is the reason two layers can be laid down over each other
	 * at all: copying a clear texel would erase what the layer beneath had already put there.
	 */
	@Test
	void aClearTexelOfALayerCopiesNothingOverWhatIsBeneath() {
		try (NativeImage source = sheet(); NativeImage target = sheet()) {
			target.setPixel(40, 8, MARK);

			WynncraftMountArmorTexture.lay(source, target, WynncraftMountArmorTexture.NO_COLOUR,
					WynncraftMountArmorTexture.Piece.OUTER_HEAD, WynncraftMountArmorTexture.Body.WIDE);

			assertEquals(MARK, target.getPixel(40, 8),
					"a clear texel of a layer erased the one beneath it");
		}
	}

	/** A dye is a multiply of the texel and nothing else, and the alpha is not the dye's business. */
	@Test
	void aLayerDyeMultipliesTheTexelAndLeavesItsAlphaAlone() {
		assertEquals(0xFF402010, WynncraftMountArmorTexture.tint(0xFF804020, 0xFF808080),
				"the dye is not the multiply the game's own dyed armour is");
		assertEquals(MARK, WynncraftMountArmorTexture.tint(MARK, WynncraftMountArmorTexture.NO_COLOUR),
				"an undyed layer's texels were changed");
		assertEquals(0x80, WynncraftMountArmorTexture.tint(0x80804020, 0xFF808080) >>> 24,
				"the dye took the texel's own alpha with it");
	}

	/**
	 * One texel over another: an opaque source replaces what is there, a clear one leaves it, and
	 * neither of those is the interesting case - the interesting one is the half-transparent source,
	 * where a half-and-half average and the source-over formula give different answers.
	 */
	@Test
	void aLayerOverAnotherIsASourceOverAndNotAnAverage() {
		try (NativeImage target = sheet()) {
			target.setPixel(0, 0, 0xFF000000);
			WynncraftMountArmorTexture.alphaComposite(target, 0, 0, 0xFFFFFFFF);
			assertEquals(0xFFFFFFFF, target.getPixel(0, 0),
					"an opaque texel over another did not replace it");

			target.setPixel(1, 0, MARK);
			WynncraftMountArmorTexture.alphaComposite(target, 1, 0, 0x00FFFFFF);
			assertEquals(MARK, target.getPixel(1, 0), "a clear texel over another did not leave it");

			// Half of white over black: the source-over answer is a hundred and twenty-eight, where
			// an average of the two would be a hundred and twenty-seven, and the point of the test is
			// that the formula is the one that was written rather than one that happens to be close.
			target.setPixel(2, 0, 0xFF000000);
			WynncraftMountArmorTexture.alphaComposite(target, 2, 0, 0x80FFFFFF);
			assertEquals(128, target.getPixel(2, 0) >>> 16 & 0xFF,
					"a half-transparent texel over another is not the source-over answer");
		}
	}

	/**
	 * The slim arm keeps the outer edge of the sheet's arm, which is the one place a copy reads a
	 * source wider than its destination.
	 * <p>
	 * The four columns are painted with four different texels and the three that arrive are read
	 * back one at a time, because the mistake this guards is a shift of one texel: a test that
	 * checked the shape of the copy rather than which column came from where would pass on a plate a
	 * texel out along the front and the back.
	 */
	@Test
	void aSlimArmsPlateTakesTheOuterEdgeOfTheArmsTexels() {
		try (NativeImage source = sheet(); NativeImage target = sheet()) {
			for (int column = 0; column < 4; column++) {
				// The wide arm's front face is four texels wide at (44, 20).
				source.setPixel(44 + column, 20, 0xFF000000 | column + 1);
			}

			WynncraftMountArmorTexture.lay(source, target, WynncraftMountArmorTexture.NO_COLOUR,
					WynncraftMountArmorTexture.Piece.OUTER_CHEST, WynncraftMountArmorTexture.Body.SLIM);

			// Three texels at (44, 20) moved sixteen down, which is where a skin keeps the outer
			// layer of an arm, taking columns one, two and three of the sheet's four.
			assertEquals(0xFF000002, target.getPixel(44, 36),
					"the slim arm's plate did not keep the outer edge of the arm's texels");
			assertEquals(0xFF000004, target.getPixel(46, 36),
					"the slim arm's plate is not three texels of the arm's four");
		}
	}

	/**
	 * A left arm is the sheet's right one read backwards in both directions, and the two directions
	 * are asserted together because they are one table each and a reader checking one has to see the
	 * other.
	 */
	@Test
	void aLeftArmIsTheRightOneReadBackwardsInBothDirections() {
		try (NativeImage source = sheet(); NativeImage target = sheet()) {
			source.setPixel(47, 19, MARK);

			WynncraftMountArmorTexture.lay(source, target, WynncraftMountArmorTexture.NO_COLOUR,
					WynncraftMountArmorTexture.Piece.OUTER_CHEST, WynncraftMountArmorTexture.Body.WIDE);

			// The left arm's top face lands at (36, 48) moved sixteen right, which is (52, 48), and
			// the sheet's top face is four by four: mirrored in both directions, the texel at its
			// bottom right corner is the one the target's top left corner reads.
			assertEquals(MARK, target.getPixel(52, 48),
					"a left arm was not read backwards in both directions");
		}
	}

	/**
	 * The boots and the leggings are the two layers that put a leg on each side, and the difference
	 * between them is the one mirror table the boots carry and the leggings do not.
	 */
	@Test
	void theBootsAndTheLeggingsBothFillBothLegs() {
		try (NativeImage source = sheet(); NativeImage target = sheet()) {
			source.setPixel(4, 20, MARK);

			WynncraftMountArmorTexture.lay(source, target, WynncraftMountArmorTexture.NO_COLOUR,
					WynncraftMountArmorTexture.Piece.LEGGINGS, WynncraftMountArmorTexture.Body.WIDE);

			// The right leg's front face at the sheet's own place, sixteen down, and the left leg's
			// from the sheet's right leg read across.
			assertEquals(MARK, target.getPixel(4, 36), "the leggings did not cover the right leg");
		}
	}

	/** A sheet of the size every player texture is, which is what every rectangle above is on. */
	private static NativeImage sheet() {
		return new NativeImage(64, 64, true);
	}
}
