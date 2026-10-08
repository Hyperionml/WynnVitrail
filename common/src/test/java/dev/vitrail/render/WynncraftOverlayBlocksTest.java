package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.Std140Counter;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Sums the members of each of the overlay's uniform blocks out of the shader that declares them, and
 * holds the number the buffer is allocated with against it.
 * <p>
 * <strong>The two halves of a block are written in two files and nothing joins them.</strong> The
 * shader declares {@code mat4, mat4, vec4}; the Java says how many bytes to allocate; and the writer
 * fills them in the declaration's order. A member added to one of the three and not to the others is
 * not a compile error, not a validation error and not a wrong picture: the writer runs off the end
 * of the buffer and the game stops, with a message that names a byte count and neither the block nor
 * the member. That is exactly what happened once, with {@code newPosition > limit: (128 > 80)} and
 * nothing else to go on, which is why this test exists rather than a comment saying "keep these in
 * step".
 * <p>
 * <strong>The sizes come from the engine's own counter and not from a second set of rules written
 * here.</strong> {@link Std140Counter} is the sink that tracks an offset instead of writing bytes,
 * and it is the same sink the engine's own {@code Std140SinkTest} checks the alignment rules of - so
 * a block that agrees with this test agrees with the writer, and a test that reimplemented the
 * alignment rules could agree with the constant and not with the writer.
 */
class WynncraftOverlayBlocksTest {

	/**
	 * The fog, whose declaration is one matrix, two colours' worth of parameters and the screen.
	 * <p>
	 * The block is asserted as well as the size: a size is only meaningful against the declaration it
	 * was taken from, and a test that read the first block in the file whatever it was called would
	 * pass on a shader whose blocks had been renamed.
	 */
	@Test
	void theFogBlockIsSizedByItsOwnDeclaration() {
		assertBlock(WynncraftOverlay.FOG, WynncraftOverlay.FOG_BLOCK, WynncraftOverlay.FOG_BLOCK_BYTES);
	}

	/** The scene, which is the one that was allocated four bytes short of its second matrix. */
	@Test
	void theSceneBlockIsSizedByItsOwnDeclaration() {
		assertBlock(WynncraftOverlay.SCENE, WynncraftOverlay.SCENE_BLOCK, WynncraftOverlay.SCENE_BLOCK_BYTES);
	}

	@Test
	void theTransitionBlockIsSizedByItsOwnDeclaration() {
		assertBlock(WynncraftOverlay.TRANSITION, WynncraftOverlay.TRANSITION_BLOCK,
				WynncraftOverlay.TRANSITION_BLOCK_BYTES);
	}

	/**
	 * One block, read out of the shader and summed.
	 *
	 * @param source   the fragment source the block is declared in
	 * @param name     the block's own name, which is what the pipeline is told to bind
	 * @param declared the bytes the buffer is allocated with
	 */
	private static void assertBlock(String source, String name, int declared) {
		String body = body(source, name);

		assertEquals(size(body), declared, "the " + name + " block is not the size its shader "
				+ "declares. Its members are:\n" + body);
	}

	/** The members of one named block, which is everything between its braces. */
	private static String body(String source, String name) {
		String opening = "uniform " + name + " {";
		int start = source.indexOf(opening);
		assertTrue(start >= 0, "no block named " + name + " is declared in this shader");

		int end = source.indexOf('}', start);
		assertTrue(end > start, "the block named " + name + " is never closed");

		return source.substring(start + opening.length(), end);
	}

	/**
	 * What the members cost, by making each one's write into a sink that counts.
	 * <p>
	 * The types are the four a block of this engine's uses and no others, and an unknown one fails
	 * rather than being skipped: a member this cannot size is a member the buffer is not sized for,
	 * which is the whole failure the test is here to catch.
	 */
	private static int size(String body) {
		Std140Counter counter = new Std140Counter();

		for (String line : body.split(";", -1)) {
			String member = line.trim();
			if (member.isEmpty()) {
				continue;
			}

			// A declaration is a type and then a name, and the name is the writer's business.
			String type = member.split("\\s+", 2)[0];
			switch (type) {
				case "mat4" -> counter.putMat4(new Matrix4f());
				case "vec4" -> counter.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
				case "vec3" -> counter.putVec3(0.0F, 0.0F, 0.0F);
				case "vec2" -> counter.putVec2(0.0F, 0.0F);
				case "float" -> counter.putFloat(0.0F);
				case "int" -> counter.putInt(0);
				default -> throw new AssertionError("a member of type '" + type
						+ "' is declared and this test cannot size it, so the buffer is not sized "
						+ "for it either");
			}
		}

		return counter.size();
	}
}
