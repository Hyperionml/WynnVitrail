package dev.vitrail.mixin.access;

import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.client.model.object.skull.SkullModelBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerHeadSpecialRenderer.class)
public interface MountSkullAccessor {
	@Accessor("modelBase")
	SkullModelBase vitrail$modelBase();
}
