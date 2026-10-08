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

	private static volatile int kind;

	private static volatile float progress;

	private static volatile int colour;

	private WynncraftTransition() {
	}

	/**
	 * Takes one transition, from the entity that asked for it.
	 *
	 * @param kind     which of the numbered patterns, one to nineteen
	 * @param progress how far through the transition the screen is, nought to one, read off the
	 *                 entity's own opacity
	 * @param colour   the transition's colour as {@code 0xRRGGBB}
	 */
	public static void note(int kind, float progress, int colour) {
		WynncraftTransition.kind = kind;
		WynncraftTransition.progress = progress;
		WynncraftTransition.colour = colour;
	}

	/**
	 * Whether a transition is waiting to be drawn, taking its answer with it: the one pass that
	 * draws a transition draws each note once, and a note left standing would draw the same
	 * screen over the frames until the next entity replaced it.
	 */
	public static boolean consume() {
		return kind > 0 && progress > 0.001F;
	}

	/** Which of the numbered patterns the waiting transition asked for. */
	public static int kind() {
		return kind;
	}

	/** How far through the transition the screen is, nought to one. */
	public static float progress() {
		return progress;
	}

	/** The waiting transition's colour as {@code 0xRRGGBB}. */
	public static int colour() {
		return colour;
	}

	/**
	 * Empties the note, which the pass does once it has drawn it.
	 * <p>
	 * Public rather than package private, and not because anything outside this package is meant to
	 * raise a transition: the pass that draws it lives in {@code dev.vitrail.render}, one module
	 * across, and it is the one caller this has. The whole class is public for the same reason -
	 * the mixin that catches the entity is in the other module too.
	 */
	public static void taken() {
		kind = 0;
	}
}
