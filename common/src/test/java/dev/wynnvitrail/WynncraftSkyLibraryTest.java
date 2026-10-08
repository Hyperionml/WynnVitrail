package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Reads the library the scene pass is compiled against, which is the one thing about that pass that
 * can be read off-game.
 * <p>
 * <strong>The pass is a program of this engine's and the dome is a program of a pack's, and the two
 * have to agree about what a sky looks like.</strong> They agree by being compiled against the same
 * functions, which works only for as long as the library really holds them: an entry moved out of
 * the list, a name renamed in one file and not the other, or the drawing deleted from
 * {@code WynncraftSkybox} altogether would each leave a pass that compiles nowhere - or, worse in the
 * one case where the mistake is subtler, a pass that compiles against a sky that is a shade
 * different from the one behind it and shows as a seam at the horizon.
 * <p>
 * <strong>What is checked is the shape of the text and not its content.</strong> No test here can
 * compile GLSL. What it can do is hold the two ends together: that every function the drawing is
 * built on is present, that the one entry that takes a sampler is not, and that they are in an order
 * the language accepts - which is the whole of what goes wrong when somebody edits one of the two
 * lists without the other.
 */
class WynncraftSkyLibraryTest {

	/**
	 * The three declarations of the lattice, without which the drawing is a file that calls
	 * functions nothing wrote.
	 */
	private static final String[] LATTICE = {
		"float wynnRandom(float seed)", "float wynnNoise(vec2 p)", "float wynnSmoothNoise(vec2 p)"};

	/** And the drawing itself, by one function of each shape it comes in. */
	private static final String[] DRAWING = {
		"float wynnSkyFbm(vec2 p)", "float wynnSkyFbm(vec3 p)",
		"vec3 wynnSkyRotateAxis(vec3 v, vec3 axis, float angle)",
		"float wynnSkyCrystalNoise(vec3 position, float time)",
		"float wynnSkyLightningBolt(vec2 uv, vec2 start, vec2 end, float seed, float width)",
		"float wynnSkyLightningFlash(float time, float seed)",
		"float wynnSkyDistantCloudFlash(float time, float seed)",
		"vec4 wynnSkyApply(int id, float time, vec3 direction)"};

	@Test
	void theLibraryHoldsTheLatticeAndTheDrawingAndBothInOrder() {
		String library = WynncraftPatch.skyLibrary();

		for (String declaration : LATTICE) {
			assertTrue(library.contains(declaration),
					"the library is missing a declaration the drawing calls: " + declaration);
		}

		for (String declaration : DRAWING) {
			assertTrue(library.contains(declaration),
					"the library is missing a sky the pass has to head towards: " + declaration);
		}

		// The order is the whole reason this is a list and not a set: the language wants a function
		// declared before its first call, and the pass is a single unit with nothing above it to
		// declare one.
		assertTrue(library.indexOf("float wynnSmoothNoise(vec2 p)")
						< library.indexOf("float wynnSkyFbm(vec2 p)"),
				"the skies are declared above the noise they are built on");
		assertTrue(library.indexOf("float wynnSkyFbm(vec2 p)")
						< library.indexOf("float wynnSkyFbm(vec3 p)"),
				"the three dimensional fbm is declared above the two dimensional one it calls");
		assertTrue(library.indexOf("float wynnSkyCrystalNoise(")
						< library.indexOf("vec4 wynnSkyApply("),
				"the dispatch is declared above one of the skies it dispatches to");
	}

	/**
	 * The decode is the one entry left out, and it is left out for a reason that would be a compile
	 * failure rather than a seam if it were included: it takes a sampler, and a pass of this engine's
	 * has no pack texture to give it - which sky is overhead was settled on the CPU and arrives as a
	 * number.
	 * <p>
	 * Asserted rather than left to the reader because the failure is silent in the other direction:
	 * a library that grew the decode back would compile perfectly well and read a texel of whatever
	 * happened to be bound, which is a sky identity decided by a texture nothing here chose.
	 */
	@Test
	void theLibraryLeavesOutTheOneDeclarationThatTakesASampler() {
		String library = WynncraftPatch.skyLibrary();

		assertFalse(library.contains("wynnSkyboxSignal"),
				"the library carries the marking's decode, which needs a sampler this pass has not got");
		assertFalse(library.contains("sampler2D"),
				"the library carries a sampler the pass would have to bind something to");
	}
}
