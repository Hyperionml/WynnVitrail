package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.NativeImage;

import org.junit.jupiter.api.Test;

/**
 * Runs the armour effects over texels painted by hand, off-game.
 * <p>
 * <strong>What a test cannot say here is whether the picture is the one the server meant</strong>,
 * and nothing in this file tries to. What it can say is everything the transcription of two hundred
 * lines of arithmetic can get wrong silently, and that is three things in three places:
 * <ul>
 * <li><strong>The rounding.</strong> Every effect ends in a fraction turned into a byte, and a floor
 * where the reference rounds - or a cast where it floors - is a picture a shade darker that nobody
 * could name. The helpers are read exactly, at the values where the two would differ.</li>
 * <li><strong>The folds.</strong> Two of the effects sample their neighbours, and the coordinates
 * they sample at are negative: half of every armour piece is behind the middle it is measured
 * against. A remainder that keeps the dividend's sign, or a fraction taken by truncating, puts the
 * read outside the image on one side and silently clamps on the other.</li>
 * <li><strong>The dispatch.</strong> A number is the whole of what a texel is told about which effect
 * it belongs to, and a case that fell through to the wrong branch is a rainbow on a glitched weapon -
 * which draws, and looks deliberate.</li>
 * </ul>
 */
class WynncraftMountArmorEffectsTest {

	/** A texel that is not any of the others. */
	private static final int MARK = 0xFF3366CC;

	/** A clear texel, which is what a hole in a layer is. */
	private static final int CLEAR = 0;

	/**
	 * A fraction, at the one value where the two ways of taking one differ.
	 * <p>
	 * A negative coordinate's fraction is what every hue walk and every band is measured by, and
	 * {@code (int)} would answer minus a quarter where the reference answers three quarters - which
	 * is a walk that runs the other way for half of every armour piece.
	 */
	@Test
	void aFractionOfANegativeNumberIsAWholeFraction() {
		assertEquals(0.75F, WynncraftMountArmorEffects.fract(-0.25F), 0.0001F,
				"a negative fraction was truncated rather than folded");
		assertEquals(0.0F, WynncraftMountArmorEffects.fract(1.0F), 0.0001F,
				"a whole number has no fraction");
	}

	/**
	 * A coordinate folded back into an image, at the value where a remainder is not enough.
	 * <p>
	 * This is the one that matters at the edge: a glitch reads a texel one column off its own, and at
	 * the left edge that column does not exist - the repeat is what puts it on the right, and a
	 * remainder alone would put it at minus one.
	 */
	@Test
	void aCoordinateBeforeTheImageComesBackFromTheEndOfIt() {
		assertEquals(15, WynncraftMountArmorEffects.wrap(-1, 16), "a coordinate before the image did "
				+ "not come back from the end of it");
		assertEquals(0, WynncraftMountArmorEffects.wrap(16, 16), "a coordinate past the image did not "
				+ "come back to the start of it");
		assertEquals(3, WynncraftMountArmorEffects.wrap(3, 16), "a coordinate inside the image moved");
	}

	/** And the same read out of a real image, which is where the fold is actually spent. */
	@Test
	void aSampleBeforeTheLeftEdgeReadsTheRightEdge() {
		try (NativeImage source = new NativeImage(4, 1, true)) {
			source.setPixel(0, 0, 0xFFFFFFFF);
			source.setPixel(3, 0, 0xFF000000);

			// Half a texel before the left edge, which is half way between the last texel and the
			// first: white and black mixed evenly is neither.
			assertEquals(0xFF808080, WynncraftMountArmorEffects.sampleBilinearRepeated(source, -0.5F, 0.0F),
					"a sample before the image was clamped rather than repeated");
		}
	}

	/** A read outside an image the other way is held at its edge, and that is the other sampler. */
	@Test
	void aClampedSampleAtTheEdgeIsTheEdgeTexel() {
		try (NativeImage source = new NativeImage(4, 1, true)) {
			source.setPixel(0, 0, MARK);

			assertEquals(MARK, WynncraftMountArmorEffects.sampleClamped(source, -9, 0),
					"a clamped sample before the image did not land on its edge");
			assertEquals(MARK, WynncraftMountArmorEffects.sampleClamped(source, 0, -9),
					"a clamped sample above the image did not land on its edge");
			assertNotEquals(MARK, WynncraftMountArmorEffects.sampleClamped(source, 3, 0),
					"a clamped sample inside the image was moved");
		}
	}

	/**
	 * The colour helpers, at the values where a wrong rounding shows.
	 * <p>
	 * A grey read back as itself is the one assertion that catches a rounding in either direction, and
	 * it is why it is here: every other value in this file is a value a shade off and nobody would
	 * ever say which.
	 */
	@Test
	void theColourHelpersRoundTheWayTheReferenceRounds() {
		assertEquals(0xFF808080, WynncraftMountArmorEffects.grayscale(0xFF808080),
				"a grey was not read back as itself");
		assertEquals(1.0F, WynncraftMountArmorEffects.luma(0xFFFFFFFF), 0.0001F,
				"white is not the brightest a texel gets");
		assertEquals(0xFFFF00FF, WynncraftMountArmorEffects.invert(0xFF00FF00),
				"a texel was not turned over channel by channel");
		assertEquals(0xFF404040, WynncraftMountArmorEffects.multiply(0xFF808080, 0xFF808080),
				"two colours were not multiplied channel by channel");
		assertEquals(0x80112233, WynncraftMountArmorEffects.withAlpha(0xFF112233, 0x80),
				"a texel's alpha was not replaced");
		assertEquals(0xFFFF0000, WynncraftMountArmorEffects.rgba(0xFF0000, 1.0F),
				"a full opacity did not survive");
		assertEquals(0, WynncraftMountArmorEffects.rgba(0xFF0000, 0.0F),
				"a glint that rounded to clear was left as a colour");
	}

	/**
	 * The two dispatches, read by one property each that only the effect they name can have.
	 * <p>
	 * A property and not a value, because the values are the reference's and this file has already
	 * been read against it once: what a dispatch mistake looks like is the wrong property, and that is
	 * what can be caught - a rainbow is not greyscale, and a shadow sweep is not gold.
	 */
	@Test
	void eachNumberReachesTheEffectItNames() {
		int pixel = MARK;

		int halved = WynncraftMountArmorEffects.basePixel(null, 2, 0, 0, 1, 1, pixel, 0.0F);
		assertEquals(pixel & 0x00FFFFFF, halved & 0x00FFFFFF,
				"the translucent glint changed a texel's colour");
		assertTrue((halved >>> 24) < (pixel >>> 24),
				"the translucent glint did not bring a texel's alpha down");

		int grey = WynncraftMountArmorEffects.basePixel(null, 7, 0, 0, 1, 1, pixel, 0.0F);
		assertEquals((grey >>> 16) & 0xFF, grey & 0xFF,
				"the greyscale effect left a colour in the texel");
		assertEquals((grey >>> 8) & 0xFF, grey & 0xFF,
				"the greyscale effect left a colour in the texel");

		int turned = WynncraftMountArmorEffects.basePixel(null, 8, 0, 0, 1, 1, pixel, 0.0F);
		assertEquals(255 - ((pixel >>> 16) & 0xFF), (turned >>> 16) & 0xFF,
				"the invert effect did not turn the red channel over");

		int untouched = WynncraftMountArmorEffects.basePixel(null, 99, 0, 0, 1, 1, pixel, 0.0F);
		assertEquals(pixel, untouched, "a base effect this does not know changed the texel anyway");
	}

	/**
	 * The shadow sweep, read as the one thing that is true of it wherever it draws: it is black.
	 * <p>
	 * The times are walked rather than chosen, which is the point: the sweep is a band that crosses
	 * the armour, and any single time would be a time at which it happens to be somewhere. What is
	 * asserted is that it drew AT ALL somewhere in a turn of its cycle, and that everywhere it drew,
	 * it drew black and left the alpha alone to say how much of it there is.
	 */
	@Test
	void theShadowSweepDrawsBlackWhereverItDraws() {
		boolean drew = false;

		for (int step = 0; step < 100; step++) {
			int pixel = WynncraftMountArmorEffects.glintPixel(9, 0, 0, 16, 16, 0xFFFFFFFF, step * 0.01F);
			if ((pixel >>> 24) == 0) {
				continue;
			}

			drew = true;
			assertEquals(0, pixel & 0x00FFFFFF, "the shadow sweep drew something that is not black");
		}

		assertTrue(drew, "the shadow sweep drew nothing at all over a whole turn of its cycle");
	}

	/** A clear texel is a hole and is not handed to an effect, which is what keeps the layers apart. */
	@Test
	void aClearTexelIsNeverHandedToAnEffect() {
		try (NativeImage source = new NativeImage(2, 1, true); NativeImage target = new NativeImage(2, 1, true)) {
			source.setPixel(0, 0, CLEAR);
			source.setPixel(1, 0, MARK);

			WynncraftMountArmorEffects.renderBase(source, target, 8, 0.0F);

			assertEquals(CLEAR, target.getPixel(0, 0), "a hole in a layer was filled in by an effect");
			assertNotEquals(MARK, target.getPixel(1, 0), "the effect did not run over the texel beside it");
		}
	}

	/**
	 * The tint family, which is the one group applied to a whole image rather than per texel.
	 * <p>
	 * The value read is a white texel under a blue tint, and what makes it worth reading is that it is
	 * NOT the tint: the highlight is melted back towards white, so a tinted metal keeps its sheen. A
	 * plain multiply would answer the tint's own colour and a test that only checked "the texel
	 * changed" would pass on both.
	 */
	@Test
	void aTintIsABrightnessAndNotAMultiply() {
		assertTrue(WynncraftMountArmorEffects.isStaticTint(15), "the first tint is not a tint");
		assertTrue(WynncraftMountArmorEffects.isStaticTint(24), "the last tint is not a tint");
		assertFalse(WynncraftMountArmorEffects.isStaticTint(14), "the effect before the tints is a tint");
		assertFalse(WynncraftMountArmorEffects.isStaticTint(25), "the shiny after the tints is a tint");

		assertEquals(0x5082E6, WynncraftMountArmorEffects.staticTintRgb(15),
				"the first tint is not the colour the server writes for it");

		// A white texel under that tint: each channel is the tint's own brightness half way melted
		// towards one, which is brighter than the tint and darker than white.
		assertEquals(0xFFA8C1F3, WynncraftMountArmorEffects.applyTint(0xFFFFFFFF, 0x5082E6),
				"the highlight of a tinted texel was not melted back towards white");
	}

	/** And the whole image at once, in place, with a hole left where there was one. */
	@Test
	void aTintOverAWholeImageSkipsTheHolesInIt() {
		try (NativeImage image = new NativeImage(2, 1, true)) {
			image.setPixel(0, 0, CLEAR);
			image.setPixel(1, 0, MARK);

			WynncraftMountArmorEffects.applyStaticTint(image, 15);

			assertEquals(CLEAR, image.getPixel(0, 0), "a tint filled in a hole in the armour");
			assertNotEquals(MARK, image.getPixel(1, 0), "a tint did not reach the texel beside it");
		}

		// And an effect that is not a tint leaves the whole image exactly as it was.
		try (NativeImage image = new NativeImage(1, 1, true)) {
			image.setPixel(0, 0, MARK);
			WynncraftMountArmorEffects.applyStaticTint(image, 3);

			assertEquals(MARK, image.getPixel(0, 0), "an effect that is not a tint tinted an image");
		}
	}

	/**
	 * The clock, which is the whole of what the effects animate on.
	 * <p>
	 * A day is three hundred seconds of effect time, which is the number the server's own rate gives
	 * and is what a reader comparing this with the woven effects' day clock has to be able to see.
	 */
	@Test
	void aDayOfTicksIsThreeHundredSecondsOfEffectTime() {
		assertEquals(0.0F, WynncraftMountArmorEffects.time(0L, 0.0F), 0.0001F,
				"midnight is not the start of the effect clock");
		assertEquals(300.0F, WynncraftMountArmorEffects.time(24000L, 0.0F), 0.0001F,
				"a day is not three hundred seconds of effect time");
		assertEquals(3.75F, WynncraftMountArmorEffects.idleTime(1.0F), 0.0001F,
				"real time does not run at the same rate as a day");
	}
}
