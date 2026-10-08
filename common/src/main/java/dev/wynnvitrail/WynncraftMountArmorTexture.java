package dev.wynnvitrail;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Where each face of a piece of armour has to land in the skin layout a mount's body is textured
 * with.
 * <p>
 * <strong>An armour texture is not a skin and the two do not share a layout.</strong> What the game
 * hands out for a chestplate is one arm and one leg - the right ones - because a player's own model
 * mirrors them, and the helmet's faces sit at the left edge of the sheet where a skin puts the outer
 * layer. A mount's body is not a mirrored model: the server sent four separate limbs as four
 * separate quads, so the left arm and the left leg need their own texels, and the only ones there are
 * to give them are the right limb's, copied across and flipped - which is exactly how the client
 * draws a left limb from a skin, and is why the two arrays of rectangles below differ by more than
 * an offset.
 * <p>
 * <strong>Everything here is a copy between two images, and nothing here needs the game.</strong>
 * That is why it is its own class: what it does is decided entirely by the tables below, the tables
 * are the part somebody has to be able to check against a skin, and a bug in one of them is a plate
 * on a mount's face rather than an exception anywhere. {@code WynncraftMountArmorOverlay} copies the
 * same tables in WynnIris, under the same names and with the same numbers.
 * <p>
 * <strong>The six faces are in the order the game lays a box out in</strong> - top, bottom, right,
 * front, left, back - and each table is read in that order. A table whose faces were reordered would
 * put a chestplate's front on the top of a body, which draws.
 * <p>
 * <strong>Two things about the copy are not a plain blit.</strong> A face's destination is often
 * smaller than its source, because a slim arm's plate is three texels wide where a wide arm's is
 * four, and the scale is taken by stepping through the destination and reading the source at the
 * proportion - with the outer edge kept rather than dropped for the arms, which is what
 * {@code SLIM_ARM_KEEP_OUTER_X} says. And a transparent source texel copies nothing at all rather
 * than copying clear over what is there, so an armour layer with a hole in it shows the layer
 * beneath.
 */
public final class WynncraftMountArmorTexture {

	/**
	 * One face of a box: where it starts and how big it is.
	 * <p>
	 * A rectangle rather than an offset and a size in two arrays, because the six of them are written
	 * out eight times below and a reader comparing two tables has to be able to compare them at a
	 * glance.
	 */
	public record FaceRect(int x, int y, int width, int height) {

		/** The same face, moved. Used for the offsets the model's own layout asks for. */
		public FaceRect offset(int dx, int dy) {
			return new FaceRect(this.x + dx, this.y + dy, this.width, this.height);
		}
	}

	/** Which piece of armour is being laid out, which is which faces are copied and where. */
	public enum Piece {

		/** The helmet, whose faces are already where a skin keeps the outer layer's. */
		OUTER_HEAD,

		/** The chestplate and the two arms, which is where a slim body diverges from a wide one. */
		OUTER_CHEST,

		/** The boots, which are the legs' own outer layer. */
		OUTER_FEET,

		/** The leggings, which are the body and both legs, and stand off least. */
		LEGGINGS
	}

	/** How wide the body the armour is being laid out for is. */
	public enum Body {

		/** Four texels of arm, which is what a skin's own arm is. */
		WIDE,

		/** Three, which is the slim skin's arm and a narrower plate to go with it. */
		SLIM
	}

	/** The colour that means "leave the texels alone", which is what a layer with no dye answers. */
	public static final int NO_COLOUR = -1;

	private WynncraftMountArmorTexture() {
	}

	/**
	 * Lays one piece of armour out over a target image, in the order the game draws the pieces in.
	 *
	 * @param source      the armour layer's own texture, which is a sheet the game handed over
	 * @param target      the image to lay it out in, which is the one the mount's quads are textured
	 *                    with; read and written, and left as it was wherever nothing was copied
	 * @param layerColour the dye a layer is drawn in, or {@link #NO_COLOUR} where it is not dyed
	 * @param piece       which piece this layer is
	 * @param body        how wide the body under it is, which the arms and only the arms care about
	 */
	public static void lay(NativeImage source, NativeImage target, int layerColour, Piece piece,
			Body body) {
		switch (piece) {
			case OUTER_HEAD -> copyFaces(source, target, layerColour, SOURCE_HEAD, TARGET_HEAD, 32, 0,
					NO_MIRROR, NO_MIRROR, NO_MIRROR);
			case OUTER_CHEST -> layChest(source, target, layerColour, body);
			case OUTER_FEET -> {
				copyFaces(source, target, layerColour, SOURCE_RIGHT_LEG, TARGET_RIGHT_LEG, 0, 16,
						NO_MIRROR, NO_MIRROR, NO_MIRROR);
				copyFaces(source, target, layerColour, SOURCE_RIGHT_LEG_FOR_LEFT_LIMB, TARGET_LEFT_LEG,
						-16, 0, LEFT_LEG_BOOT_MIRROR_X, NO_MIRROR, NO_MIRROR);
			}
			case LEGGINGS -> {
				copyFaces(source, target, layerColour, SOURCE_BODY, TARGET_BODY, 0, 16, NO_MIRROR,
						NO_MIRROR, NO_MIRROR);
				copyFaces(source, target, layerColour, SOURCE_RIGHT_LEG, TARGET_RIGHT_LEG, 0, 16,
						NO_MIRROR, NO_MIRROR, NO_MIRROR);
				copyFaces(source, target, layerColour, SOURCE_RIGHT_LEG_FOR_LEFT_LIMB, TARGET_LEFT_LEG,
						-16, 0, NO_MIRROR, NO_MIRROR, NO_MIRROR);
			}
		}
	}

	/**
	 * The chestpiece, whose arms are the one place a body's width changes the layout.
	 * <p>
	 * A slim arm's plate is a texel narrower and its faces take the OUTER edge of the wide arm's
	 * rather than the inner one, which is what {@code SLIM_ARM_KEEP_OUTER_X} says and is the whole
	 * reason the third mirror table exists: a slim arm's own skin texels are the three nearest the
	 * outside seam, so a plate built from the inner three would be a texel out along the front and
	 * the back.
	 */
	private static void layChest(NativeImage source, NativeImage target, int layerColour, Body body) {
		copyFaces(source, target, layerColour, SOURCE_BODY, TARGET_BODY, 0, 16, NO_MIRROR, NO_MIRROR,
				NO_MIRROR);

		if (body == Body.SLIM) {
			copyFaces(source, target, layerColour, SOURCE_RIGHT_ARM, TARGET_RIGHT_ARM_SLIM, 0, 16,
					NO_MIRROR, NO_MIRROR, KEEP_OUTER_X);
			copyFaces(source, target, layerColour, SOURCE_RIGHT_ARM_FOR_LEFT_LIMB, TARGET_LEFT_ARM_SLIM,
					16, 0, LEFT_ARM_MIRROR_X, LEFT_ARM_MIRROR_Y, KEEP_OUTER_X);
			return;
		}

		copyFaces(source, target, layerColour, SOURCE_RIGHT_ARM, TARGET_RIGHT_ARM_WIDE, 0, 16,
				NO_MIRROR, NO_MIRROR, NO_MIRROR);
		copyFaces(source, target, layerColour, SOURCE_RIGHT_ARM_FOR_LEFT_LIMB, TARGET_LEFT_ARM_WIDE, 16,
				0, LEFT_ARM_MIRROR_X, LEFT_ARM_MIRROR_Y, NO_MIRROR);
	}

	/**
	 * Copies the six faces of one box, in the order the tables are written in.
	 *
	 * @param offsetX how far the piece's own layout has to move to reach the skin's
	 * @param offsetY the same downwards
	 * @param mirrorX which faces are read back to front, one flag per face
	 * @param mirrorY and which are read bottom to top
	 * @param keepOuterX which faces take the outer edge of a source wider than their destination
	 */
	static void copyFaces(NativeImage source, NativeImage target, int layerColour,
			FaceRect[] sourceFaces, FaceRect[] targetFaces, int offsetX, int offsetY, boolean[] mirrorX,
			boolean[] mirrorY, boolean[] keepOuterX) {
		for (int face = 0; face < sourceFaces.length; face++) {
			copyScaledRect(source, target, sourceFaces[face], targetFaces[face].offset(offsetX, offsetY),
					layerColour, mirrorX[face], mirrorY[face], keepOuterX[face]);
		}
	}

	/**
	 * One face, scaled by proportion where the two rectangles are different sizes.
	 * <p>
	 * <strong>The scale is taken from the destination</strong> - each destination texel asks which
	 * source texel it stands for - rather than the other way round, which is what keeps a shrunk face
	 * from leaving holes: every destination texel is written by exactly one step of the loop, where a
	 * walk over the source would skip one wherever the ratio is not a whole number.
	 */
	static void copyScaledRect(NativeImage source, NativeImage target, FaceRect sourceRect,
			FaceRect targetRect, int layerColour, boolean mirrorX, boolean mirrorY,
			boolean keepOuterX) {
		for (int y = 0; y < targetRect.height(); y++) {
			for (int x = 0; x < targetRect.width(); x++) {
				int sourceOffsetX = x * sourceRect.width() / targetRect.width();
				int sourceOffsetY = y * sourceRect.height() / targetRect.height();

				if (keepOuterX && !mirrorX && sourceRect.width() > targetRect.width()) {
					sourceOffsetX += sourceRect.width() - targetRect.width();
				}

				int sourceX = sourceRect.x()
						+ (mirrorX ? sourceRect.width() - 1 - sourceOffsetX : sourceOffsetX);
				int sourceY = sourceRect.y()
						+ (mirrorY ? sourceRect.height() - 1 - sourceOffsetY : sourceOffsetY);
				int targetX = targetRect.x() + x;
				int targetY = targetRect.y() + y;

				if (!holds(source, sourceX, sourceY) || !holds(target, targetX, targetY)) {
					continue;
				}

				int pixel = source.getPixel(sourceX, sourceY);

				// A hole in a layer is a hole and not a clear texel: copying it would erase whatever
				// the layer beneath had already put there, and the layers are laid down in the order
				// the game draws them.
				if (((pixel >>> 24) & 0xFF) == 0) {
					continue;
				}

				alphaComposite(target, targetX, targetY, tint(pixel, layerColour));
			}
		}
	}

	private static boolean holds(NativeImage image, int x, int y) {
		return x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight();
	}

	/**
	 * A texel in the dye a layer is drawn with, or the texel itself where there is no dye.
	 * <p>
	 * A multiply and not a mix, and in bytes rather than in fractions: the game's own dyed armour is
	 * a multiply of the two, and an armour texture is drawn the way the sheet holds it. The alpha is
	 * carried through untouched, because the dye says what colour a plate is and not how opaque.
	 *
	 * @param argb  the texel
	 * @param colour the layer's dye, or {@link #NO_COLOUR}
	 */
	static int tint(int argb, int colour) {
		if (colour == NO_COLOUR) {
			return argb;
		}

		int alpha = (argb >>> 24) & 0xFF;
		int red = (argb >>> 16) & 0xFF;
		int green = (argb >>> 8) & 0xFF;
		int blue = argb & 0xFF;
		int dyeRed = (colour >>> 16) & 0xFF;
		int dyeGreen = (colour >>> 8) & 0xFF;
		int dyeBlue = colour & 0xFF;

		return (alpha << 24) | ((red * dyeRed / 255) << 16) | ((green * dyeGreen / 255) << 8)
				| (blue * dyeBlue / 255);
	}

	/**
	 * One texel over another, which is what lets two layers of armour share a mount.
	 * <p>
	 * The layers are laid down in the order the game draws them and each is drawn over what is
	 * already there, so what a player sees is the last layer wherever it is opaque and a
	 * proportional mix of the two wherever it is not. The arithmetic is the standard source-over
	 * formula rather than a half-and-half average, because a plate is mostly opaque and mostly
	 * transparent and an average of the two would show the layer beneath through every solid texel.
	 */
	static void alphaComposite(NativeImage target, int x, int y, int source) {
		int destination = target.getPixel(x, y);
		int sourceAlpha = (source >>> 24) & 0xFF;
		int destinationAlpha = (destination >>> 24) & 0xFF;
		int outAlpha = sourceAlpha + destinationAlpha * (255 - sourceAlpha) / 255;
		if (outAlpha <= 0) {
			target.setPixel(x, y, 0);
			return;
		}

		int sourceRed = (source >>> 16) & 0xFF;
		int sourceGreen = (source >>> 8) & 0xFF;
		int sourceBlue = source & 0xFF;
		int destinationRed = (destination >>> 16) & 0xFF;
		int destinationGreen = (destination >>> 8) & 0xFF;
		int destinationBlue = destination & 0xFF;
		int outRed = (sourceRed * sourceAlpha
				+ (destinationRed * destinationAlpha * (255 - sourceAlpha)) / 255) / outAlpha;
		int outGreen = (sourceGreen * sourceAlpha
				+ (destinationGreen * destinationAlpha * (255 - sourceAlpha)) / 255) / outAlpha;
		int outBlue = (sourceBlue * sourceAlpha
				+ (destinationBlue * destinationAlpha * (255 - sourceAlpha)) / 255) / outAlpha;

		target.setPixel(x, y, (outAlpha << 24) | (outRed << 16) | (outGreen << 8) | outBlue);
	}

	private static FaceRect[] faces(FaceRect top, FaceRect bottom, FaceRect right, FaceRect front,
			FaceRect left, FaceRect back) {
		return new FaceRect[] {top, bottom, right, front, left, back};
	}

	private static boolean[] mirrors(boolean top, boolean bottom, boolean right, boolean front,
			boolean left, boolean back) {
		return new boolean[] {top, bottom, right, front, left, back};
	}

	private static FaceRect face(int x, int y, int width, int height) {
		return new FaceRect(x, y, width, height);
	}

	// The armour sheet's own layout: the right arm and the right leg only, because a player's model
	// mirrors them, and the helmet at the sheet's left edge.
	private static final FaceRect[] SOURCE_HEAD = faces(face(8, 0, 8, 8), face(16, 0, 8, 8),
			face(0, 8, 8, 8), face(8, 8, 8, 8), face(16, 8, 8, 8), face(24, 8, 8, 8));
	private static final FaceRect[] SOURCE_BODY = faces(face(20, 16, 8, 4), face(28, 16, 8, 4),
			face(16, 20, 4, 12), face(20, 20, 8, 12), face(28, 20, 4, 12), face(32, 20, 8, 12));
	private static final FaceRect[] SOURCE_RIGHT_ARM = faces(face(44, 16, 4, 4), face(48, 16, 4, 4),
			face(40, 20, 4, 12), face(44, 20, 4, 12), face(48, 20, 4, 12), face(52, 20, 4, 12));
	private static final FaceRect[] SOURCE_RIGHT_LEG = faces(face(4, 16, 4, 4), face(8, 16, 4, 4),
			face(0, 20, 4, 12), face(4, 20, 4, 12), face(8, 20, 4, 12), face(12, 20, 4, 12));

	/**
	 * The right arm's faces read in the order a LEFT arm's are, which is the mirror and not an
	 * offset: the two side faces swap places between the sheets, so a left arm built from the right
	 * one has to take the right sheet's left face for its right face and the other way round.
	 */
	private static final FaceRect[] SOURCE_RIGHT_ARM_FOR_LEFT_LIMB = faces(face(44, 16, 4, 4),
			face(48, 16, 4, 4), face(48, 20, 4, 12), face(44, 20, 4, 12), face(40, 20, 4, 12),
			face(52, 20, 4, 12));

	/** And the same swap for a leg, which is the one the boots do not mirror as well. */
	private static final FaceRect[] SOURCE_RIGHT_LEG_FOR_LEFT_LIMB = faces(face(4, 16, 4, 4),
			face(8, 16, 4, 4), face(8, 20, 4, 12), face(4, 20, 4, 12), face(0, 20, 4, 12),
			face(12, 20, 4, 12));

	// And where each piece has to land in the skin layout a mount's body is drawn with.
	private static final FaceRect[] TARGET_HEAD = faces(face(8, 0, 8, 8), face(16, 0, 8, 8),
			face(0, 8, 8, 8), face(8, 8, 8, 8), face(16, 8, 8, 8), face(24, 8, 8, 8));
	private static final FaceRect[] TARGET_BODY = faces(face(20, 16, 8, 4), face(28, 16, 8, 4),
			face(16, 20, 4, 12), face(20, 20, 8, 12), face(28, 20, 4, 12), face(32, 20, 8, 12));
	private static final FaceRect[] TARGET_RIGHT_ARM_WIDE = faces(face(44, 16, 4, 4),
			face(48, 16, 4, 4), face(40, 20, 4, 12), face(44, 20, 4, 12), face(48, 20, 4, 12),
			face(52, 20, 4, 12));
	private static final FaceRect[] TARGET_LEFT_ARM_WIDE = faces(face(36, 48, 4, 4), face(40, 48, 4, 4),
			face(32, 52, 4, 12), face(36, 52, 4, 12), face(40, 52, 4, 12), face(44, 52, 4, 12));
	private static final FaceRect[] TARGET_RIGHT_ARM_SLIM = faces(face(44, 16, 3, 4), face(47, 16, 3, 4),
			face(40, 20, 4, 12), face(44, 20, 3, 12), face(47, 20, 4, 12), face(51, 20, 3, 12));
	private static final FaceRect[] TARGET_LEFT_ARM_SLIM = faces(face(36, 48, 3, 4), face(39, 48, 3, 4),
			face(32, 52, 4, 12), face(36, 52, 3, 12), face(39, 52, 4, 12), face(43, 52, 3, 12));
	private static final FaceRect[] TARGET_RIGHT_LEG = faces(face(4, 16, 4, 4), face(8, 16, 4, 4),
			face(0, 20, 4, 12), face(4, 20, 4, 12), face(8, 20, 4, 12), face(12, 20, 4, 12));
	private static final FaceRect[] TARGET_LEFT_LEG = faces(face(20, 48, 4, 4), face(24, 48, 4, 4),
			face(16, 52, 4, 12), face(20, 52, 4, 12), face(24, 52, 4, 12), face(28, 52, 4, 12));

	private static final boolean[] NO_MIRROR = mirrors(false, false, false, false, false, false);
	private static final boolean[] LEFT_ARM_MIRROR_X = mirrors(true, false, false, true, false, true);
	private static final boolean[] LEFT_ARM_MIRROR_Y = mirrors(true, false, false, false, false, false);
	private static final boolean[] LEFT_LEG_BOOT_MIRROR_X = mirrors(false, false, true, false, true,
			false);
	private static final boolean[] KEEP_OUTER_X = mirrors(true, true, false, true, false, true);
}
