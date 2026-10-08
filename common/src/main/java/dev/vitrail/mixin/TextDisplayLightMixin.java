package dev.vitrail.mixin;

import dev.wynnvitrail.WynncraftSettings;
import dev.wynnvitrail.WynncraftSky;
import dev.wynnvitrail.WynncraftText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lifts the light a text display is drawn at, so that a Wynncraft sky which darkened the scene does
 * not take the letters with it.
 * <p>
 * <strong>The light is the game's and it is computed before any of this runs.</strong> A text display
 * is lit by the block light and the sky light where it stands, and the two are packed into one
 * integer the renderer is handed. Under four of Wynncraft's seven skies the scene those two describe
 * is not the scene on screen - the sky has darkened the ground the sign stands on - so the letters
 * keep the brightness of a world that is no longer there. Nothing inside a shader can fix it: the
 * pack reads that integer as a light, which is what it is, and the mistake was made before the draw.
 * {@link WynncraftText} carries the arithmetic and the targets; this file is what reaches the value.
 * <p>
 * <strong>At the head of {@code submitInner} and not at the renderer's own {@code submit}.</strong>
 * The same place the transition above is caught and for the same reason: the light is an argument of
 * this method, and the state built from the entity is already in hand beside it. Iris modifies the
 * same argument of the same method ({@code MixinTextDisplayRenderer.iris$boostTextLight}), and the
 * pair of arguments this handler takes is its own pair.
 * <p>
 * <strong>This is the second Wynncraft effect to hook this method</strong>, {@link
 * TransitionDisplayMixin} being the first, and the two are separate files because they are separate
 * subjects: one reads the entity and stops it being drawn, the other changes how brightly what is
 * drawn is lit. Both are at the head and neither cancels the other, so the order they run in does
 * not matter - a display the transition claims is not drawn, and a light computed for one that is
 * not drawn is a light nothing reads.
 * <p>
 * <strong>Rain is read here rather than in the state machine</strong>, because it is a fact about
 * the world at this instant and the state machine is deliberately kept to what it can be told a
 * frame at a time. It stops the lift: a storm is dark enough on its own, and letters lifted inside
 * one read as letters glowing. {@code WynncraftSky.textLightBoost} carries that argument and the
 * other three.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public abstract class TextDisplayLightMixin {

	/**
	 * The light the display is about to be drawn at, lifted towards legibility.
	 * <p>
	 * The strength is nought on every frame but a handful of them - it needs one of four skies to be
	 * fading in, and no rain - so the walk over the text's own colours happens only where there is
	 * something to do with it.
	 */
	@ModifyVariable(method = "submitInner(Lnet/minecraft/client/renderer/entity/state/"
			+ "TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
			at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
	private int vitrail$liftTextLight(int packedLight, TextDisplayEntityRenderState state) {
		if (!WynncraftSettings.effects() || state.textRenderState == null) {
			return packedLight;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (level == null) {
			return packedLight;
		}

		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float strength = WynncraftSky.textLightBoost(level.getRainLevel(partial));
		if (strength <= 0.0F) {
			return packedLight;
		}

		Component content = state.textRenderState.text();
		if (content == null) {
			return packedLight;
		}

		return WynncraftText.light(packedLight, content, strength);
	}
}
