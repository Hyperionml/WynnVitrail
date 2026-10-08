package dev.vitrail.mixin;

import dev.vitrail.render.PackChain;
import dev.wynnvitrail.WynncraftSettings;
import dev.wynnvitrail.WynncraftTransition;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Catches a Wynncraft transition screen on the entity that asks for it, so the pattern can be
 * painted over the finished picture instead of arriving as a row of boxes.
 * <p>
 * <strong>The server does not send a transition packet.</strong> It draws one: a text display
 * entity carrying one private use character from U+E000 to U+E012 under the font
 * {@code minecraft:screen/transition}, whose style colour is the transition's own and whose text
 * opacity is how far through it the screen is. The resource pack's font turns that character into
 * the moving pattern, and a pack never sees the font - it draws the missing glyph as a box. So the
 * entity is read for its three numbers, held in {@link WynncraftTransition}, and cancelled; the
 * pass at the end of the chain draws what the character was standing for.
 * <p>
 * <strong>Both the numbers and the reading of them are WynnIris's.</strong> The character is the
 * kind and the offset is from U+E000, so U+E000 is kind one and U+E012 is kind nineteen
 * ({@code MixinTextDisplayRenderer.java:112-123}); the opacity is masked to its low byte and
 * divided by 255 by the pipeline on the other side
 * ({@code IrisRenderingPipeline.java:1740}); and the colour comes off the style rather than off
 * the entity, which is where the server wrote it. The visit stops at the first matching character
 * for the same reason: a display carries one.
 * <p>
 * <strong>Cancelled only where a pack is drawing.</strong> With no pack loaded the game's own text
 * pipeline draws the glyph and the resource pack's font turns it into the pattern, which is the
 * transition working and not a box; cancelling there would take the screen away and put nothing
 * back. {@code PackChain.drawingPack} is the same question WynnIris asks of its pipeline manager,
 * and {@link WynncraftSettings#effects()} the switch every other part of the patch is behind.
 * <p>
 * This is the head of {@code submitInner} and not the renderer's own {@code submit}: the state is
 * what carries the text, and it is built by {@code extractRenderState} before this is reached.
 * The other half of WynnIris's injector here - a brightness floor and a dark-scene boost for the
 * letters themselves - is deliberately not taken over, being a general readability effect for
 * every text display rather than a transition.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public abstract class TransitionDisplayMixin {

	/** The font the server writes its transition characters under. */
	@Unique
	private static final Identifier vitrail$transitionFont =
			Identifier.fromNamespaceAndPath("minecraft", "screen/transition");

	/**
	 * Reads a transition out of the entity and stops it being drawn.
	 * <p>
	 * The cheap test first: a display with no private use character in its plain text cannot be a
	 * transition, and this walks every text display in the world. Only then is the component
	 * visited style by style, which is the walk that can see the font.
	 */
	@Inject(method = "submitInner(Lnet/minecraft/client/renderer/entity/state/"
			+ "TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
			at = @At("HEAD"), cancellable = true)
	private void vitrail$catchTransition(TextDisplayEntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, int packedLight, float interpolationProgress,
			CallbackInfo callback) {
		if (!WynncraftSettings.effects() || !PackChain.drawingPack()) {
			return;
		}

		if (state.textRenderState == null) {
			return;
		}

		Display.TextDisplay.TextRenderState text = state.textRenderState;
		Component content = text.text();
		if (content == null || !vitrail$carriesATransitionCharacter(content)) {
			return;
		}

		// The full walk, which is the one that can read a style's font. The character's own style is
		// what carries the colour the server chose, so the two are read together.
		int[] found = new int[] { 0, 0 };
		content.visit((Style style, String run) -> {
			FontDescription font = style.getFont();
			if (!(font instanceof FontDescription.Resource resource)
					|| !vitrail$transitionFont.equals(resource.id())) {
				return Optional.empty();
			}

			for (int index = 0; index < run.length(); index++) {
				char character = run.charAt(index);
				if (character >= WynncraftTransition.FIRST_CHARACTER
						&& character <= WynncraftTransition.LAST_CHARACTER) {
					found[0] = character - WynncraftTransition.FIRST_CHARACTER + 1;
					TextColor colour = style.getColor();
					found[1] = colour == null ? 0x000000 : colour.getValue();
					return Optional.of(Boolean.TRUE);
				}
			}

			return Optional.empty();
		}, Style.EMPTY);

		if (found[0] <= 0) {
			return;
		}

		// The entity's opacity is how far through the transition stands, in the low byte of the
		// interpolated value; the pipeline on the other side divides it back down to nought to one.
		int opacity = text.textOpacity().get(interpolationProgress) & 0xFF;
		WynncraftTransition.note(found[0], opacity / 255.0F, found[1]);
		callback.cancel();
	}

	/** Whether the plain text holds any character that could name a transition. */
	@Unique
	private static boolean vitrail$carriesATransitionCharacter(Component content) {
		String plain = content.getString();

		for (int index = 0; index < plain.length(); index++) {
			char character = plain.charAt(index);
			if (character >= WynncraftTransition.FIRST_CHARACTER
					&& character <= WynncraftTransition.LAST_CHARACTER) {
				return true;
			}
		}

		return false;
	}
}
