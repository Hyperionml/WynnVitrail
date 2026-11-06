package dev.wynnvitrail;

/** Transition encoded by the centre texel of a baked bitmap glyph. */
public interface WynnGlyphMarker {
    int wynn$transition();
    void wynn$transition(int value);

    static int decode(int argb) {
        return (argb >>> 24) == 253 ? Math.min(argb & 255, 19) : 0;
    }
}
