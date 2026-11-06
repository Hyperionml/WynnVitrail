package dev.vitrail.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$EffectInstance")
public interface TextEffectDepth {
    @Accessor("depth") float wynn$depth();
}
