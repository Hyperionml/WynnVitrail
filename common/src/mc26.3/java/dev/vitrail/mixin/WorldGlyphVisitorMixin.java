package dev.vitrail.mixin;

import dev.vitrail.render.PackChain;
import dev.wynnvitrail.WynncraftSettings;
import dev.wynnvitrail.WynncraftTransition;
import dev.wynnvitrail.WynnGlyphMarker;
import dev.wynnvitrail.WynnTextBackground;
import net.minecraft.client.gui.font.TextRenderable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.feature.TextFeatureRenderer$GlyphRenderer")
abstract class WorldGlyphVisitorMixin {
    @Inject(method = "acceptRenderable", at = @At("HEAD"), cancellable = true)
    private void wynn$begin(TextRenderable glyph, CallbackInfo ci) {
        WynnTextBackground.building = false;
        if (glyph instanceof WorldGlyphAccessor instance && instance.wynn$glyph() instanceof WynnGlyphMarker marker
                && marker.wynn$transition() > 0 && WynncraftSettings.effects() && PackChain.drawingPack()) {
            int color = instance.wynn$color();
            int brightest = Math.max((color >>> 16) & 255, Math.max((color >>> 8) & 255, color & 255));
            if (brightest >= 235 && (color >>> 24) != 0) {
                WynncraftTransition.note(marker.wynn$transition(), (color >>> 24) / 255.0F, color & 0xffffff);
            }
            ci.cancel();
            return;
        }
        // Both Font.prepareBackground and PreparedTextBuilder use -0.01 for a background.
        // Strikethrough and underline use +0.01 and must keep their glyph shader.
        WynnTextBackground.building = glyph instanceof TextEffectDepth effect && effect.wynn$depth() == -0.01F;
    }

    @Inject(method = "acceptRenderable", at = @At("RETURN"))
    private void wynn$end(TextRenderable glyph, CallbackInfo ci) {
        WynnTextBackground.building = false;
    }
}
