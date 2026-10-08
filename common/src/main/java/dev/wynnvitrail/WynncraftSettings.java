package dev.wynnvitrail;

import dev.vitrail.Vitrail;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the Wynncraft patch is switched on and off.
 * <p>
 * <strong>On by default, and the three ways to say otherwise are read in this order.</strong>
 * {@code -Dwynnvitrail.enabled=false} wins over everything and is what the tests drive the state
 * with, one JVM and a call apart; a file {@code wynnvitrail/no-wynncraft} in the game directory is
 * read again at every pack load and is what a player uses; and with neither, the patch runs.
 * <p>
 * <strong>The default is on because the fork exists for one server.</strong> WynnIris applies its
 * adaptation unconditionally, and nothing here can go wrong on a world that never sends a signal:
 * every helper reads the signal out of a colour or a texel and returns nought on one that does not
 * carry it, so an ordinary sword, sheep or skyline is drawn exactly as it was. What the switch
 * costs when it is on and nothing is calling is a few hundred lines of unused functions, which the
 * compiler discards, and two varyings carried across the link, which are written either way.
 * <p>
 * <strong>It was a system property alone until a test run came back with no glint on screen</strong>,
 * which is a failure the switch has by construction: a property has to be named in a launcher's
 * argument list, and nothing in the game says whether it was. The file is the fix, and it is
 * {@code render/RawLocals.java:24-45}'s shape rather than a new one - read at the head of a load,
 * so that the state a translation runs under cannot move under it, with the property as the
 * override that lets one JVM hold both states.
 * <p>
 * <strong>Why it has to be re-read at the load at all.</strong> The answer decides the text of
 * every program of a pack and the text is cached, so a value that moved while a load was under way
 * would leave a program translated for one answer and stored under the other's key. {@link #read}
 * is called beside {@code RawLocals.read} and before a line of any pack is translated.
 * <p>
 * <strong>The file names the state it is not the default of</strong>, which is the one place its
 * shape differs from {@code vitrail/raw-locals}: that default is off and its file turns the pass on,
 * this one is on and its file turns the patch off. What the file is FOR is the same in both cases
 * and is why it is a file rather than a setting on the screen - a reading is taken against another
 * reading, and a weapon's glint is told apart from a pack's own shine by removing the patch and
 * looking again. A screen would be a promise that one of the two is worth choosing.
 */
public final class WynncraftSettings {

	/**
	 * Whether the patch is woven into the pack's programs. Read per call rather than once, because
	 * the tests move it and they share a JVM with the rest.
	 */
	private static final String ENABLED = "wynnvitrail.enabled";

	/**
	 * Whether to leave the decode standing whatever the property says.
	 * <p>
	 * For the tests and for the harness: the decode is cheap and the checks are about the text the
	 * translator writes, which has to be there to be checked whether or not the effects below it
	 * are finished. Off by default, and the patch does not need it.
	 */
	private static final String DECODE = "wynnvitrail.decode";

	/** The file whose presence turns the patch off, under the mod's own directory. */
	static final String OFF_FILE = "no-wynncraft";

	/**
	 * What the last load read, which is the answer where the property says nothing.
	 * <p>
	 * True before any load has been read, so that a harness that never opens a pack - the tests,
	 * the corpus readers - is left with the shipped state rather than with a state nothing chose.
	 */
	private static volatile boolean running = true;

	private WynncraftSettings() {
	}

	/**
	 * Reads the file at the head of a pack load, before one module of it has been translated, since
	 * what it decides is the text every translation of the load emits.
	 * <p>
	 * The property is not consulted here. It is read where it is used, so that a caller which sets
	 * it after a load - a test, and only a test - is still answered with what it asked for.
	 *
	 * @param gameDirectory the installation's own folder, which is where {@code mods/} and
	 *                      {@code shaderpacks/} are and is not ours to choose
	 */
	public static void read(Path gameDirectory) {
		running = !Files.isRegularFile(gameDirectory.resolve(Vitrail.MOD_ID).resolve(OFF_FILE));
		Vitrail.logger().info(state());
	}

	/**
	 * The state as one line, said at every load and whether or not a pack was chosen with it.
	 * <p>
	 * <strong>Said rather than left to be inferred, and the reason is the run that found nothing on
	 * screen.</strong> A patch that is off and a patch that is on and reading the wrong channel
	 * look the same from outside the game and leave the same trace, which is none. Naming the state
	 * makes the two readings apart, and it costs a line beside the ones the module cache and the
	 * local-zeroing pass already print at the same moment.
	 */
	static String state() {
		String how = System.getProperty(ENABLED) != null
				? "-D" + ENABLED
				: running ? "the default" : Vitrail.MOD_ID + "/" + OFF_FILE;
		if (!effects()) {
			return "The Wynncraft patch is OFF, asked for by " + how + ": every signal is left in "
					+ "the pack's own colour and no glint is drawn. Remove the file to go back.";
		}

		return "The Wynncraft patch is ON, from " + how + ": the signal decode, the glint effects "
				+ "and the unlit and self-lit corrections are woven into every entity program. "
				+ Vitrail.MOD_ID + "/" + OFF_FILE + " turns them off, and this line is said either way so "
				+ "that a picture can name the state it was drawn under.";
	}

	/**
	 * Whether the effects run.
	 * <p>
	 * The property where it is set and the load's own reading otherwise, which is what makes one
	 * JVM able to hold both states a call apart and a player able to hold one without an argument.
	 */
	public static boolean effects() {
		String asked = System.getProperty(ENABLED);

		return asked != null ? Boolean.parseBoolean(asked) : running;
	}

	/** Whether to write the decode helpers in, which the effects key off and the tests read. */
	public static boolean decode() {
		return effects() || Boolean.getBoolean(DECODE);
	}

	/**
	 * The whole of the state above as one word, for the translation cache's key.
	 * <p>
	 * <strong>The patch changes the text of a program, so its state is part of what a cached
	 * translation is worth.</strong> {@code GlslTranslator.emissionSwitches} folds every switch that
	 * moves the emitted text into the cache key, and this one moves it more than any of them: a
	 * program translated with the effects on carries the decode, the varying and the wrapper, and
	 * one translated with them off carries none of the three. Serving either to the other is a
	 * wrong picture with no error anywhere to point at it.
	 * <p>
	 * Three answers and not two, because the decode alone is a state the tests hold the translator
	 * in and it too changes the text.
	 */
	public static String key() {
		if (effects()) {
			return "effects";
		}

		return Boolean.getBoolean(DECODE) ? "decode" : "off";
	}
}
