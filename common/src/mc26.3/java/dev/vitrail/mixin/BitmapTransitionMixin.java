package dev.vitrail.mixin;

import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import dev.wynnvitrail.WynnGlyphMarker;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph")
abstract class BitmapTransitionMixin {
    @Unique private int wynn$kind;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wynn$read(float scale, @Coerce Object holder, int x, int y, int width, int height,
                           int advance, int ascent, CallbackInfo ci) {
        NativeImage image = ((BitmapImageAccessor) holder).wynn$image();
        if (image.format() == NativeImage.Format.RGBA && width > 0 && height > 0) {
            wynn$kind = WynnGlyphMarker.decode(image.getPixel(x + width / 2, y + height / 2));
        }
    }

    @Inject(method = "bake", at = @At("RETURN"))
    private void wynn$mark(UnbakedGlyph.Stitcher stitcher, CallbackInfoReturnable<BakedGlyph> ci) {
        if (wynn$kind != 0 && ci.getReturnValue() instanceof WynnGlyphMarker marker) marker.wynn$transition(wynn$kind);
    }
}
