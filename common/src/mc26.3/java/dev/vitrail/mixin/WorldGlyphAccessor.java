package dev.vitrail.mixin;

import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$GlyphInstance")
public interface WorldGlyphAccessor {
    @Accessor("glyph") BakedSheetGlyph wynn$glyph();
    @Accessor("color") int wynn$color();
}
