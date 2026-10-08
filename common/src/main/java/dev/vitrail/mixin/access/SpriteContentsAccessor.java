package dev.vitrail.mixin.access;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Opens the image a sprite was stitched from, so that a texel of it can be read as a number rather
 * than drawn.
 * <p>
 * <strong>A shader pack cannot do this and the game will not say it.</strong> Wynncraft writes the
 * identity of a sky, and the markers for a good deal else, into single texels of its own art, and
 * every one of those markings is invisible to a pack that has not been told to look: a pack reads a
 * texture through a sampler at a coordinate the mesh carries, and by the time it has, the marking is
 * one of the four numbers a fragment is made of. Reading it in a program is what
 * {@link dev.wynnvitrail.WynncraftSkybox} does; reading it as a NUMBER - to decide which pass to run
 * over the whole frame - has to happen before a frame is drawn, and that means the CPU has to see
 * the pixels.
 * <p>
 * <strong>The image the sprite was made from and not the atlas it was stitched into.</strong> What
 * is wanted is the centre texel of one item's own art, and an atlas holds that art at a place that
 * depends on everything else in the pack; the stitched image also carries mip levels, whose small
 * ones have averaged the marking bytes with their neighbours and lost them. Iris reads the same
 * field for the same reason and hands it out under its own accessor
 * ({@code mixin/texture/SpriteContentsAccessor}), so this is that accessor's shape and not a new
 * one.
 * <p>
 * Read-only and never closed: the image belongs to the sprite, and a caller that closed it would
 * take the item's own texture away with it.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {

	@Accessor("originalImage")
	NativeImage vitrail$originalImage();
}
