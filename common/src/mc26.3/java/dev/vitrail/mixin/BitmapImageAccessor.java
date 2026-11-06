package dev.vitrail.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$ImageDataHolder")
public interface BitmapImageAccessor {
    @Accessor("image") NativeImage wynn$image();
}
