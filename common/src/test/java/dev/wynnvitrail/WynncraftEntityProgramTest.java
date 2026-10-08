package dev.wynnvitrail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;

/**
 * Drives the translator off-game over an entity program written on the spot, and checks the text
 * the Wynncraft patch is supposed to leave in it.
 * <p>
 * <strong>This is the check the port cannot do by building.</strong> The patch weaves code into a
 * pack's own file and the compiler accepts every line of it: a decode that read the wrong channel,
 * a varying declared on one side and not the other, or a wrapper nobody wrote would each build
 * cleanly and draw a pack that never showed a glint. What can be read is the text, and the text is
 * what is read here.
 * <p>
 * The pack is deliberately one that mentions none of it. Its fragment stage writes a colour and its
 * vertex stage places a vertex, and the whole of the patch - the varying, the copy that fills it,
 * the decode and the application - has to appear around that body without the body having asked.
 * That is the shape the real Wynncraft pack has, and it is the shape a patch that only answered
 * names the pack already wrote would pass and this one would fail.
 * <p>
 * The switch ships on and is turned off by a file in the game directory, so the two states are
 * read in one JVM through the property that outranks the file - which is the only way to read one
 * against the other without a second launch. {@code GlslTranslator.emissionSwitches} keeps the
 * translation caches from serving one state's text to the other.
 */
class WynncraftEntityProgramTest {

	/**
	 * The property that outranks the marker file, named once so that a test that moves it and the
	 * tests that read the moved value cannot come to disagree about its spelling.
	 */
	private static final String SWITCH = "wynnvitrail.enabled";

	private static final String VERTEX = """
			#version 120

			varying vec4 texcoord;

			void main() {
			    gl_Position = ftransform();
			    texcoord = gl_MultiTexCoord0;
			}
			""";

	/**
	 * Writes its colour and nothing else. It names no signal, reads no vertex colour and contains no
	 * line either can be confused with, which is what makes every assertion below a statement about
	 * the patch rather than about the pack.
	 */
	private static final String FRAGMENT = """
			#version 120

			uniform sampler2D texture;
			varying vec4 texcoord;

			void main() {
			    vec4 c = texture2D(texture, texcoord.st);
			    gl_FragData[0] = c;
			}
			""";

	@Test
	void theDecodeAndItsApplicationAreWovenIntoAnEntityProgram(@TempDir Path pack) throws IOException {
		withSwitch(SWITCH, true, () -> {
			Map<ProgramStage, String> text = translate(pack);

			String vertex = text.get(ProgramStage.VERTEX);
			String fragment = text.get(ProgramStage.FRAGMENT);

			assertNotNull(vertex);
			assertNotNull(fragment);

			// The varying, on the side that writes it and the side that reads it. One letter of
			// difference between the two declarations and the game refuses the module, so both are
			// named.
			assertTrue(vertex.contains("out vec4 of_VertexColor;"),
					"the vertex stage does not hand the colour on:\n" + vertex);
			assertTrue(fragment.contains("in vec4 of_VertexColor;"),
					"the fragment stage cannot see the colour:\n" + fragment);

			// The other two the mesh carries, which the effects read a coordinate off. Both are
			// asked of the mesh rather than of the pack - a pack's own coordinate is whatever the
			// pack called it - so both have to be declared on both sides and filled on one.
			assertTrue(vertex.contains("out vec2 of_VertexUV;"),
					"the vertex stage does not hand the coordinate on:\n" + vertex);
			assertTrue(fragment.contains("in vec2 of_VertexUV;"),
					"the fragment stage cannot see the coordinate:\n" + fragment);
			assertTrue(vertex.contains("out vec2 of_VertexMidTex;"),
					"the vertex stage does not hand the middle of the sprite on:\n" + vertex);
			assertTrue(fragment.contains("in vec2 of_VertexMidTex;"),
					"the fragment stage cannot see the middle of the sprite:\n" + fragment);
			assertTrue(vertex.contains("of_VertexUV = UV0;"),
					"the coordinate is declared and never filled:\n" + vertex);
			assertTrue(vertex.contains("of_VertexMidTex = MidTexCoord;"),
					"the sprite's middle is declared and never filled:\n" + vertex);

			// The fourth, which only the skies read: a Wynncraft skybox is a box, so the direction
			// a fragment of it lies in is the direction of the vertex it was built from.
			assertTrue(vertex.contains("out vec3 of_VertexPosition;"),
					"the vertex stage does not hand the position on:\n" + vertex);
			assertTrue(fragment.contains("in vec3 of_VertexPosition;"),
					"the fragment stage cannot see the position:\n" + fragment);
			assertTrue(vertex.contains("of_VertexPosition = Position;"),
					"the position is declared and never filled:\n" + vertex);

			// The copy that fills it, out of the mesh's own element rather than out of the name the
			// pack reads: that name has just been redefined to hide the signal, and this varying is
			// the only place it survives.
			assertTrue(vertex.contains("of_VertexColor = Color;"),
					"the varying is declared and never filled:\n" + vertex);

			// And the hiding itself, on the vertex stage, below the head that defines the name and
			// above the body that reads it.
			assertTrue(vertex.contains("#undef of_Color"),
					"the pack still sees the signal as its own tint:\n" + vertex);
			assertTrue(vertex.contains("#define of_Color wynnNeutralColour(Color)"),
					"the name was undefined and not redefined:\n" + vertex);
			assertTrue(vertex.contains("vec4 wynnNeutralColour(vec4 colour)"),
					"the redefinition calls a function the header never wrote:\n" + vertex);

			// The decode, in the header, where the wrapper's call can reach it.
			assertTrue(fragment.contains("bool wynnIsGlint(vec4 colour)"),
					"the fragment stage has no glint decode:\n" + fragment);
			assertTrue(fragment.contains("int wynnTranslucency(vec4 colour)"),
					"the fragment stage has no translucency decode:\n" + fragment);

			// The application, inside a wrapper of ours and after the pack's own body. The wrapper is
			// read off the renamed entry point rather than assumed: it is the whole of the seam.
			assertTrue(fragment.contains("ofPackMain();"),
					"the pack's main was not wrapped:\n" + fragment);
			assertTrue(fragment.contains("void main() { "),
					"no wrapper was written for the wrapped main:\n" + fragment);
			assertTrue(fragment.contains("wynnTranslucency(of_VertexColor)"),
					"the decode is woven in and never called:\n" + fragment);

			// The application is the clamp WynnIris settled on and not a multiply: see
			// WynncraftPatch.epilogue. The alpha it clamps to is read through the helper rather than
			// inlined, so that the floor of nought point one lives in one place.
			assertTrue(fragment.contains("wynnTranslucentAlpha(wynnLevel)"),
					"the level is not turned into the alpha it means:\n" + fragment);
			assertTrue(fragment.contains("max(0.10, 1.0 - float(level) / 100.0)"),
					"the alpha floor and the level's meaning are not the pack's own:\n" + fragment);

			// After the pack's body and not before it: the application changes the colour the pack
			// has already decided, and a call standing above the body would change one nothing had
			// written yet.
			assertTrue(fragment.indexOf("ofPackMain();")
							< fragment.indexOf("wynnTranslucency(of_VertexColor)"),
					"the application runs before the pack's own body:\n" + fragment);
		});
	}

	/**
	 * The glint, which is what the decode is for.
	 * <p>
	 * Read as text like everything else here, and the things worth reading are the ones a build
	 * cannot tell: that the library is written into the header at all, that the call reaches it with
	 * the sampler this program really declares rather than with the name the library takes it under,
	 * that the number comes off the varying the vertex stage filled, and that the three parts of the
	 * call are in the order that makes the picture.
	 * <p>
	 * The pack declares its diffuse texture as {@code texture}, which is the word modern GLSL
	 * reserves and the translator renames, so the name the call has to carry is {@code ofTexture}:
	 * an assertion on that spelling is an assertion that the sampler was found in the program rather
	 * than guessed.
	 */
	@Test
	void theGlintIsDrawnOverAnItemThatCarriesASignal(@TempDir Path pack) throws IOException {
		withSwitch(SWITCH, true, () -> {
			String fragment = translate(pack).get(ProgramStage.FRAGMENT);

			assertNotNull(fragment);

			// The library, in the header, because the wrapper that calls it is written below the
			// body. One function out of the seventeen is named as the witness, and the two the
			// switch dispatches on are named because a library without them is a call that does not
			// compile.
			assertTrue(fragment.contains("vec4 wynnApplyGlint(sampler2D wynnTex,"),
					"the fragment stage has no glint library:\n" + fragment);
			assertTrue(fragment.contains("const float wynnGlintBrightness = 1.10;"),
					"the glint's brightness is not the setting it was measured at:\n" + fragment);

			// The number, off the varying and not off a uniform: this is the whole of what the
			// vertex-side neutralisation exists to preserve. Read once, because the two halves of
			// the application that want it are not the only places it could have been written and a
			// decode repeated is a decode that can drift.
			assertTrue(fragment.contains("int wynnEffect = wynnGlintId(of_VertexColor);"),
					"the effect number is not read off the carried colour:\n" + fragment);
			assertTrue(fragment.indexOf("int wynnEffect =") == fragment.lastIndexOf("int wynnEffect ="),
					"the effect number is decoded more than once:\n" + fragment);

			// The call, with the program's own sampler name in the first argument.
			assertTrue(fragment.contains("wynnApplyGlint(ofTexture, wynnEffect & 31, "),
					"the library is written and never called with this program's texture:\n"
							+ fragment);

			// The day the effects animate on, taken into the block because a pack of the corpus may
			// not declare it, and scaled from ticks to the three hundred units a day WynnIris uses.
			assertTrue(fragment.contains("int worldTime;"),
					"the day clock is read and never declared:\n" + fragment);
			assertTrue(fragment.contains("float(worldTime) * 0.0125"),
					"the clock is read in the wrong unit:\n" + fragment);

			// The order, which is the one thing about the pair that a still picture could not show:
			// the glint runs first and the reduction second, because seven of the effects rebuild
			// the alpha out of the texture and would undo a reduction applied before them. The
			// reduction is named by its assignment rather than by its decode, which now stands above
			// both of them.
			assertTrue(fragment.indexOf("wynnApplyGlint(ofTexture,")
							< fragment.indexOf("ofFragData0.a = min(ofFragData0.a, "),
					"the reduction runs before the glint that would overwrite it:\n" + fragment);

			// And both after the pack's own body, which is the seam the whole patch hangs off.
			assertTrue(fragment.indexOf("ofPackMain();") < fragment.indexOf("wynnApplyGlint(ofTexture,"),
					"the glint runs before the pack has decided anything:\n" + fragment);
		});
	}

	/**
	 * The two corrections a texture's alpha calls for, and where they stand beside the glint.
	 * <p>
	 * The second signal family, and the one thing about it a build cannot tell: it is read off a
	 * texel rather than off the carried colour, so it is the sampler the call has to reach for, and
	 * the order it takes in the application is WynnIris's own and is not free - the unlit correction
	 * has to see the colour the pack produced, and the self-lit lift has to see the number the glint
	 * has already been dispatched on.
	 */
	@Test
	void theUnlitAndSelfLitMarkersAreCorrectedWhereTheyLieInTheOrder(@TempDir Path pack)
			throws IOException {
		withSwitch(SWITCH, true, () -> {
			String fragment = translate(pack).get(ProgramStage.FRAGMENT);

			assertNotNull(fragment);

			// The library, in the header, with the strength it is applied at. That strength is a
			// constant here where WynnIris reads it from a video setting, so its value is the
			// setting's default and is asserted rather than left to the two call sites to imply.
			assertTrue(fragment.contains("bool wynnIsShadeless(vec4 texel)"),
					"the fragment stage cannot tell an unlit texture:\n" + fragment);
			assertTrue(fragment.contains("bool wynnIsEmissive(vec4 texel)"),
					"the fragment stage cannot tell a self-lit texture:\n" + fragment);
			assertTrue(fragment.contains("const float wynnEntityEmissivity = 1.0;"),
					"the lift's strength is not the setting's default:\n" + fragment);

			// The markers themselves, by their numbers, which are the whole of what makes them
			// signals rather than alphas a pack might have meant.
			assertTrue(fragment.contains("abs(texel.a * 255.0 - 251.0) < 0.5"),
					"the unlit marker is not the one the server writes:\n" + fragment);
			assertTrue(fragment.contains("return a == 254 && g != 251;"),
					"the self-lit marker is not the one the server writes:\n" + fragment);

			// Both applied, over this program's own texture and off the mesh's own coordinate: the
			// marking is a property of the sprite, and a pack's own sample may have been taken
			// elsewhere or not at all.
			// The divisor goes through the neutraliser and not straight to the carried colour, which
			// is the one place the two engines' expressions differ for a reason: WynnIris's carried
			// colour is already white on a mesh that holds a signal, and this engine's is the signal
			// itself. See WynncraftShading.shadeless.
			assertTrue(fragment.contains("wynnIsShadeless(wynnShadingTexel)) { ofFragData0.rgb /="
							+ " max(wynnNeutralColour(of_VertexColor).rgb, vec3(0.05)); }"),
					"the unlit correction divides by the signal it is meant to ignore:\n" + fragment);
			assertTrue(fragment.contains(
							"wynnIsEmissive(wynnShadingTexel) && !(wynnEffect >= 15 && wynnEffect <= 24)"),
					"the lift is applied where a tint was asked for:\n" + fragment);
			assertTrue(fragment.contains("mix(ofFragData0.rgb, max(ofFragData0.rgb,"
							+ " wynnShadingTexel.rgb), wynnEntityEmissivity)"),
					"the lift is not a mix at the setting's strength:\n" + fragment);

			// The order, which is WynnIris's and is the one thing here a still picture could not
			// show. Each neighbour has a reason: unlit reads the pack's own colour, the glint
			// rebuilds the pixel, the reduction holds what the glint left, and the lift needs the
			// number to have been decoded before it can excuse a tint.
			int unlit = fragment.indexOf("wynnIsShadeless(wynnShadingTexel)");
			int glint = fragment.indexOf("wynnApplyGlint(ofTexture,");
			int reduction = fragment.indexOf("ofFragData0.a = min(ofFragData0.a, ");
			int lit = fragment.indexOf("wynnIsEmissive(wynnShadingTexel)");

			assertTrue(unlit >= 0 && glint >= 0 && reduction >= 0 && lit >= 0,
					"one of the four steps is missing:\n" + fragment);
			assertTrue(unlit < glint, "the unlit correction runs after the glint rebuilt the pixel:\n"
					+ fragment);
			assertTrue(glint < reduction, "the reduction runs before the glint that would undo it:\n"
					+ fragment);
			assertTrue(reduction < lit, "the lift runs before the reduction has settled the alpha:\n"
					+ fragment);

			// And the whole of it after the pack's own body, as every step of the application is.
			assertTrue(fragment.indexOf("ofPackMain();") < unlit,
					"a correction runs before the pack has decided anything:\n" + fragment);

			// The library stands in the header and not in the body, or the wrapper's calls would be
			// calls to nothing.
			assertTrue(fragment.indexOf("bool wynnIsShadeless(vec4 texel)")
							< fragment.indexOf("ofPackMain();"),
					"the library is written below the code that calls it:\n" + fragment);
		});
	}

	/**
	 * The sky, which is the one application that replaces the colour rather than adjusting it.
	 * <p>
	 * Two things are asked of it that a still reading of the text cannot answer on its own, and both
	 * are about order. The skies' own noise is the glint library's, so that library has to have been
	 * written first or the header names a callee nothing declared; and the application has to run
	 * before the four steps that adjust the colour, because those would otherwise adjust a colour
	 * that is about to be discarded.
	 */
	@Test
	void theSkyReplacesTheColourBeforeAnythingAdjustsIt(@TempDir Path pack) throws IOException {
		withSwitch(SWITCH, true, () -> {
			String fragment = translate(pack).get(ProgramStage.FRAGMENT);

			// The decode and the dispatch, with the shapes the caller is written against.
			assertTrue(fragment.contains("int wynnSkyboxSignal(sampler2D tex, vec2 uv) {"),
					"no sky identity is read:\n" + fragment);
			assertTrue(fragment.contains("vec4 wynnSkyApply(int id, float time, vec3 direction) {"),
					"no sky is drawn:\n" + fragment);

			// The decode is read at LOD 0 with a tolerance, because Vitrail's sampler can still let
			// anisotropy or derivative-based LOD move the marking bytes off their exact values.
			assertTrue(fragment.contains("vec4 sc = textureLod(tex, uv, 0.0);"),
					"the skybox signal is not forced to LOD 0:\n" + fragment);
			assertTrue(fragment.contains("abs(sg - 251) <= 1 && abs(sa - 254) <= 1"),
					"the skybox signal has no tolerance on the marking bytes:\n" + fragment);

			// That the seven skies are all there, by one function of each shape they come in: the
			// lattice, a rotation, the crystalline field, the bolt and its envelope.
			for (String sky : new String[] {"float wynnSkyFbm(vec2 p) {", "float wynnSkyFbm(vec3 p) {",
					"vec3 wynnSkyRotateAxis(vec3 v, vec3 axis, float angle) {",
					"float wynnSkyCrystalNoise(vec3 position, float time) {",
					"float wynnSkyLightningBolt(vec2 uv, vec2 start, vec2 end, float seed, float width) {",
					"float wynnSkyLightningFlash(float time, float seed) {",
					"float wynnSkyDistantCloudFlash(float time, float seed) {"}) {
				assertTrue(fragment.contains(sky), "a sky is missing: " + sky + "\n" + fragment);
			}

			// The dependency, which is an adjacency and not a membership: the skies interpolate a
			// lattice the glint library declares, and a header holding one without the other does
			// not compile at all.
			assertTrue(fragment.indexOf("float wynnSmoothNoise(vec2 p) {")
							< fragment.indexOf("float wynnSkyFbm(vec2 p) {"),
					"the skies are written above the noise they are built on:\n" + fragment);

			// The call itself: the program's own sampler, the mesh's own coordinate, and the two
			// ids at the value that means the CPU side has filled neither. The ids being nought is
			// WynnIris's own "no primary detected", so the discard below cannot fire - and it is
			// asserted in that state rather than skipped, because the day the CPU side lands the
			// constants become uniforms and this is the line that has to change.
			assertTrue(fragment.contains("int wynnSkyId = wynnSkyboxSignal(ofTexture, of_VertexUV);"),
					"the sky is decoded and never asked for:\n" + fragment);
			assertTrue(fragment.contains("const int wynnSkyPrimaryId = 0;"),
					"the primary sky id is not the constant this port has:\n" + fragment);
			assertTrue(fragment.contains("if (wynnSkyId == wynnSkyPrimaryId"
							+ " || wynnSkyId == wynnSkyRecentId) { discard; }"),
					"a sky drawn twice would be drawn twice:\n" + fragment);

			// The time, in the shape WynnIris writes it and against this engine's own day clock: the
			// day is a fraction there and ticks here, so it is divided back out before being taken
			// up to the pack's twelve thousand.
			assertTrue(fragment.contains("fract(float(worldTime) / 24000.0) * 12000.0"),
					"the sky is drawn at the wrong hour:\n" + fragment);

			// The direction, off the mesh's own position, which is the one thing a box-shaped sky
			// needs that a dome would not.
			assertTrue(fragment.contains("= wynnSkyApply(wynnSkyId, wynnSkyTime, "
							+ "normalize(of_VertexPosition));"),
					"the sky is drawn from somewhere other than the vertex it lies on:\n" + fragment);

			// The three adjacencies, which are the whole of the reason the sky runs first.
			int sky = fragment.indexOf("wynnSkyApply(wynnSkyId,");
			int unlit = fragment.indexOf("wynnIsShadeless(wynnShadingTexel)");
			int glint = fragment.indexOf("= wynnApplyGlint(ofTexture,");
			int lift = fragment.indexOf("wynnEntityEmissivity)");
			assertTrue(sky < unlit && unlit < glint && glint < lift,
					"the application is not in WynnIris's order:\n" + fragment);

			// And the guard that keeps the two corrections off a colour the drawing chose. Asked
			// twice, because the two are guarded separately and one of them guarded alone is a sky
			// divided by the light the pack had baked in. The brace after the guard is the
			// correction's own block, which is why there is only one of them.
			assertTrue(fragment.contains("if (!wynnSkyApplied) { vec4 wynnShadingTexel"),
					"the unlit correction would act on a sky:\n" + fragment);
			assertTrue(fragment.lastIndexOf("if (!wynnSkyApplied) {")
							> fragment.indexOf("if (wynnLevel > 0) { "),
					"the self-lit lift is not guarded, or not after the reduction:\n" + fragment);
		});
	}

	/**
	 * A program that declares no diffuse texture.
	 * <p>
	 * None of what samples the item's own sprite has anything to read there - the glint, the sky,
	 * and both shading corrections all sample that texel, and the glint reads the texture's size to
	 * know whether it is looking at an atlas - so all of them are withheld. What is NOT withheld is
	 * the reduction, which reads the carried colour alone, and that is the point of the split: a
	 * program that cannot take one half of the patch still gets the other rather than losing both.
	 */
	@Test
	void aProgramWithNoDiffuseTextureStillGetsTheReduction(@TempDir Path pack) throws IOException {
		String fragment = """
				#version 120

				uniform sampler2D colortex1;
				varying vec4 texcoord;

				void main() {
				    gl_FragData[0] = vec4(1.0);
				}
				""";

		withSwitch(SWITCH, true, () -> {
			String text = translate(pack, fragment).get(ProgramStage.FRAGMENT);

			assertTrue(text.contains("wynnTranslucency(of_VertexColor)"),
					"the reduction was dropped with the glint:\n" + text);
			// The declarations are in the header either way and are not what is asked about; the
			// calls are the assignments, and they are the ones that must not be there.
			assertFalse(text.contains("= wynnApplyGlint("),
					"the glint was called on a program with no sprite to draw over:\n" + text);
			// The sky is withheld for the same reason and not by accident: it is decoded out of the
			// same texel, so a program with no sampler has nothing to decode and nothing to draw.
			assertFalse(text.contains("= wynnSkyApply("),
					"a sky was drawn on a program with no texel to read it from:\n" + text);
			assertFalse(text.contains("wynnIsShadeless(wynnShadingTexel)"),
					"an unlit texture was looked for on a program with none:\n" + text);
			assertFalse(text.contains("wynnIsEmissive(wynnShadingTexel)"),
					"a self-lit texture was looked for on a program with none:\n" + text);
		});
	}

	/**
	 * A pack whose own slot nought is not a {@code vec4}.
	 * <p>
	 * The application writes the alpha of the pack's first colour output, and {@code .a} on a
	 * {@code vec3} is not a name the language has: emitted anyway, this is a module the compiler
	 * refuses, and the whole pass is lost over an effect that is decoration on top of one. The alpha
	 * test carries the same gate for the same reason, so what is checked here is that the two agree.
	 * <p>
	 * The varying is still declared on both sides, which is the other half of it: what the two
	 * stages are told has to be one answer whatever either of them does with the value, or a
	 * location moves.
	 */
	@Test
	void anOutputThatIsNotAVec4IsLeftAloneRatherThanWritten(@TempDir Path pack) throws IOException {
		String fragment = """
				#version 120

				uniform sampler2D texture;
				varying vec4 texcoord;
				layout(location = 0) out vec3 fragColor;

				void main() {
				    fragColor = texture2D(texture, texcoord.st).rgb;
				}
				""";

		withSwitch(SWITCH, true, () -> {
			Map<ProgramStage, String> text = translate(pack, fragment);

			assertTrue(text.get(ProgramStage.FRAGMENT).contains("in vec4 of_VertexColor;"),
					"the varying was dropped with the application:\n"
							+ text.get(ProgramStage.FRAGMENT));
			assertFalse(text.get(ProgramStage.FRAGMENT).contains("wynnTranslucency(of_VertexColor)"),
					"the alpha of a vec3 was written:\n" + text.get(ProgramStage.FRAGMENT));
			assertFalse(text.get(ProgramStage.FRAGMENT).contains("= wynnApplyGlint("),
					"the glint was drawn into a vec3:\n" + text.get(ProgramStage.FRAGMENT));
			// The shading corrections write colour alone and a vec3 has colour, so this half is
			// narrower than it has to be and the test says so rather than leaving it to be
			// discovered: the gate is one gate, and Emitter.wynncraftEpilogue carries why.
			assertFalse(text.get(ProgramStage.FRAGMENT).contains("wynnShadingTexel"),
					"a correction was written into a slot the application refused:\n"
							+ text.get(ProgramStage.FRAGMENT));
			assertFalse(text.get(ProgramStage.FRAGMENT).contains("ofPackMain"),
					"the body was wrapped for an application that was refused:\n"
							+ text.get(ProgramStage.FRAGMENT));
		});
	}

	@Test
	void aPackTranslatedWithThePatchOffIsUntouched(@TempDir Path pack) throws IOException {
		withSwitch(SWITCH, false, () -> {
			Map<ProgramStage, String> text = translate(pack, FRAGMENT);

			// The whole of the patch, by the one substring every line of it carries. A pack that
			// asked for none of this must come out of the translator exactly as it went in, which is
			// what keeps an unpatched run the same program it was before the fork.
			assertFalse(text.get(ProgramStage.FRAGMENT).contains("wynn"),
					"the fragment stage was patched with the switch off:\n"
							+ text.get(ProgramStage.FRAGMENT));
			assertFalse(text.get(ProgramStage.VERTEX).contains("of_VertexColor"),
					"the vertex stage was patched with the switch off:\n"
							+ text.get(ProgramStage.VERTEX));
		});
	}

	/**
	 * The shipped state, which is the one a player who has said nothing is in.
	 * <p>
	 * <strong>This is the case that failed in the game and is the reason the switch has a file.</strong>
	 * Before it, the only way to be in this state was to name a JVM property, and the way to be in
	 * the other was to name it too, so a run with nothing named had no patch and no line anywhere
	 * saying so. The assertion is the one that would have caught it: a pack that carries a signal
	 * gets the glint with nothing set at all.
	 */
	@Test
	void thePatchRunsWithNothingSetAnywhere(@TempDir Path pack) throws IOException {
		String previous = System.getProperty(SWITCH);
		System.clearProperty(SWITCH);
		try {
			// A directory with no marker in it, which is what an installation nobody has touched
			// holds; read here so the test does not lean on whatever the last one left behind.
			WynncraftSettings.read(pack);

			assertTrue(WynncraftSettings.effects(), "the patch is off with nothing set anywhere");
			assertTrue(WynncraftSettings.state().contains("is ON"),
					"the state line does not say the patch is running: " + WynncraftSettings.state());

			String fragment = translate(pack).get(ProgramStage.FRAGMENT);
			assertTrue(fragment.contains("= wynnApplyGlint("),
					"a pack that says nothing at all was not given the glint:\n" + fragment);
		} finally {
			if (previous != null) {
				System.setProperty(SWITCH, previous);
			}
		}
	}

	/**
	 * The file a player uses, and the property that outranks it.
	 * <p>
	 * Both halves in one test because the second is a statement about the first: the property is not
	 * a second switch, it is the override that lets one JVM hold the states a call apart, which is
	 * what the tests above are written on.
	 */
	@Test
	void theFileInTheGameDirectoryTurnsThePatchOffAndThePropertyOutranksIt(@TempDir Path game)
			throws IOException {
		Path marker = game.resolve("wynnvitrail").resolve("no-wynncraft");
		Files.createDirectories(marker.getParent());
		Files.writeString(marker, "", StandardCharsets.UTF_8);

		String previous = System.getProperty(SWITCH);
		System.clearProperty(SWITCH);
		try {
			WynncraftSettings.read(game);

			assertFalse(WynncraftSettings.effects(), "the marker did not turn the patch off");
			assertTrue(WynncraftSettings.state().contains("is OFF"),
					"the state line does not say the patch is off: " + WynncraftSettings.state());
			assertTrue(WynncraftSettings.state().contains("no-wynncraft"),
					"the state line does not name the file that did it: " + WynncraftSettings.state());

			// The property over the file, which is the test's own escape hatch and the only reason
			// the two states can be read against each other in one JVM.
			System.setProperty(SWITCH, "true");

			assertTrue(WynncraftSettings.effects(), "the property did not outrank the file");
			assertTrue(WynncraftSettings.state().contains("is ON"),
					"the state line does not credit the property: " + WynncraftSettings.state());
		} finally {
			if (previous == null) {
				System.clearProperty(SWITCH);
			} else {
				System.setProperty(SWITCH, previous);
			}

			// Put the process back in the state every other test expects, whatever happened: the
			// switch is held in a static and the tests share a JVM.
			WynncraftSettings.read(game.getParent());
		}
	}

	/** What both stages of the program came out as, by stage, over the entity fragment above. */
	private static Map<ProgramStage, String> translate(Path pack) throws IOException {
		return translate(pack, FRAGMENT);
	}

	/** The same, over a fragment of the caller's choosing. */
	private static Map<ProgramStage, String> translate(Path pack, String fragment) throws IOException {
		Path shaders = Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(shaders.resolve("gbuffers_entity.vsh"), VERTEX, StandardCharsets.UTF_8);
		Files.writeString(shaders.resolve("gbuffers_entity.fsh"), fragment, StandardCharsets.UTF_8);

		var loaded = PackProgram.load(pack, "gbuffers_entity", VertexInputs.ENTITY, Map.of(), "");
		assertTrue(loaded.isPresent(), "the program did not load");

		Map<ProgramStage, String> text = new java.util.EnumMap<>(ProgramStage.class);
		loaded.get().program().stages().forEach((stage, unit) -> text.put(stage, unit.text()));

		return text;
	}

	/**
	 * Runs the body with one switch set and puts it back afterwards, whatever happens.
	 * <p>
	 * The switch is a process wide property and the tests share a JVM, so a body that threw with it
	 * left set would make the next test read a state it never asked for.
	 */
	private static void withSwitch(String name, boolean on, ThrowingRunnable body) throws IOException {
		String previous = System.getProperty(name);
		System.setProperty(name, Boolean.toString(on));
		try {
			body.run();
		} finally {
			if (previous == null) {
				System.clearProperty(name);
			} else {
				System.setProperty(name, previous);
			}
		}
	}

	/** A body that may throw what the translator throws, which is only ever an {@code IOException}. */
	private interface ThrowingRunnable {
		void run() throws IOException;
	}
}
