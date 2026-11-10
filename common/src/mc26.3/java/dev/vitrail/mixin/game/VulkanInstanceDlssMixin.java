package dev.vitrail.mixin.game;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import dev.vitrail.render.upscale.GameDlssDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/** NGX instance extensions must be enabled before the game enumerates its physical devices. */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceDlssMixin {
	@Shadow @Final private Set<String> enabledExtensions;

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanInstance;getSupportedInstanceExtensions()Ljava/util/Set;"),
			require = 1)
	private Set<String> vitrail$dlssExtensions(VulkanInstance instance, Operation<Set<String>> original) {
		Set<String> supported = original.call(instance);
		GameDlssDevice.instanceExtensions(supported, enabledExtensions);
		return supported;
	}
}
