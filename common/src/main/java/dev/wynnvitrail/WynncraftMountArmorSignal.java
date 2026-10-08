package dev.wynnvitrail;

/**
 * Which of Wynncraft's armour effects a piece of armour carries, read out of the colour the server
 * wrote into it.
 * <p>
 * <strong>The signal is a colour that no dye could have been.</strong> The server writes the effect's
 * number into the red channel, a green of two hundred and fifty-five and a blue of nought, and it
 * writes it in one of the two places a piece of armour can hide a number: the item's own custom model
 * data colours, which a player never chooses, or the dye colour, which the server sets. The green and
 * the blue are what make it a signal rather than a colour - a full green with no blue is not
 * something a dye produces - and the red's range is what makes it a number rather than a coincidence.
 * <p>
 * <strong>The same three bytes the shader reads, read on the CPU.</strong> A weapon's effect rides in
 * its mesh's vertex colour and is decoded in the program ({@link WynncraftSignals}); an armour
 * piece's rides in an item component and is decoded here, because an armour layer's whole texture has
 * to be rebuilt on the CPU before it is drawn. The numbers are the server's one table for both, which
 * is why {@link WynncraftMountArmorEffects} dispatches on the same numbers {@link WynncraftGlint}
 * does.
 * <p>
 * <strong>An effect is spent in one of four ways, and two of the numbers are spent in two of them at
 * once.</strong> A tint is a colour laid over the whole layer; a base effect rewrites the layer's own
 * art; a shader overlay is a number carried through to the program instead of drawn; and everything
 * else is a glint drawn over the armour as a second layer. The two that are more than one are the
 * glitch and the distortion, and they are both at once on purpose: the art is rebuilt on the CPU and
 * the program is told the number as well, because the tear and the warp are the part a texture cannot
 * hold - they move. So the first three questions are not a partition and asking them as one would be
 * the mistake; what IS a partition is a tint against the rest, and the glint question is asked as the
 * complement of the three rather than as a fourth list, which is what keeps a number from being two
 * things at the same time by accident rather than on purpose.
 */
public final class WynncraftMountArmorSignal {

	/**
	 * The colour a layer carries when it carries no effect.
	 * <p>
	 * White, which is what a tint that does nothing is and what every question below answers for "no
	 * effect" - so a caller that forgets to check gets a layer drawn as itself rather than one drawn
	 * as an effect numbered nought.
	 */
	public static final int NO_EFFECT = 0x00FFFFFF;

	/** The largest number the server uses, which is also the top of the red channel's range. */
	public static final int MAX_EFFECT = 32;

	/** The number that means the armour is not drawn at all, which is a state and not an effect. */
	public static final int HIDE_ARMOR = 2;

	/**
	 * The two alphas the overlay's own geometry is stamped with.
	 * <p>
	 * Two and not one because they are told apart by the program that reads them: the outer layers and
	 * the leggings are two passes with two alpha markers, and the shader's decode is what turns a
	 * marker back into "which layer am I". Neither is nought and neither is two hundred and
	 * fifty-five, so neither is a texel the art could have painted transparent or opaque.
	 */
	public static final int OUTER_ALPHA = 252;
	public static final int LEGGINGS_ALPHA = 250;

	private WynncraftMountArmorSignal() {
	}

	/**
	 * The signal inside a colour, or {@link #NO_EFFECT} where there is none.
	 * <p>
	 * The alpha is dropped and never read, which is deliberate: this is called on a custom model
	 * data colour, on a dye colour and on a colour the game made up, and the three of them carry
	 * whatever alpha they like.
	 *
	 * @param colour a colour the server may have written an effect into
	 * @return the red, green and blue of the signal, or {@link #NO_EFFECT}
	 */
	public static int decode(int colour) {
		int rgb = colour & 0x00FFFFFF;
		int red = (rgb >>> 16) & 0xFF;
		int green = (rgb >>> 8) & 0xFF;
		int blue = rgb & 0xFF;

		if (red >= 1 && red <= MAX_EFFECT && green == 255 && blue == 0) {
			return rgb;
		}

		return NO_EFFECT;
	}

	/**
	 * The effect's number out of a signal, or nought where there is none.
	 * <p>
	 * Nought and not minus one, because nought is what every dispatch below answers for "nothing"
	 * and what a switch's default takes.
	 */
	public static int effectOf(int signalRgb) {
		if (signalRgb == NO_EFFECT) {
			return 0;
		}

		int effectId = (signalRgb >>> 16) & 0xFF;

		return effectId >= 1 && effectId <= MAX_EFFECT ? effectId : 0;
	}

	/** Whether the number names one of the ten tints, which are laid over a whole layer at once. */
	public static boolean isStaticTint(int effectId) {
		return effectId >= 15 && effectId <= 24;
	}

	/**
	 * Whether the number means the armour is not drawn.
	 * <p>
	 * A state rather than an effect and it is numbered among them, which is why it is asked before
	 * any of the others: a piece of armour the player has asked to hide must not be handed to a tint
	 * or a glint first.
	 */
	public static boolean isHideArmor(int effectId) {
		return effectId == HIDE_ARMOR;
	}

	/**
	 * Whether the number rewrites the layer's own art.
	 * <p>
	 * The eight are the ones {@link WynncraftMountArmorEffects#renderBase} has a case for, and the two
	 * lists are one decision written twice - so a number added to one and not the other is an effect
	 * that is dispatched and never asked for, or asked for and never dispatched.
	 * <p>
	 * <strong>Two of the eight are also carried through to the program</strong>, which is
	 * {@link #usesShaderOverlay} and is not a mistake: the art is rebuilt here AND the number is
	 * stamped on the geometry, because the tear and the warp are the part of those two effects that
	 * moves and a rebuilt texture cannot move.
	 */
	public static boolean isDynamicBase(int effectId) {
		return effectId == 2 || effectId == 3 || effectId == 4 || effectId == 6 || effectId == 7
				|| effectId == 8 || effectId == 10 || effectId == 13;
	}

	/**
	 * Whether the number is carried through to the program rather than drawn here.
	 * <p>
	 * Four of them are, and what that means is that the layer's art is handed to the shader with the
	 * number stamped into the geometry, and the effect is the program's business - a displacement, a
	 * rainbow, a plasma, a distortion that the CPU would have to rebuild every frame and the GPU can
	 * simply draw.
	 * <p>
	 * <strong>Two of the four are base effects as well</strong> ({@link #isDynamicBase}), and the
	 * reason is in that method: for those two the CPU half and the program half are one effect with a
	 * still part and a moving part, not the same effect drawn twice.
	 */
	public static boolean usesShaderOverlay(int effectId) {
		return effectId == 4 || effectId == 5 || effectId == 12 || effectId == 13;
	}

	/**
	 * Whether the number is a glint drawn over the armour, which is everything left over.
	 * <p>
	 * <strong>Asked as the complement of the other three rather than as a list of its own</strong>,
	 * which is WynnIris's own shape and is the shape that cannot disagree with them: a number is one
	 * of the four or it is nothing, and a fourth list would be a fourth thing to keep in step. The
	 * bound is where it is and not at {@link #MAX_EFFECT} because the last number is a state rather
	 * than a glint, and a glint is a pattern rather than a switch.
	 */
	public static boolean hasAnimatedGlint(int effectId) {
		return effectId >= 1 && effectId <= 31 && !isStaticTint(effectId)
				&& !isDynamicBase(effectId) && !usesShaderOverlay(effectId);
	}

	/**
	 * The colour the overlay's own geometry is stamped with, for one of the two alphas.
	 * <p>
	 * <strong>A colour with an alpha in it, and the pair is the whole protocol.</strong> The geometry
	 * drawn as a third layer over the armour carries this as its vertex colour; the program reads the
	 * alpha back out to know which layer it is looking at, and the shader's decode is the other half of
	 * this function. Its red, green and blue are the "no effect" white, because what the program does
	 * with them is multiply, and a number there would tint every plate.
	 *
	 * @param overlayAlpha {@link #OUTER_ALPHA} or {@link #LEGGINGS_ALPHA}
	 */
	public static int overlaySignal(int overlayAlpha) {
		return ((overlayAlpha & 0xFF) << 24) | NO_EFFECT;
	}
}
