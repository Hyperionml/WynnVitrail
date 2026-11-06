package dev.vitrail.mixin;

import dev.wynnvitrail.WynnGlyphMarker;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BakedSheetGlyph.class)
abstract class BakedGlyphMarkerMixin implements WynnGlyphMarker {
    @Unique private int wynn$kind;
    @Override public int wynn$transition() { return wynn$kind; }
    @Override public void wynn$transition(int value) { wynn$kind = value; }
}
