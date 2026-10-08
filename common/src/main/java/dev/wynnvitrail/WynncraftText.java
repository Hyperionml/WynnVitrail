package dev.wynnvitrail;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Optional;

/**
 * How bright the letters of a text display are drawn, which a Wynncraft sky can make too dark to
 * read.
 * <p>
 * <strong>The problem is not the text and it is not the shader.</strong> A text display is lit by
 * the block light and the sky light where it stands, and the game packs both into one integer the
 * renderer is handed. Under four of Wynncraft's seven skies the scene those two describe is not the
 * scene the player sees: the sky has darkened the ground the sign stands on, and the letters keep
 * the brightness the game computed for a world that is no longer on screen. WynnIris answers by
 * lifting the two light levels towards a target that depends on how bright the letters themselves
 * are, and this is that answer ({@code MixinTextDisplayRenderer.java:145-186}).
 * <p>
 * <strong>The target is a walk over the text's own colours, and it has two ends rather than one.</strong>
 * Pale letters on a dark sign are already legible and are lifted to twelve; dark letters are the ones
 * a dark scene swallows, and they are lifted to fifteen. Which of the two a display gets is decided
 * by the luminance of its text as a whole, weighted by how many characters each style covers, and
 * the two ends are melted into each other over the band between them so that a sign part way between
 * the two is lifted part way.
 * <p>
 * <strong>The luminance is a real one and not an average of the three channels.</strong> sRGB is
 * linearised channel by channel first, and the three are then weighted as the eye weights them -
 * which matters here and not in most places this engine does arithmetic, because the colours being
 * judged are text colours a server chose and a green and a blue of the same byte are not the same
 * brightness at all.
 * <p>
 * <strong>Both light levels move together and only upwards.</strong> A display lifted towards a
 * target below where it already stands is left exactly as it was, or a bright room would be darkened
 * by an effect meant to brighten a dark one. And the packed value is only rewritten where it really
 * moved, so that a display the effect does nothing for is handed back the very integer it came in
 * with.
 * <p>
 * <strong>WynnIris's brightness floor is not carried.</strong> Its other half raises both levels to
 * a floor the player chooses, shipped off, and this fork keeps the settings it does carry at the
 * values they ship at - so the floor is nought and the expression is not written. What IS carried is
 * the thing that made the setting worth having, which is the lift above.
 */
public final class WynncraftText {

	/** The light a display of pale letters is lifted to, which is where it is already legible. */
	private static final int LIGHT_FOR_BRIGHT_TEXT = 12;

	/** And the light a display of dark letters is lifted to, which is where a dark scene stops eating it. */
	private static final int LIGHT_FOR_DARK_TEXT = 15;

	/** The luminance band the two ends above are melted into each other over. */
	private static final float DARK_TEXT_LUMINANCE = 0.18F;
	private static final float BRIGHT_TEXT_LUMINANCE = 0.82F;

	/** Where the two light levels sit in the game's packed value, and the mask that clears both. */
	private static final int BLOCK_LIGHT_SHIFT = 4;
	private static final int SKY_LIGHT_SHIFT = 20;
	private static final int LIGHT_NIBBLE = 0xF;
	private static final int LIGHT_BITS = 0x00F000F0;

	/** What a nibble of the packed value holds. */
	private static final int MIN_LIGHT = 0;
	private static final int MAX_LIGHT = 15;

	private WynncraftText() {
	}

	/**
	 * The packed light a display is drawn at, lifted towards legibility by how dark the scene has
	 * become.
	 *
	 * @param packedLight the game's own pair, the block light in bits four to seven and the sky light
	 *                    in bits twenty to twenty-three
	 * @param text        what the display says, whose own colours choose the target
	 * @param strength    how far the lift is taken, nought to one, which is the sky's own fade
	 * @return the value to draw with, which is the one handed in wherever nothing moved
	 */
	public static int light(int packedLight, Component text, float strength) {
		float amount = clamp01(strength);
		if (amount <= 0.0F) {
			return packedLight;
		}

		int block = (packedLight >> BLOCK_LIGHT_SHIFT) & LIGHT_NIBBLE;
		int sky = (packedLight >> SKY_LIGHT_SHIFT) & LIGHT_NIBBLE;

		float darkText = 1.0F - smoothstep(DARK_TEXT_LUMINANCE, BRIGHT_TEXT_LUMINANCE, luminance(text));
		int target = Math.round(LIGHT_FOR_BRIGHT_TEXT
				+ (LIGHT_FOR_DARK_TEXT - LIGHT_FOR_BRIGHT_TEXT) * darkText);

		int liftedBlock = lift(block, target, amount);
		int liftedSky = lift(sky, target, amount);
		if (liftedBlock == block && liftedSky == sky) {
			return packedLight;
		}

		return (packedLight & ~LIGHT_BITS) | (liftedBlock << BLOCK_LIGHT_SHIFT)
				| (liftedSky << SKY_LIGHT_SHIFT);
	}

	/**
	 * How bright a display's letters are, as a whole: each style's own colour, weighted by how many
	 * characters it covers.
	 * <p>
	 * A style with no colour of its own is white, which is what the game draws it as, and the walk
	 * is over the whole component rather than over its first style because a sign is one message and
	 * a server colours part of it.
	 */
	static float luminance(Component text) {
		float[] weighted = new float[1];
		int[] total = new int[1];

		text.visit((Style style, String run) -> {
			int characters = run.codePointCount(0, run.length());
			if (characters <= 0) {
				return Optional.empty();
			}

			TextColor colour = style.getColor();
			weighted[0] += (colour == null ? 1.0F : relativeLuminance(colour.getValue())) * characters;
			total[0] += characters;

			return Optional.empty();
		}, Style.EMPTY);

		return total[0] == 0 ? 1.0F : clamp01(weighted[0] / total[0]);
	}

	/**
	 * The relative luminance of a colour written as bytes, which is the linearised triple weighted
	 * as the eye weights it.
	 */
	static float relativeLuminance(int rgb) {
		float red = linear(((rgb >> 16) & 0xFF) / 255.0F);
		float green = linear(((rgb >> 8) & 0xFF) / 255.0F);
		float blue = linear((rgb & 0xFF) / 255.0F);

		return 0.2126F * red + 0.7152F * green + 0.0722F * blue;
	}

	/**
	 * One channel out of sRGB and into light, which is the transfer function the standard defines
	 * rather than a square: a byte is not a fraction of the light it stands for, and the bend is
	 * steepest where dark colours live, which is exactly the range this class is judging.
	 */
	private static float linear(float channel) {
		if (channel <= 0.04045F) {
			return channel / 12.92F;
		}

		return (float) Math.pow((channel + 0.055F) / 1.055F, 2.4F);
	}

	/** One level towards a target, upwards only, which is what keeps a bright display bright. */
	private static int lift(int current, int target, float amount) {
		if (target <= current) {
			return current;
		}

		int moved = Math.round(current + (target - current) * amount);

		return Math.max(MIN_LIGHT, Math.min(MAX_LIGHT, moved));
	}

	private static float smoothstep(float low, float high, float value) {
		float t = clamp01((value - low) / (high - low));

		return t * t * (3.0F - 2.0F * t);
	}

	private static float clamp01(float value) {
		if (value < 0.0F) {
			return 0.0F;
		}

		return Math.min(value, 1.0F);
	}
}
