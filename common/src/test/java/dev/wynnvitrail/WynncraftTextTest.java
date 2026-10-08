package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import org.junit.jupiter.api.Test;

/**
 * Drives the lift a text display's light is given off-game, over text written on the spot.
 * <p>
 * <strong>Everything here is arithmetic about two numbers a shader never sees.</strong> The light a
 * display is drawn at is packed by the game before any of this runs, so a lift taken in the wrong
 * nibble is a display that stays dark, and a lift taken into a neighbour's bits is a display that
 * changes colour for no reason - neither of which is an error anywhere, and neither of which a
 * screenshot of a sign would tell from the light being right.
 * <p>
 * The text is a real {@code Component} rather than a colour, because the target depends on the
 * colours of the whole message and a message is what a server sends: a sign with one dark word on it
 * is a different case from a sign that is dark, and the walk that tells them apart is the part of
 * this that a colour parameter would have hidden.
 */
class WynncraftTextTest {

	/** The packed value the game hands the renderer, out of its two nibbles. */
	private static int packed(int block, int sky) {
		return (block << 4) | (sky << 20);
	}

	/**
	 * The luminance of a colour, which is not an average of its channels and is the reason this is
	 * linearised first.
	 */
	@Test
	void aColourIsMeasuredByHowBrightItIsAndNotByWhatItIsMadeOf() {
		assertEquals(1.0F, WynncraftText.relativeLuminance(0xFFFFFF), 0.001F,
				"white is not the brightest a colour gets");
		assertEquals(0.0F, WynncraftText.relativeLuminance(0x000000), 0.001F,
				"black is not the darkest");

		// A green and a blue of the same byte are not the same brightness, which an average of the
		// three channels would have said they were.
		float green = WynncraftText.relativeLuminance(0x00FF00);
		float blue = WynncraftText.relativeLuminance(0x0000FF);
		assertTrue(green > blue * 5.0F,
				"the eye's weighting is missing: green " + green + " against blue " + blue);
	}

	/**
	 * Pale letters are lifted to the level they are already legible at and dark ones to the level a
	 * dark scene stops eating them at, and the packed value comes back with both nibbles moved.
	 */
	@Test
	void darkLettersAreLiftedFurtherThanPaleOnes() {
		Component pale = Component.literal("Warp to Ragni");
		Component dark = Component.literal("Warp to Ragni")
				.withStyle(Style.EMPTY.withColor(0x000000));

		assertEquals(packed(12, 12), WynncraftText.light(packed(5, 3), pale, 1.0F),
				"pale letters were not lifted to the level they are legible at");
		assertEquals(packed(15, 15), WynncraftText.light(packed(5, 3), dark, 1.0F),
				"dark letters were not lifted to the level a dark scene stops eating");
	}

	/**
	 * The lift is taken in part where the sky has only half arrived, and it is a lift: a display
	 * already brighter than the target is handed back exactly as it came in.
	 * <p>
	 * The second half is the one worth reading twice, because a display above the target is the
	 * ordinary case in a lit room and an effect that darkened one would be the same bug pointed the
	 * other way. The identity is asserted on the whole integer rather than on the two nibbles, since
	 * what the caller does with the answer is draw with it.
	 */
	@Test
	void theLiftIsPartialAndNeverDownwards() {
		Component dark = Component.literal("Warp to Ragni")
				.withStyle(Style.EMPTY.withColor(0x000000));

		assertEquals(packed(10, 9), WynncraftText.light(packed(5, 3), dark, 0.5F),
				"half a lift is not half of one");
		assertEquals(packed(5, 3), WynncraftText.light(packed(5, 3), dark, 0.0F),
				"a display was touched by a lift of nothing");

		int bright = packed(15, 15);
		assertEquals(bright, WynncraftText.light(bright, dark, 1.0F),
				"a display already at the top of the range was changed");
	}

	/**
	 * The two levels live in two nibbles with a great deal of the value between them, and everything
	 * else in it is the renderer's business.
	 * <p>
	 * The other bits here are not decoration: the packed value carries more than the two lights, and
	 * a rewrite that took a mask off would change them. Asserted by comparing the whole integer
	 * against one built by hand rather than by testing the bits one at a time, because what must
	 * survive is the integer.
	 */
	@Test
	void everythingInThePackedValueThatIsNotALightIsLeftAlone() {
		int other = (0x5A << 24) | (0xB << 16) | (0xD << 8);
		Component dark = Component.literal("Warp to Ragni")
				.withStyle(Style.EMPTY.withColor(0x000000));

		int lifted = WynncraftText.light(other | packed(5, 3), dark, 1.0F);

		assertEquals(other | packed(15, 15), lifted,
				"a rewrite of the two lights changed something else in the value");
	}
}
