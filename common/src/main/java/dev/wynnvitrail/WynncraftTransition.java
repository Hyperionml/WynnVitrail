package dev.wynnvitrail;

/**
 * A transition screen the server asked for, caught on its way past and held for the pass that
 * draws it.
 * <p>
 * <strong>The server does not send a transition packet.</strong> It draws one: a text display
 * entity, carrying one private use character from U+E000 to U+E012 under the font
 * {@code minecraft:screen/transition}, whose colour is the transition's colour and whose
 * opacity is how far through it the screen is. The resource pack's own font turns the character
 * into the moving pattern; a shader pack never sees the font and draws the character as a box.
 * WynnIris catches the entity before it is drawn ({@code MixinTextDisplayRenderer}, the
 * {@code submitInner} head), takes the three numbers, cancels the entity, and paints the pattern
 * itself over the whole screen ({@code pathways/WynncraftTransitionRenderer}) - which is why the
 * box a pack would draw disappears exactly when the pattern takes its place.
 * <p>
 * <strong>The pattern is nineteen numbered kinds</strong>, one per character the font defines,
 * and their arithmetic is the pack's own; the pass that draws them carries it and this class
 * only hands over which one, how far, and in what colour.
 * <p>
 * Noted during the level's walk of its entities and consumed by the pass at the end of the
 * chain, which is WynnIris's own pairing of the two ({@code ImmediateState.noteTransitionDetection}
 * against {@code consumeTransitionDetection}); {@code volatile} because the two sit in different
 * halves of one frame rather than different threads, and the note is read exactly once.
 */
public final class WynncraftTransition {

	/** The font the server writes its transition characters under. */
	public static final String FONT = "minecraft:screen/transition";

	/** The first character that names a transition, which names the first kind. */
	public static final char FIRST_CHARACTER = '\uE000';

	/** The last character that names a transition, which names the nineteenth kind. */
	public static final char LAST_CHARACTER = '\uE012';

	public record Detection(int kind, float progress, int colour) {}

	private static final java.util.concurrent.atomic.AtomicReference<Detection> pending =
			new java.util.concurrent.atomic.AtomicReference<>();

	private WynncraftTransition() {}

	public static void note(int kind, float progress, int colour) {
		if (kind >= 1 && kind <= 19) {
			pending.set(new Detection(kind, progress, colour));
		}
	}

	public static Detection consume() {
		return pending.getAndSet(null);
	}
}
