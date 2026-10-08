package dev.wynnvitrail;

/**
 * Where the Wynncraft patch is switched on and off.
 * <p>
 * <strong>It is off by default, and that is a statement about the state of the port rather than
 * about the feature.</strong> What is implemented so far is the signal decode, the neutralisation
 * that hides a signal from the pack's own colour reads, and the translucency reduction, which
 * together are the foundation every effect needs. The nineteen glint effects, the skybox, the
 * transitions and the regional pack switching are not here yet, so a glint is decoded and then
 * does nothing: switched on, a Wynncraft weapon loses its signal tint and gains no sweep, where
 * WynnIris gives it both. The picture is then right about every ordinary item on screen and wrong
 * about the one thing this mod is for, which is why the switch is not on the options screen yet.
 * <p>
 * The switch is a system property rather than a setting on the screen because a setting on the
 * screen is a promise that it is worth turning. It becomes one when the effects land.
 */
public final class WynncraftSettings {

	/** Whether the patch is woven into the pack's programs at all. */
	private static final String ENABLED = "wynnvitrail.enabled";

	/**
	 * Whether to leave the decode standing whatever the property says.
	 * <p>
	 * For the tests and for the harness: the decode is cheap and the checks are about the text the
	 * translator writes, which has to be there to be checked whether or not the effects below it
	 * are finished.
	 */
	private static final String DECODE = "wynnvitrail.decode";

	private WynncraftSettings() {
	}

	/**
	 * Whether the effects run. Read once and cached, because the answer decides the text of every
	 * program of a pack and the text is cached: a value that moved under a running game would leave
	 * a program translated for one answer and drawn under another.
	 */
	public static boolean effects() {
		return Boolean.getBoolean(ENABLED);
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
	 * in and it too changes the text. Left to the caller to fold in, and {@link #effects} says why
	 * the value is not cached here: a property that moved under a running game would be a program
	 * translated for one answer and drawn under another.
	 */
	public static String key() {
		if (effects()) {
			return "effects";
		}

		return Boolean.getBoolean(DECODE) ? "decode" : "off";
	}
}
