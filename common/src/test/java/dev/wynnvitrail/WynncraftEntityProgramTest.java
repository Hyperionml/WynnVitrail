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
		// pack called it - so both have to be declared on both sides and filled on one. The fill
		// is the decoded coordinate rather than the element's own, because the emote decode runs
		// ahead of it and a limb's glint is drawn over the limb's own region of the skin.
		assertTrue(vertex.contains("out vec2 of_VertexUV;"),
				"the vertex stage does not hand the coordinate on:\n" + vertex);
		assertTrue(fragment.contains("in vec2 of_VertexUV;"),
				"the fragment stage cannot see the coordinate:\n" + fragment);
		assertTrue(vertex.contains("out vec2 of_VertexMidTex;"),
				"the vertex stage does not hand the middle of the sprite on:\n" + vertex);
		assertTrue(fragment.contains("in vec2 of_VertexMidTex;"),
				"the fragment stage cannot see the middle of the sprite:\n" + fragment);
		assertTrue(vertex.contains("of_VertexUV = wynnEmoteUv;"),
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

		// The fifth, which only the emote writes and only the fragment spends: a computed fade
		// rather than a carried value, one on a mesh that never held a limb at all.
		assertTrue(vertex.contains("out float of_VertexNearFade;"),
				"the vertex stage does not hand the fade on:\n" + vertex);
		assertTrue(fragment.contains("in float of_VertexNearFade;"),
				"the fragment stage cannot see the fade:\n" + fragment);

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

			// The day the effects animate on, taken into the block because it is this engine's own
			// name rather than the pack's, and scaled from ticks to the three hundred units a day
			// WynnIris uses. A float with the frame's fraction of a tick on it and not the pack's
			// whole-tick int: a sky is twelve thousand units of a day, so the integer would step
			// half a unit twenty times a second where WynnIris slides.
			assertTrue(fragment.contains("float wynnDayClock;"),
					"the day clock is read and never declared:\n" + fragment);
			assertTrue(fragment.contains("wynnDayClock * 0.0125"),
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

			// And the compensation for a sky that darkened the scene, which is the other arm of the
			// same block and the arm every unmarked fragment takes. Its value is a member of the
			// block rather than a constant, because how far a sky has darkened a scene moves frame
			// to frame; the member is declared here and filled by the runtime, and WynncraftSky is
			// the rule behind the number.
			assertTrue(fragment.contains("float wynnEntityBoost;"),
					"the compensation is spent and never declared:\n" + fragment);
			assertTrue(fragment.contains(
							"float wynnBoostScale = mix(wynnEntityBoost, 1.0, smoothstep(0.3, 0.8,"
									+ " wynnBoostLuma))"),
					"the compensation is not ramped by the fragment's own brightness:\n" + fragment);
			assertTrue(fragment.contains(
							"if (wynnBoostMax * wynnBoostScale > 1.0) { wynnBoostScale = 1.0 /"
									+ " max(wynnBoostMax, 1e-5); }"),
					"a colour taken past one is left to the hardware to clamp:\n" + fragment);

			// The two arms exclude each other, which is the whole reason they are one block: a
			// self-lit piece is drawn at its own brightness and a piece under a storm is drawn
			// brighter than the pack left it, and doing both would lift a lamp the sky had already
			// been compensated for.
			assertTrue(fragment.indexOf("wynnEntityEmissivity); } else { float wynnBoostLuma") > 0,
					"the compensation is not the mark's other arm:\n" + fragment);

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
			// up to the pack's twelve thousand. Read from the clock that carries the frame's
			// fraction, so that a sky moves between ticks as WynnIris's does.
			assertTrue(fragment.contains("fract(wynnDayClock / 24000.0) * 12000.0"),
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
			//
			// The lift carries a SECOND guard the unlit correction has not got, and it is the
			// mesh's own flags rather than the sky: a mesh that asked for the light tweaks to stand
			// has been lit by the server already, and WynnIris withholds its own pair on the same
			// flag ({@code EntityPatcher.java:1412}, {@code :1497}).
			assertTrue(fragment.contains("if (!wynnSkyApplied) { vec4 wynnShadingTexel"),
					"the unlit correction would act on a sky:\n" + fragment);
			assertTrue(fragment.contains(
							"if (!wynnSkyApplied && !wynnSkipLights) { vec4 wynnShadingTexel"),
					"the self-lit lift ignores the flags the mesh carries:\n" + fragment);
			assertTrue(fragment.indexOf("if (!wynnSkyApplied && !wynnSkipLights)")
							> fragment.indexOf("if (wynnLevel > 0) { "),
					"the self-lit lift runs before the reduction has settled the alpha:\n"
							+ fragment);
		});
	}

	/**
	 * The player emote, which is the one decode that changes what the pack's own geometry and
	 * texture reads see rather than what its colour reads do.
	 * <p>
	 * Three things are asked of it that a membership check cannot: that the decode runs AHEAD of
	 * the pack's own main, because the pack draws with what it leaves; that the pack's reads of
	 * the position and the coordinate are redirected onto the decoded pair, because the mesh
	 * stores both in their encoded form; and that the fade the fragment spends travels on a
	 * varying rather than through either of the two names above, because it is a value the mesh
	 * never held.
	 */
	@Test
	void theEmotePlayerIsDecodedBeforeThePackDrawsIt(@TempDir Path pack) throws IOException {
		withSwitch(SWITCH, true, () -> {
			Map<ProgramStage, String> text = translate(pack);

			String vertex = text.get(ProgramStage.VERTEX);
			String fragment = text.get(ProgramStage.FRAGMENT);

			// The decode and the data it reads, both in the header where the wrapper can reach
			// them and with the shapes the call is written against.
			assertTrue(vertex.contains("void wynnApplyPlayer(inout vec3 pos, inout vec2 uv,"
							+ " out float nearFade) {"),
					"no emote is decoded:\n" + vertex);
			assertTrue(vertex.contains("const wynnLimbUv WYNN_EMOTE_LIMB_UVS[8]"),
					"the limb table is missing:\n" + vertex);

			// The two guards the decode opens with, both the game's own numbers: the threshold
			// that leaves an ordinary vertex alone, and the identity check that keeps the decode
			// out of the interface, where the whole transform was put in the pose.
			assertTrue(vertex.contains("if (pos.y < 2.0 * float(WYNN_EMOTE_Y_RADIX)) return;"),
					"the decode does not leave an ordinary vertex alone:\n" + vertex);
			assertTrue(vertex.contains("if (of_GameModelView == mat4(1.0)) return;"),
					"the decode does not ask the draw's own matrix:\n" + vertex);

			// The vertex index the face and the overlay come off, in the OpenGL spelling the
			// engine's compiler maps at compile time.
			assertTrue(vertex.contains("int face = (gl_VertexID % 24) / 4;"),
					"the face is not read off the vertex index:\n" + vertex);

			// The redirection, which is the whole of what makes the decode visible to the pack:
			// its own reads of the position and the coordinate are pointed at the decoded pair,
			// under the macros the head defines and below the head that defines them.
			assertTrue(vertex.contains("#undef of_Vertex"),
					"the pack still draws the encoded position:\n" + vertex);
			assertTrue(vertex.contains("#define of_Vertex vec4(wynnEmotePos, 1.0)"),
					"the position was undefined and not redirected:\n" + vertex);
			assertTrue(vertex.contains("#define of_MultiTexCoord0 vec4(wynnEmoteUv, 0.0, 1.0)"),
					"the pack still samples the head's region of the skin:\n" + vertex);

			// The call, in the wrapper and ahead of the pack's own main: what the pack draws with
			// is what the decode left, and a call below the body would decode nothing the body had
			// not already read.
			assertTrue(vertex.contains("wynnApplyPlayer(wynnEmotePos, wynnEmoteUv, wynnEmoteFade);"),
					"the decode is written and never called:\n" + vertex);
			assertTrue(vertex.indexOf("wynnApplyPlayer(wynnEmotePos")
							< vertex.indexOf("ofPackMain();"),
					"the decode runs after the pack has drawn:\n" + vertex);

			// The fade on the wire, and the discard and the multiplication that spend it. The
			// discard is first of everything the application does, because everything below it
			// would draw a picture it then takes off the screen.
			assertTrue(vertex.contains("of_VertexNearFade = wynnEmoteFade;"),
					"the fade is decoded and never carried:\n" + vertex);
			assertTrue(fragment.contains("if (of_VertexNearFade <= 0.01) { discard; }"),
					"a limb faded to nothing is not thrown away:\n" + fragment);
			assertTrue(fragment.contains("if (!wynnSkyApplied) { ofFragData0 *= of_VertexNearFade; }"),
					"the fade is not multiplied in, or not guarded on the sky:\n" + fragment);
			assertTrue(fragment.indexOf("if (of_VertexNearFade <= 0.01)")
							< fragment.indexOf("int wynnSkyId"),
					"the discard runs after the sky has been drawn:\n" + fragment);
		});
	}

	/**
	 * The item tint and the albedo it is suppressed into, which are the two halves of one question:
	 * what happens to a tint the game asked for when the pack lights the item without it.
	 * <p>
	 * The tint is put back with the hue of the tint at the luma the pack already lit, which is the
	 * shape that is safe on a pack that applied the tint itself; and the second output is zeroed
	 * only where the pack wrote one AND named the identifier BSL lights its entities out of, which
	 * is WynnIris's own gate and is inert on a pack configured as BSL ships.
	 */
	@Test
	void theItemTintIsPutBackAndTheVlAlbedoIsSuppressedWhereItExists(@TempDir Path pack)
			throws IOException {
		withSwitch(SWITCH, true, () -> {
			String fragment = translate(pack).get(ProgramStage.FRAGMENT);

			// The two identifiers the tint asks of the mesh, declared on the fragment side the
			// patch asked for them rather than the pack: the item's own id, and the block
			// entity's, whose high bits carry the flags that can ask for the tint to stand.
			assertTrue(fragment.contains("flat in int currentRenderedItemId;"),
					"the item's id is not carried:\n" + fragment);
			assertTrue(fragment.contains("flat in int blockEntityId;"),
					"the block entity's id is not carried:\n" + fragment);

			// The flags, with the one spelling that keeps the two engines alike: the identifier is
			// unsigned here and the unmapped value is six five five three five, which divided by
			// the radix is three where WynnIris's minus one divides to nought. Both that ride on
			// them are read: the tint stands where the flags ask, and the light tweaks stand from
			// two upwards.
			assertTrue(fragment.contains(
					"int wynnInfoFlags = (blockEntityId == 65535 ? -1 : blockEntityId) / 16384;"),
					"the flags are not read off the identifier:\n" + fragment);
			assertTrue(fragment.contains(
							"bool wynnSkipTint = wynnInfoFlags == 1 || wynnInfoFlags == 3;"),
					"the flags that ask for the tint to stand are not read:\n" + fragment);
			assertTrue(fragment.contains("bool wynnSkipLights = wynnInfoFlags >= 2;"),
					"the flags that ask for the light tweaks to stand are not read:\n" + fragment);

			// The tint itself: the pair the vanilla shader multiplies together, gated on the draw
			// being an item the game told the id of, and mixed in by how far the tint is from
			// white.
			assertTrue(fragment.contains("vec3 wynnTintColor = clamp(of_VertexColor.rgb"
							+ " * of_GameColorModulator.rgb, vec3(0.0), vec3(1.0));"),
					"the tint is not read out of the pair the game asked it through:\n" + fragment);
			assertTrue(fragment.contains("currentRenderedItemId > 0) {"),
					"the tint is not gated on the draw being an item:\n" + fragment);
			assertTrue(fragment.contains(".rgb = mix(ofFragData0.rgb, wynnPreservedTint, "
							+ "wynnTintStrength);"),
					"the tint is not mixed in at the luma the pack lit:\n" + fragment);

			// The order: after the reduction, before the fade, which is WynnIris's own.
			int reduction = fragment.indexOf("if (wynnLevel > 0) {");
			int tint = fragment.indexOf("currentRenderedItemId > 0) {");
			int fade = fragment.indexOf("ofFragData0 *= of_VertexNearFade");
			assertTrue(reduction < tint && tint < fade,
					"the tint is not between the reduction and the fade:\n" + fragment);

			// And the suppression, which this program has no second output for: the default pack
			// of these tests writes one colour and names no albedo, so the question is never
			// asked of it.
			assertFalse(fragment.contains("wynnShaderTint"),
					"the albedo was suppressed on a program with no second output:\n" + fragment);

			// The same program with a second output and BSL's own identifier in it: the question
			// is asked, and the answer is the colour of that output zeroed for a tinted fragment.
			String two = """
					#version 120

					uniform sampler2D texture;
					varying vec4 texcoord;

					void main() {
					    vec4 c = texture2D(texture, texcoord.st);
					    vec3 vlAlbedo = c.rgb;
					    gl_FragData[0] = c;
					    gl_FragData[1] = vec4(vlAlbedo, 1.0);
					}
					""";
			String suppressed = translate(pack, two).get(ProgramStage.FRAGMENT);

			assertTrue(suppressed.contains(
							"bool wynnShaderTint = wynnEffect >= 15 && wynnEffect <= 24;"),
					"the shader's own tints do not suppress the albedo:\n" + suppressed);
			assertTrue(suppressed.contains(".rgb = vec3(0.0); } } "),
					"the albedo is not zeroed for a tinted fragment:\n" + suppressed);
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

			// The compensation is the one step of the light tweaks that is not withheld, and the
			// split falls where it does for a reason worth reading back: a mark is a property of a
			// sprite and there is no sprite here, where a scene the sky has darkened is a property
			// of the fragment and such a program is as much a part of one as any other.
			assertTrue(text.contains("ofFragData0.rgb *= wynnBoostScale;"),
					"a program with no atlas was left out of the sky's compensation:\n" + text);
			assertFalse(text.contains("wynnShadingTexel"),
					"a correction that needs a sprite was applied without one:\n" + text);
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

	/**
	 * The deferred half of the patch: a pack that never writes its first output whole.
	 * <p>
	 * Such a pack holds an albedo in a variable and packs the gbuffer out of it a component at a
	 * time, after the discard it alpha-tests with and after everything it derives from the albedo.
	 * The effects belong on the variable and in the middle of the pack's own main, at the statement
	 * that mixes the entity colour over the albedo - which is the earliest point the albedo exists
	 * in its final form - and the tail the wrapper would have written is withheld, or the effects
	 * would run twice: once over the variable and once over the output the pack packed it into.
	 * WynnIris answers the same shape the same way ({@code EntityPatcher.java:1515-1590}).
	 */
	@Test
	void aDeferredPackGetsItsEffectsOnTheAlbedoVariableAtTheOverlayAnchor(@TempDir Path pack)
			throws IOException {
		String fragment = """
				#version 120

				uniform sampler2D texture;
				varying vec4 texcoord;
				varying vec4 entityColor;
				layout(location = 0) out vec4 color;

				void main() {
				    vec4 albedo = texture2D(texture, texcoord.st);
				    albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);
				    if (albedo.a < 0.1) discard;
				    color.rgb = albedo.rgb;
				    color.a = albedo.a;
				}
				""";

		withSwitch(SWITCH, true, () -> {
			String text = translate(pack, fragment).get(ProgramStage.FRAGMENT);

			// The decode runs in the pack's own main, which is the whole of what the deferred half
			// is: not a wrapper around it but statements inside it, where the pack's own lighting
			// and material extraction see the albedo the effects left.
			assertTrue(text.contains("int wynnEffect = wynnGlintId(of_VertexColor);"),
					"the effects were not put inside the pack's own main:\n" + text);

			// The statements sit after the overlay they take the variable from and before the alpha
			// test they have to beat, which is the one order that gives the pack the albedo the
			// effects decided rather than the one it had already packed.
			int overlay = text.indexOf("albedo.rgb = mix(albedo.rgb");
			int effects = text.indexOf("int wynnEffect = wynnGlintId");
			int discard = text.indexOf("if (albedo.a");
			assertTrue(overlay >= 0 && effects > overlay,
					"the effects do not follow the overlay they belong after:\n" + text);
			assertTrue(discard < 0 || effects < discard,
					"the effects run after the alpha test they have to beat:\n" + text);

			// The variable they write is the pack's own albedo and not the output: a glint that
			// rebuilt the output would leave the pack deriving its material channels from an
			// albedo the effect never touched.
			assertTrue(text.contains("albedo = wynnApplyGlint("),
					"the glint does not rebuild the albedo:\n" + text);
			assertTrue(text.contains("wynnPreservedTint, wynnTintStrength)"),
					"the item tint does not write the albedo:\n" + text);

			// And the tail is withheld, which is the other half of the half: a wrapper's epilogue
			// here would run the same effects again over the output, and a body that was wrapped
			// for nothing is one the compiler would take but the picture would not.
			assertFalse(text.contains("ofPackMain"),
					"the body was wrapped for an application that runs inside it:\n" + text);
		});
	}

	/**
	 * The same deferred shape without the overlay statement, which is the anchor WynnIris falls
	 * back on ({@code findAlphaDiscardAnchorInMain}): the first if that throws a fragment away on
	 * its albedo's alpha names the variable as surely as the mix did, and the statements go before
	 * it, so that a translucent limb's clamped alpha is the alpha the pack tests against.
	 */
	@Test
	void aDeferredPackWithoutAnOverlayAnchorsOnItsAlphaTest(@TempDir Path pack) throws IOException {
		String fragment = """
				#version 120

				uniform sampler2D texture;
				varying vec4 texcoord;
				layout(location = 0) out vec4 color;

				void main() {
				    vec4 albedo = texture2D(texture, texcoord.st);
				    if (albedo.a < 0.1) discard;
				    color.rgb = albedo.rgb;
				    color.a = albedo.a;
				}
				""";

		withSwitch(SWITCH, true, () -> {
			String text = translate(pack, fragment).get(ProgramStage.FRAGMENT);

			// texture2D is what the pack wrote and texture is what it comes out as, the legacy
			// spelling having been rewritten on the way up.
			int albedo = text.indexOf("albedo = texture(ofTexture");
			int effects = text.indexOf("int wynnEffect = wynnGlintId");
			int discard = text.indexOf("if (albedo.a");
			assertTrue(albedo >= 0 && effects > albedo,
					"the effects do not follow the albedo they read:\n" + text);
			assertTrue(discard >= 0 && effects < discard,
					"the effects do not run before the alpha test:\n" + text);
			assertFalse(text.contains("ofPackMain"),
					"the body was wrapped for an application that runs inside it:\n" + text);
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
