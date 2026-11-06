package dev.vitrail.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.wynnvitrail.WynnOption;
import dev.wynnvitrail.WynncraftMountArmorOverlay;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(EquipmentLayerRenderer.class)
abstract class EquipmentSignalMixin {
	@ModifyArg(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
			at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"), index = 6)
	private int wynn$captureSignal(int color, @Local(argsOnly = true) ItemStack stack) {
		if (WynnOption.MOUNT.enabled()) WynncraftMountArmorOverlay.cacheRenderedArmorSignal(stack, color);
		return color;
	}
}
