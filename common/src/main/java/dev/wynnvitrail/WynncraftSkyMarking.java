package dev.wynnvitrail;

import dev.vitrail.mixin.access.SpriteContentsAccessor;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * Reads the marking of a Wynncraft sky out of one quad's own art.
 * <p>
 * <strong>The marking is three bytes of one texel and there is nowhere else it could be.</strong>
 * Wynncraft puts a very large textured box around the player for a sky and marks the art with a
 * green of two hundred and fifty-one, an alpha of two hundred and fifty-four and a blue of one to
 * seven, which is its identity. {@link WynncraftSkybox} reads the same three bytes inside the entity
 * program and draws the sky they mean; what a program cannot do is decide anything about the rest of
 * the frame, and the tint and the fog that go over the terrain are a pass of this engine's. That
 * pass needs the identity on the CPU, and the CPU can only see it here: the sprite of a quad is the
 * art, the art is in hand as an image, and a layer being submitted is the one moment the image and
 * the pose that placed it are both available.
 * <p>
 * <strong>An item layer is where it is called from, and the pose is what that buys.</strong> Iris
 * injects at the same place for the same two reasons
 * ({@code ItemStackStateLayerMixin.iris$detectSkyboxSignal}): the quads belong to the layer, so the
 * marking is found there and nowhere else, and the pose read above the layer's own transform is the
 * transform the dome was placed by - which is how the height that tells the sky the player is under
 * from a neighbour's is obtained.
 * <p>
 * <strong>One quad at a time, and the walk over a layer is somebody else's.</strong>
 * {@link dev.vitrail.mixin.ItemStackLayerMixin} is what iterates the layer's quads, because a mixin
 * that shadows the field holds them has to name their container's type and that type is the game's;
 * this class takes one quad and answers whether it marked anything, which is all the walk needs and
 * the whole of what does not move with the game.
 * <p>
 * <strong>The first marking of the layer wins, which is Iris's own rule.</strong> A layer of a
 * skybox is one sprite repeated over the box's faces, and two of them disagreeing is not a case the
 * server produces; the first is as good an answer as the best and cheaper to reach.
 * <p>
 * The reason the walk over a layer is not here is the reason this class is shared at all: 26.2 keeps
 * a layer's quads in a {@code List} and 26.3 in a record that splits the same list three ways, and
 * only the container differs. Nothing about the bytes below does.
 */
public final class WynncraftSkyMarking {

	private WynncraftSkyMarking() {
	}

	/**
	 * Reads one quad's own art and notes the sky it marks, if it marks one.
	 * <p>
	 * Read-only: the image belongs to the sprite and is never closed here, and a quad that marks
	 * nothing costs one comparison of three bytes.
	 *
	 * @param quad   one quad of the layer being submitted, whose sprite is the art to read
	 * @param deltaY the height the layer's own pose placed it at, in blocks against the camera
	 * @return whether a marking was found, which is what lets the caller stop walking the layer
	 */
	public static boolean read(BakedQuad quad, float deltaY) {
		TextureAtlasSprite sprite = quad.materialInfo().sprite();
		if (sprite == null) {
			return false;
		}

		SpriteContents contents = sprite.contents();
		if (contents == null) {
			return false;
		}

		int width = contents.width();
		int height = contents.height();
		if (width < 1 || height < 1) {
			return false;
		}

		// The image the sprite was made from and not the atlas it was stitched into: the centre of
		// the art is wanted, and in an atlas that art sits wherever the pack put it. Mip levels are
		// wrong for the same reason and worse - a small level has averaged the marking bytes with
		// their neighbours and there is nothing left to read.
		NativeImage image = ((SpriteContentsAccessor) contents).vitrail$originalImage();
		if (image == null) {
			return false;
		}

		// A pixel comes back with the alpha in the top byte, which is the order the marking is
		// written in.
		int pixel = image.getPixel(width / 2, height / 2);
		int alpha = (pixel >> 24) & 0xFF;
		int green = (pixel >> 8) & 0xFF;
		int blue = pixel & 0xFF;

		if (green != 251 || alpha != 254 || blue < 1 || blue > 7) {
			return false;
		}

		WynncraftSky.note(blue, deltaY);

		return true;
	}
}
