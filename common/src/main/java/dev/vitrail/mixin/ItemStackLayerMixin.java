package dev.vitrail.mixin;

import dev.wynnvitrail.WynncraftSettings;
import dev.wynnvitrail.WynncraftSkyMarking;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the marking of a Wynncraft sky out of an item's own art while the item is being submitted.
 * <p>
 * <strong>The marking is three bytes of one texel and there is nowhere else it could be.</strong>
 * Wynncraft puts a very large textured box around the player for a sky and marks the art with a
 * green of two hundred and fifty-one, an alpha of two hundred and fifty-four and a blue of one to
 * seven, which is its identity. {@link dev.wynnvitrail.WynncraftSkybox} reads the same three bytes
 * inside the entity program and draws the sky they mean; what a program cannot do is decide anything
 * about the rest of the frame, and the tint and fog that go over the terrain are a pass of this
 * engine's. That pass needs the identity on the CPU, and the CPU can only see it here: the sprite of
 * a quad is the art, the art is in hand as an image, and a layer being submitted is the one moment
 * the image and the pose that placed it are both available. {@link WynncraftSkyMarking} is the read
 * itself and carries the rest of that argument; this file is the hook that reaches it.
 * <p>
 * <strong>An item layer and not a whole item.</strong> Iris injects at the same place for the same
 * two reasons ({@code ItemStackStateLayerMixin.iris$detectSkyboxSignal}): the quads belong to the
 * layer, so the marking is found there and nowhere else, and a pose read above the layer's own
 * transform is the transform the dome was placed by - which is how the height that tells the sky the
 * player is under from a neighbour's is obtained. The layer's {@code submit} pushes and applies that
 * transform itself, so the pose read at the head of it is the outer one, which is Iris's reading as
 * well.
 * <p>
 * <strong>The record splits the list three ways and this walk wants the whole one.</strong> A
 * layer's quads arrive as all of them, the opaque ones and the translucent ones; a sky's art is
 * opaque, but choosing between the three would be a rule about the server's art that nothing here
 * has a reason to hold, and the whole list is the list Iris walks for the same marking from the same
 * field.
 * <p>
 * <strong>Nothing is changed and nothing is cancelled.</strong> The injector returns void and lets
 * the submission run: a marking is a fact about the item, not an instruction to the game, and the
 * item is drawn exactly as it was. Iris reaches this method through a {@code @ModifyVariable} that
 * returns its argument unchanged, which is a way of getting at the arguments rather than a use for
 * the injection point; an injector at the head has the same arguments and says so.
 * <p>
 * <strong>The scan is skipped whole when the patch is off</strong>, before a pixel is read: with the
 * effects withheld the pass below is not drawn, so a marking gathered for it would be a number
 * nothing reads, and the switch exists precisely so that a frame can be read with none of this
 * running.
 * <p>
 * What it costs when the patch is on is one walk of an item's quads and one read of a pixel out of
 * each sprite until a marking is found. That is per layer, per frame, over the items the frame
 * submits - the same cost Iris pays for the same reason - and it is a read of an array rather than a
 * transfer, since the image is the one the sprite was built from and lives on the CPU.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public abstract class ItemStackLayerMixin {

	/** The quads this layer was built from, which is where a sprite and its art are reached. */
	@Shadow
	private ItemQuads quads;

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
			at = @At("HEAD"), require = 1)
	private void vitrail$detectSky(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
			int overlayCoords, int outlineColor, CallbackInfo callback) {
		if (!WynncraftSettings.effects()) {
			return;
		}

		ItemQuads layer = this.quads;
		if (layer == null || layer.isEmpty()) {
			return;
		}

		float deltaY = poseStack.last().pose().m31();

		for (BakedQuad quad : layer.all()) {
			if (WynncraftSkyMarking.read(quad, deltaY)) {
				return;
			}
		}
	}
}
