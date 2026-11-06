package dev.vitrail.mixin;

import dev.wynnvitrail.WynncraftMountArmorOverlay;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureManager.class)
abstract class MountTextureReloadMixin {
	@Inject(method = "reload", at = @At("RETURN"))
	private void wynn$forgetArmor(CallbackInfoReturnable<CompletableFuture<Void>> result) {
		result.getReturnValue().thenRun(WynncraftMountArmorOverlay::invalidateResources);
	}
}
