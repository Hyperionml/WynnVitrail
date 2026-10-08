package dev.wynnvitrail;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * What each of Wynncraft's armour effects does to a texel, one texel at a time.
 * <p>
 * <strong>The effect is not in a shader, and that is the whole shape of this.</strong> The server
 * hands out an armour layer whose art already carries the effect - a rainbow weapon's texels are its
 * own greyscale, a glitched one's are its own displaced columns - so what a client has to do is not
 * compute the pattern but re-derive it from the art, in the same arithmetic the server's own
 * resource pack uses, and hand the result to the game as an ordinary texture. That is why every
 * function below takes and answers an ARGB integer: the input and the output are both a texture, and
 * the only thing between them is a loop.
 * <p>
 * <strong>Two families, because they are applied at two moments.</strong> A <em>base</em> effect
 * rewrites the layer before it is drawn, and it is what turns a weapon's art into the colours the
 * effect is made of: a rainbow, an aurora, an aberration, a distortion. A <em>glint</em> effect is
 * drawn over the armour as a second layer, and it is a pattern rather than a colour: a sweep, a
 * plasma, a chrome, a reflection. The two are dispatched by number in {@link #basePixel} and
 * {@link #glintPixel}, and the numbers are the server's - the same ones the glint library in
 * {@link WynncraftGlint} dispatches on for a held item, because the server uses one table for both.
 * <p>
 * <strong>The tint family is a third thing again and is applied to the whole image at once</strong>
 * ({@link #applyStaticTint}), because it is not per-effect arithmetic but one formula with a colour
 * per effect, and it is applied where the server would have applied it rather than per texel in a
 * loop of its own.
 * <p>
 * <strong>Every number here is WynnIris's own</strong> - the colours, the frequencies, the phases,
 * the two hashes - and they are transcribed rather than chosen, because the other end of them is a
 * resource pack on a server that will not change when this does. What IS a judgement is where the
 * seams are: the colour helpers, the sampling helpers and the effect bodies are three groups, and
 * the seams are where a mistake is silent in a different way in each.
 * <p>
 * <strong>The whole library is arithmetic on integers and images, so none of it needs the game</strong>
 * and all of it can be read off-game. {@code WynncraftMountArmorEffectsTest} does that: it paints a
 * source, runs an effect and reads the texels back. What a test cannot say is whether the picture is
 * the one the server meant, and what it can say is what this file is worth saying - that a helper
 * rounds the way the reference rounds, that a sampler clamps where it should clamp and repeats where
 * it should repeat, and that a dispatch sends each number to the effect it names.
 * <p>
 * The clock is the one thing that is not entirely here. The effects animate on the world's own day,
 * scaled so that a day is a little under five minutes of effect time
 * ({@link #time}), and a frame with no world at all animates on real time at the same rate
 * ({@link #idleTime}); both are pure functions of a number the caller has, and neither of them reads
 * a clock.
 */
public final class WynncraftMountArmorEffects {

	/** The luminance weights the eye is built with, which three of the effects below are written in. */
	private static final float LUMA_RED = 0.2126F;
	private static final float LUMA_GREEN = 0.7152F;
	private static final float LUMA_BLUE = 0.0722F;

	/** What a day of ticks is divided by to reach the effect clock, and the rate of the idle one. */
	private static final float TICKS_PER_EFFECT_MINUTE = 80.0F;
	private static final float IDLE_RATE = 3.75F;

	private WynncraftMountArmorEffects() {
	}

	// === The clock ===

	/**
	 * Effect time out of the world's own day.
	 * <p>
	 * A day of twenty-four thousand ticks is three hundred seconds of effect time, which is the
	 * server's own rate and is why a rainbow slides rather than flickers. The partial tick is in it
	 * because the effects are texture rebuilds and a rebuild that stepped twenty times a second would
	 * be a pattern that steps - the same argument the woven effects' day clock carries.
	 */
	public static float time(long worldTime, float partial) {
		return (worldTime + partial) / TICKS_PER_EFFECT_MINUTE;
	}

	/** The same rate over real time, for a frame with no world to take a day from. */
	public static float idleTime(float frameTimeCounter) {
		return frameTimeCounter * IDLE_RATE;
	}

	// === The two whole-image passes ===

	/**
	 * Rebuilds a layer's own art as the effect it carries, writing into a second image.
	 * <p>
	 * A clear texel stays clear and is not passed to the effect at all, which matters for the
	 * effects that sample their neighbours: a transparent edge would otherwise be read as black and
	 * smeared inwards.
	 */
	public static void renderBase(NativeImage source, NativeImage target, int effectId, float time) {
		int width = source.getWidth();
		int height = source.getHeight();

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int pixel = source.getPixel(x, y);

				target.setPixel(x, y, (pixel >>> 24) == 0
						? 0
						: basePixel(source, effectId, x, y, width, height, pixel, time));
			}
		}
	}

	/** And the same for a glint, whose effects are patterns drawn over the armour rather than colours in it. */
	public static void renderGlint(NativeImage source, NativeImage target, int effectId, float time) {
		int width = source.getWidth();
		int height = source.getHeight();

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int pixel = source.getPixel(x, y);

				target.setPixel(x, y, (pixel >>> 24) == 0
						? 0
						: glintPixel(effectId, x, y, width, height, pixel, time));
			}
		}
	}

	/**
	 * One texel of a base effect, dispatched by the effect's number.
	 * <p>
	 * <strong>The coordinates are handed to the effect rather than to a sampler, and they are the
	 * ones the effects are written in.</strong> {@code eX} and {@code eY} are the coordinate taken
	 * off the sheet, which is what the server's own art is laid out in; {@code rX} and {@code rY} are
	 * the same coordinate measured from the middle of a limb and pushed out to eight, which is what
	 * makes an aurora read as a shape on the armour rather than as a pattern on its texels. Both are
	 * computed for every texel even where the effect does not want them, because they are four
	 * arithmetic operations and a branch per effect would be a dispatch inside a dispatch.
	 */
	static int basePixel(NativeImage source, int effectId, int x, int y, int width, int height,
			int pixel, float time) {
		float eX = ((float) x + 0.5F) / width - 1.0F;
		float eY = ((float) y + 0.5F) / height - 1.0F;
		float rX = (eX - 0.5F) * 0.25F + 8.0F;
		float rY = (eY - 0.5F) * 0.25F + 8.0F;

		return switch (effectId) {
			case 2 -> withAlpha(pixel, toByte(((pixel >>> 24) & 0xFF) / 255.0F * 0.5F));
			case 3 -> rainbowBase(pixel, rX, rY, time);
			case 4 -> glitchBase(source, pixel, x, y, width, height, time);
			case 6 -> aberrationBase(source, pixel, x, y, time);
			case 7 -> grayscale(pixel);
			case 8 -> invert(pixel);
			case 10 -> auroraBase(pixel, rX, rY, time);
			case 13 -> distortBase(source, pixel, x, y, width, height, time);
			default -> pixel;
		};
	}

	/**
	 * One texel of a glint, dispatched by the effect's number.
	 * <p>
	 * The default is the shiny sweep and not a passthrough, which is the difference between the two
	 * families in one line: a glint that is not one of the named patterns is a sweep in the colour its
	 * number names ({@link #shinyRgb}), and a base effect that is not one of its own is the art
	 * unchanged.
	 */
	static int glintPixel(int effectId, int x, int y, int width, int height, int pixel, float time) {
		float sourceAlpha = ((pixel >>> 24) & 0xFF) / 255.0F;
		float luma = luma(pixel);
		float u = ((float) x + 0.5F) / width - 1.0F;
		float v = ((float) y + 0.5F) / height - 1.0F;

		return switch (effectId) {
			case 3 -> rgba(hsvToRgb(fract(0.018F * (x + y) - time), 0.7F, 1.0F),
					sourceAlpha * mix(0.20F, 0.45F, luma));
			case 4 -> glitchOverlay(u, v, sourceAlpha, time);
			case 9 -> shadowSweep(u, v, sourceAlpha, time);
			case 10 -> rgba(hsvToRgb(fract((float) Math.sin((u * u + v * v) * 18.0F + time * 4.0F) * 0.2F
					+ time * 0.08F), 0.85F, 1.0F), sourceAlpha * 0.30F);
			case 11 -> reflection(u, v, sourceAlpha, time);
			case 12 -> plasma(u, v, sourceAlpha, time);
			case 14 -> chrome(u, v, sourceAlpha, time);
			default -> shiny(u, v, sourceAlpha, luma, time, shinyRgb(effectId));
		};
	}

	// === The base effects ===

	/** The art in greyscale under a hue that walks, which is what a rainbow weapon's texels are. */
	private static int rainbowBase(int pixel, float rX, float rY, float time) {
		return multiply(grayscale(pixel), hsvToRgb(fract(0.05F * (rX + rY) - time), 0.7F, 1.0F));
	}

	/**
	 * The art torn into bands that slide sideways, which is a glitch.
	 * <p>
	 * The two hashes are the whole of the effect and both are seeded from the band: whether a band is
	 * displaced at all, and by how much. The band is the only place the vertical coordinate is used,
	 * so a glitch is a stack of rows rather than a field, which is what makes it read as a tear.
	 */
	private static int glitchBase(NativeImage source, int pixel, int x, int y, int width, int height,
			float time) {
		if (glintRandom((float) Math.floor(time * 10.0F)) < 0.5F) {
			return pixel;
		}

		float uvX = ((float) x + 0.5F) / width;
		float uvY = ((float) y + 0.5F) / height;
		float size = glintRandom(time);
		float offset = (glintRandom((float) Math.floor(uvY * size) + time) - 0.5F) * 0.015F;
		float shiftedX = uvX + offset;
		float shiftedY = uvY + offset;
		int shifted = sampleUvRepeated(source, shiftedX, shiftedY);
		int red = sampleUvRepeated(source, shiftedX + 0.995F, shiftedY);
		int blue = sampleUvRepeated(source, shiftedX - 0.995F, shiftedY);
		int rgb = (red & 0x00FF0000) | (shifted & 0x0000FF00) | (blue & 0x000000FF);

		return blend(pixel, rgb, ((shifted >>> 24) & 0xFF) / 255.0F);
	}

	/**
	 * The channels pulled apart sideways, which is a lens rather than a tear.
	 * <p>
	 * Red and green walk opposite ways and the blue stays, so the effect is a colour fringe on every
	 * edge. What is kept from the art is the alpha and the blue channel, which is the part that makes
	 * it a misregistration rather than a filter: the picture is still the picture.
	 */
	private static int aberrationBase(NativeImage source, int pixel, int x, int y, float time) {
		int red = sampleClamped(source, x + Math.round((float) Math.sin(time * 500.0F) * 2.0F), y);
		int green = sampleClamped(source, x + Math.round((float) Math.cos(time * 500.0F) * 2.0F), y);
		int alpha = (pixel >>> 24) & 0xFF;

		return (alpha << 24) | (red & 0x00FF0000) | (green & 0x0000FF00) | (pixel & 0x000000FF);
	}

	/**
	 * Ribbons of colour over the art, which is the one base effect that is a shape rather than a
	 * treatment.
	 * <p>
	 * The two distances melted into each other are what round the ribbons' ridges without a step, and
	 * the colour is three sine waves of the melted distance at three frequencies - which is why the
	 * three channels of an aurora never agree and the result reads as light rather than as a hue.
	 */
	private static int auroraBase(int pixel, float rX, float rY, float time) {
		float radius = (float) Math.sqrt(rX * rX + rY * rY);
		float paX = (float) Math.sin(rX * radius);
		float paY = (float) Math.sin(rY * radius);
		float angle = -(float) Math.cos(radius * 5.0F + time * 10.0F);
		float rotatedX = paX * (float) Math.cos(angle) - paY * (float) Math.sin(angle);
		float rotatedY = paX * (float) Math.sin(angle) + paY * (float) Math.cos(angle);
		float expX = (float) Math.exp(-rotatedX * rotatedX);
		float expY = (float) Math.exp(-rotatedY * rotatedY);
		float distA = (float) Math.sqrt(rotatedX * rotatedX + rotatedY * rotatedY);
		float distB = (float) Math.sqrt(expX * expX + expY * expY);
		float d = smoothen(distA, distB, 0.9F);
		int aurora = (toByte((float) Math.sin(d * 4.0F) * 0.5F + 0.5F) << 16)
				| (toByte((float) Math.sin(d * 3.0F) * 0.5F + 0.5F) << 8)
				| toByte((float) Math.sin(d * 2.0F) * 0.5F + 0.5F);

		return blend(pixel, aurora, 0.5F);
	}

	/** A warped read of the art laid back over it, with a beat so that it breathes rather than flows. */
	private static int distortBase(NativeImage source, int pixel, int x, int y, int width, int height,
			float time) {
		float uvX = ((float) x + 0.5F) / width;
		float uvY = ((float) y + 0.5F) / height;
		float beat = 0.3F + 0.7F * Math.abs((float) Math.sin(time * 0.7F));
		float dx = (float) Math.sin(uvY * 40.0F + time * 8.0F) * 0.75F * beat;
		float dy = (float) Math.sin(uvX * 40.0F + time * 10.0F) * 0.75F * beat;
		int warped = sampleBilinearClamped(source, x + dx, y + dy);

		return blend(pixel, warped & 0x00FFFFFF, ((warped >>> 24) & 0xFF) / 255.0F);
	}

	// === The glint effects ===

	/**
	 * The sweep every unnamed glint is, and the number's own colour goes into it.
	 * <p>
	 * The gate is what keeps a sweep from being a stripe that is always in the same place: the cycle
	 * is counted and three unrelated sines of it are added, so a pass of the highlight is sometimes
	 * skipped. Without it every glint would be a metronome.
	 */
	private static int shiny(float u, float v, float sourceAlpha, float luma, float time, int rgb) {
		float sweep = u * 0.3F + v * -0.07F - time * 1.5F;
		float phase = 1.0F - fract(sweep * 0.5F);
		float wave = smoothstep(0.0F, 0.05F, phase) * (1.0F - smoothstep(0.1F, 0.4F, phase));
		float cycle = (float) Math.floor(sweep * 0.5F);
		float gate = (float) (Math.sin(cycle * 1.7F) + Math.sin(cycle * 0.73F) + Math.sin(cycle * 0.31F));
		if (gate < -0.3F) {
			wave = 0.0F;
		}

		return rgba(rgb, sourceAlpha * wave * mix(0.45F, 0.8F, luma));
	}

	/** And the same sweep in black, which is the shadow half of the pair. */
	private static int shadowSweep(float u, float v, float sourceAlpha, float time) {
		float sweep = u * 0.3F + v * -0.07F - time;
		float phase = 1.0F - fract(sweep);
		float wave = smoothstep(0.0F, 0.001F, phase) * (1.0F - smoothstep(0.08F, 0.3F, phase));

		return rgba(0x000000, sourceAlpha * wave * 0.65F);
	}

	/** A bright band crossing the armour on the diagonal, which is light off a polished plate. */
	private static int reflection(float u, float v, float sourceAlpha, float time) {
		float band = (float) Math.sin(((u + v) / 8.0F + time) * 10.0F) * 0.5F + 0.5F;
		float wave = smoothstep(0.7F, 1.0F, band);

		return rgba(0xFFFFFF, sourceAlpha * wave * 0.35F);
	}

	/**
	 * Two waves interfering, which is the plasma and is the one glint that moves in every direction at
	 * once. Its hue walks with the interference rather than with time alone, so the colour follows the
	 * pattern instead of sliding across it.
	 */
	private static int plasma(float u, float v, float sourceAlpha, float time) {
		float puX = u * 4.0F;
		float puY = v * 4.0F;
		float pt = time * 2.0F;
		float po = 0.1F + (float) Math.cos(puY + Math.sin(0.15F - pt)) + pt;
		float pd = 0.9F + (float) Math.sin(puX + Math.cos(0.65F + pt)) - pt;
		float pp = 8.0F * (float) Math.cos(Math.sqrt(puX * puX + puY * puY) + pd)
				* (float) Math.sin(po - pd);
		float wave = (float) Math.sin(pp + time) * 0.5F + 0.5F;

		return rgba(hsvToRgb(fract(wave * 0.4F + time * 0.08F), 0.75F, 1.0F),
				sourceAlpha * mix(0.08F, 0.35F, wave));
	}

	/** Three sines at three phases, which is a colour that has no colour of its own. */
	private static int chrome(float u, float v, float sourceAlpha, float time) {
		float ct = time * 4.0F;
		float r = 0.5F + 0.5F * (float) Math.sin(10.0F * u * 0.3F + ct);
		float g = 0.5F + 0.5F * (float) Math.sin(10.0F * v * 0.3F + ct + 1.0F);
		float b = 0.5F + 0.5F * (float) Math.sin(10.0F * (u + v) * 0.3F + ct + 2.0F);

		return rgba((toByte(r) << 16) | (toByte(g) << 8) | toByte(b), sourceAlpha * 0.32F);
	}

	/**
	 * Rows of green interference with gaps, which is the glitch as a glint rather than as a base.
	 * <p>
	 * A row that is gated out answers a zero rather than a transparent pixel, which is the same thing
	 * to the caller and one comparison cheaper; the band is counted rather than measured so that the
	 * rows are the texture's own and not a fraction of the armour.
	 */
	private static int glitchOverlay(float u, float v, float sourceAlpha, float time) {
		float band = (float) Math.floor((v + 1.0F) * 18.0F);
		float flicker = random(band + (float) Math.floor(time * 10.0F));
		if (flicker < 0.45F) {
			return 0;
		}

		float offset = random(band * 31.0F + time) * 0.5F + 0.5F;

		return rgba(0x32FF66, sourceAlpha * offset * 0.38F);
	}

	/**
	 * The colour one glint's sweep is drawn in, by the effect's number.
	 * <p>
	 * The numbers are the server's, and the gaps in the table are effects that are not sweeps: the
	 * ones that fall through to it are the ones with no pattern of their own, and their colour is
	 * what tells them apart.
	 */
	static int shinyRgb(int effectId) {
		return switch (effectId) {
			case 1 -> 0xFFC864;
			case 5, 11, 13 -> 0xFFFFFF;
			case 6 -> 0x55FFFF;
			case 26 -> 0x55FF55;
			case 27 -> 0xFFFF55;
			case 28 -> 0xFF55FF;
			case 29 -> 0x55FFFF;
			case 30 -> 0xFF5555;
			case 31 -> 0xAA00AA;
			default -> 0xFFFFFF;
		};
	}

	// === The tint family ===

	/**
	 * Whether an effect's number names one of the ten tints, which is the range the woven effects
	 * exclude a self-lit piece from for the same reason: a tint is a colour the server chose.
	 */
	public static boolean isStaticTint(int effectId) {
		return effectId >= 15 && effectId <= 24;
	}

	/**
	 * One tint over a whole layer, in place.
	 * <p>
	 * <strong>The tint is a brightness rather than a multiply, and the two are different pictures.</strong>
	 * A multiply would take the art's own shading with it and leave a flat colour; what this does is
	 * measure how bright each texel is, tint that brightness, and then melt the result back towards
	 * white where the texel was already bright. A highlight stays a highlight and the midtones take
	 * the colour, which is what makes a tinted chestplate read as metal that has been dyed.
	 */
	public static void applyStaticTint(NativeImage image, int effectId) {
		if (!isStaticTint(effectId)) {
			return;
		}

		int tintRgb = staticTintRgb(effectId);

		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int pixel = image.getPixel(x, y);
				if ((pixel >>> 24) == 0) {
					continue;
				}

				image.setPixel(x, y, applyTint(pixel, tintRgb));
			}
		}
	}

	/**
	 * The colour one tint is, by the effect's number.
	 * <p>
	 * The ten are the server's list and the numbers are the same ten the glint library dispatches on,
	 * so a weapon and a chestplate of one effect are one colour. The default is white, which is a
	 * tint that does nothing - and it is reachable only by a caller that asked for a number this
	 * refuses, since {@link #applyStaticTint} answers early for anything outside the range.
	 */
	static int staticTintRgb(int effectId) {
		return switch (effectId) {
			case 15 -> 0x5082E6;
			case 16 -> 0x1EE682;
			case 17 -> 0xEB4646;
			case 18 -> 0x64BEBE;
			case 19 -> 0xFA7814;
			case 20 -> 0x323232;
			case 21 -> 0xFAE6E6;
			case 22 -> 0xFF96C8;
			case 23 -> 0xC83CE6;
			case 24 -> 0xF0F050;
			default -> 0xFFFFFF;
		};
	}

	/** One texel of a tint, which is a brightness the tint's colour is laid over. */
	static int applyTint(int argb, int tintRgb) {
		int alpha = (argb >>> 24) & 0xFF;
		float red = ((argb >>> 16) & 0xFF) / 255.0F;
		float green = ((argb >>> 8) & 0xFF) / 255.0F;
		float blue = (argb & 0xFF) / 255.0F;
		float brightness = (float) Math.pow(LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue,
				0.7F);
		float highlight = smoothstep(0.7F, 1.0F, brightness) * 0.5F;
		float tintRed = ((tintRgb >>> 16) & 0xFF) / 255.0F;
		float tintGreen = ((tintRgb >>> 8) & 0xFF) / 255.0F;
		float tintBlue = (tintRgb & 0xFF) / 255.0F;
		int outRed = toByte(mix(tintRed * brightness, 1.0F, highlight));
		int outGreen = toByte(mix(tintGreen * brightness, 1.0F, highlight));
		int outBlue = toByte(mix(tintBlue * brightness, 1.0F, highlight));

		return (alpha << 24) | (outRed << 16) | (outGreen << 8) | outBlue;
	}

	// === Colours ===

	/** How bright a texel is to the eye, which three of the effects above are judged by. */
	static float luma(int argb) {
		return LUMA_RED * ((argb >>> 16) & 0xFF) / 255.0F + LUMA_GREEN * ((argb >>> 8) & 0xFF) / 255.0F
				+ LUMA_BLUE * (argb & 0xFF) / 255.0F;
	}

	/** A texel's brightness in all three channels, with its alpha kept. */
	static int grayscale(int argb) {
		int gray = toByte(luma(argb));

		return (argb & 0xFF000000) | (gray << 16) | (gray << 8) | gray;
	}

	/** Every channel but the alpha turned over, which is one of the ten static effects. */
	static int invert(int argb) {
		int red = 255 - ((argb >>> 16) & 0xFF);
		int green = 255 - ((argb >>> 8) & 0xFF);
		int blue = 255 - (argb & 0xFF);

		return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
	}

	/** Two colours multiplied channel by channel, which is how a hue is laid under a brightness. */
	static int multiply(int argb, int rgb) {
		int red = ((argb >>> 16) & 0xFF) * ((rgb >>> 16) & 0xFF) / 255;
		int green = ((argb >>> 8) & 0xFF) * ((rgb >>> 8) & 0xFF) / 255;
		int blue = (argb & 0xFF) * (rgb & 0xFF) / 255;

		return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
	}

	/** One colour mixed over another's, with the first one's alpha kept. */
	static int blend(int argb, int rgb, float amount) {
		int red = toByte(mix(((argb >>> 16) & 0xFF) / 255.0F, ((rgb >>> 16) & 0xFF) / 255.0F, amount));
		int green = toByte(mix(((argb >>> 8) & 0xFF) / 255.0F, ((rgb >>> 8) & 0xFF) / 255.0F, amount));
		int blue = toByte(mix((argb & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, amount));

		return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
	}

	/** A texel with its alpha replaced, which is the translucent glint's whole effect. */
	static int withAlpha(int argb, int alpha) {
		return ((alpha & 0xFF) << 24) | (argb & 0x00FFFFFF);
	}

	/**
	 * A colour and an opacity, which is what a glint is: the alpha is quantised first, so a glint that
	 * rounds to clear answers a cleared pixel rather than a colour with no alpha.
	 */
	static int rgba(int rgb, float alpha) {
		int value = toByte(alpha);

		return value == 0 ? 0 : (value << 24) | (rgb & 0x00FFFFFF);
	}

	/**
	 * Hue, saturation and brightness into a colour with no alpha.
	 * <p>
	 * The three channels are one function of the hue shifted by a third each way, which is the
	 * standard construction and is why nothing here branches on which sixth of the wheel the hue is
	 * in.
	 */
	static int hsvToRgb(float h, float s, float v) {
		float red = hsvChannel(h + 1.0F, s, v);
		float green = hsvChannel(h + 2.0F / 3.0F, s, v);
		float blue = hsvChannel(h + 1.0F / 3.0F, s, v);

		return (toByte(red) << 16) | (toByte(green) << 8) | toByte(blue);
	}

	private static float hsvChannel(float h, float s, float v) {
		float p = Math.abs(fract(h) * 6.0F - 3.0F);

		return v * mix(1.0F, clamp(p - 1.0F, 0.0F, 1.0F), s);
	}

	/**
	 * A hash out of one number, in the two spellings the effects use.
	 * <p>
	 * Two and not one because the two ends of the reference disagree: the base effects and the glints
	 * were written against different constants and the patterns depend on it. They are kept apart
	 * rather than unified because a unified hash is a different picture for one of the two families,
	 * and nothing here can tell which.
	 */
	static float random(float seed) {
		return fract((float) Math.sin(seed * 12.9898F) * 43758.547F);
	}

	/** @see #random */
	static float glintRandom(float seed) {
		return fract(57128.836F * (float) Math.sin(seed * 85.15122F));
	}

	// === Arithmetic the effects are written in ===

	/**
	 * A value's fraction, which is what a walk of a hue or a band is measured by.
	 * <p>
	 * {@code Math.floor} and not a cast, because a cast truncates towards nought and a negative
	 * coordinate would then walk the other way - and the coordinates here are negative: both the glint
	 * samplers measure from the middle of an armour piece and half of every piece is behind that.
	 */
	static float fract(float value) {
		return value - (float) Math.floor(value);
	}

	static float smoothstep(float low, float high, float value) {
		float x = clamp((value - low) / (high - low), 0.0F, 1.0F);

		return x * x * (3.0F - 2.0F * x);
	}

	static float mix(float from, float to, float amount) {
		return from * (1.0F - amount) + to * amount;
	}

	/** A fraction of one into a byte, which is where every effect's arithmetic ends. */
	static int toByte(float value) {
		return Math.round(clamp(value, 0.0F, 1.0F) * 255.0F);
	}

	static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * Two distances melted into each other over a band, which is how a ridge is rounded without a step
	 * between the two figures. The same function the glint library's aurora uses, in Java.
	 */
	static float smoothen(float distA, float distB, float amount) {
		float blend = clamp(0.5F + 0.5F * (distB - distA) / amount, 0.0F, 0.5F);

		return mix(distB, distA, blend) - amount * blend * (1.0F - blend);
	}

	// === Sampling ===

	/** The nearest texel, with the coordinate held inside the image. */
	static int sampleClamped(NativeImage source, int x, int y) {
		int clampedX = Math.max(0, Math.min(source.getWidth() - 1, x));
		int clampedY = Math.max(0, Math.min(source.getHeight() - 1, y));

		return source.getPixel(clampedX, clampedY);
	}

	/**
	 * Four texels and their weights, with the coordinate held inside the image.
	 * <p>
	 * The two right and lower neighbours are clamped rather than wrapped, so the edge texel is
	 * weighted in twice at the border - which is what a clamped sampler does and is the whole
	 * difference between this and {@link #sampleBilinearRepeated}.
	 */
	static int sampleBilinearClamped(NativeImage source, float x, float y) {
		float clampedX = clamp(x, 0.0F, source.getWidth() - 1.0F);
		float clampedY = clamp(y, 0.0F, source.getHeight() - 1.0F);
		int x0 = (int) Math.floor(clampedX);
		int y0 = (int) Math.floor(clampedY);
		int x1 = Math.min(source.getWidth() - 1, x0 + 1);
		int y1 = Math.min(source.getHeight() - 1, y0 + 1);
		float tx = clampedX - x0;
		float ty = clampedY - y0;
		int top = lerp(source.getPixel(x0, y0), source.getPixel(x1, y0), tx);
		int bottom = lerp(source.getPixel(x0, y1), source.getPixel(x1, y1), tx);

		return lerp(top, bottom, ty);
	}

	/**
	 * A coordinate in nought to one read out of an image, with both axes repeating.
	 * <p>
	 * The half texel taken off before the read is what puts the coordinate at the CENTRE of a texel
	 * rather than at its corner, which is what a texture coordinate means everywhere else in this
	 * codebase and is what keeps a glitch's displacement from being half a texel out.
	 */
	static int sampleUvRepeated(NativeImage source, float u, float v) {
		float x = fract(u) * source.getWidth() - 0.5F;
		float y = fract(v) * source.getHeight() - 0.5F;

		return sampleBilinearRepeated(source, x, y);
	}

	/** @see #sampleBilinearClamped */
	static int sampleBilinearRepeated(NativeImage source, float x, float y) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		int x1 = x0 + 1;
		int y1 = y0 + 1;
		float tx = x - x0;
		float ty = y - y0;
		int width = source.getWidth();
		int height = source.getHeight();
		int top = lerp(source.getPixel(wrap(x0, width), wrap(y0, height)),
				source.getPixel(wrap(x1, width), wrap(y0, height)), tx);
		int bottom = lerp(source.getPixel(wrap(x0, width), wrap(y1, height)),
				source.getPixel(wrap(x1, width), wrap(y1, height)), tx);

		return lerp(top, bottom, ty);
	}

	/**
	 * A coordinate folded back into an image, in both directions.
	 * <p>
	 * The remainder of a Java division takes the sign of the dividend, so a negative coordinate would
	 * land outside the image; the second step is what makes this a repeat rather than a clamp, and it
	 * is the one that matters because half of every armour piece is at a negative coordinate.
	 */
	static int wrap(int index, int size) {
		int wrapped = index % size;

		return wrapped < 0 ? wrapped + size : wrapped;
	}

	/** Two texels mixed, alpha and all: a bilinear read is a mix of four colours and not of three. */
	static int lerp(int from, int to, float amount) {
		int alpha = toByte(mix(((from >>> 24) & 0xFF) / 255.0F, ((to >>> 24) & 0xFF) / 255.0F, amount));
		int red = toByte(mix(((from >>> 16) & 0xFF) / 255.0F, ((to >>> 16) & 0xFF) / 255.0F, amount));
		int green = toByte(mix(((from >>> 8) & 0xFF) / 255.0F, ((to >>> 8) & 0xFF) / 255.0F, amount));
		int blue = toByte(mix((from & 0xFF) / 255.0F, (to & 0xFF) / 255.0F, amount));

		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}
}
