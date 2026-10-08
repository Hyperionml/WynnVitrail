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
 * The switch is a system property, so the two states are checked in one JVM a call apart, which is
 * the only way to read one against the other. {@code GlslTranslator.emissionSwitches} keeps the
 * translation caches from serving one state's text to the other.
 */
class WynncraftEntityProgramTest {

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
		withSwitch("wynnvitrail.enabled", true, () -> {
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

		withSwitch("wynnvitrail.enabled", true, () -> {
			Map<ProgramStage, String> text = translate(pack, fragment);

			assertTrue(text.get(ProgramStage.FRAGMENT).contains("in vec4 of_VertexColor;"),
					"the varying was dropped with the application:\n"
							+ text.get(ProgramStage.FRAGMENT));
			assertFalse(text.get(ProgramStage.FRAGMENT).contains("wynnTranslucency(of_VertexColor)"),
					"the alpha of a vec3 was written:\n" + text.get(ProgramStage.FRAGMENT));
			assertFalse(text.get(ProgramStage.FRAGMENT).contains("ofPackMain"),
					"the body was wrapped for an application that was refused:\n"
							+ text.get(ProgramStage.FRAGMENT));
		});
	}

	@Test
	void aPackTranslatedWithThePatchOffIsUntouched(@TempDir Path pack) throws IOException {
		withSwitch("wynnvitrail.enabled", false, () -> {
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
