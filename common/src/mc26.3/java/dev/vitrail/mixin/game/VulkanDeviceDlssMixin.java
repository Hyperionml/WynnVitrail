package dev.vitrail.mixin.game;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.vitrail.Vitrail;
import dev.vitrail.render.upscale.GameDlssDevice;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceDlssMixin implements GameDlssDevice.Owner {
	@Unique private @Nullable GameDlssDevice vitrail$dlss;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$openDlss(CallbackInfo callback) {
		vitrail$dlss = GameDlssDevice.attach((VulkanDevice) (Object) this);
	}

	@Override
	public @Nullable GameDlssDevice vitrail$dlssDevice() {
		return vitrail$dlss;
	}

	@Inject(method = "close", at = @At("HEAD"), require = 1)
	private void vitrail$closeDlss(CallbackInfo callback) {
		if (vitrail$dlss == null) return;
		try {
			vitrail$dlss.close();
		} catch (RuntimeException | LinkageError e) {
			// In particular, a lost device must still reach the game's own destruction path.
			Vitrail.logger().error("DLSS cleanup failed during Vulkan device shutdown", e);
		} finally {
			vitrail$dlss = null;
		}
	}
}
