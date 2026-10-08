package dev.wynnvitrail;

import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.WorldState;

/**
 * The values this fork publishes into the block a pack reads, which no pack asks for by name.
 * <p>
 * <strong>A uniform and not an injected constant, because the answer moves every frame.</strong>
 * Everything else the Wynncraft patch weaves into a program is either text the pack wrote or a
 * threshold that never changes, and both are settled when the translation is made. What this file
 * carries is a number that depends on which sky is overhead and on how far into it the picture has
 * come, and a value that moves cannot be a constant: it has to be a member of the block the runtime
 * fills, named into the program's text by the patch and written per draw by this table.
 * <p>
 * <strong>It has its own file rather than a place in one of the engine's.</strong> The seven classes
 * beside it in the values package are the OptiFine names a pack reads and this engine answers; this
 * is the fork's own surface, and keeping it in the fork's package is what makes the difference
 * visible in a diff against upstream. {@code UniformCatalog} calls {@link #register} with the rest
 * of them, so a value added here reaches every program that names it with nothing else to change.
 * <p>
 * <strong>Nought is not a stand-in for anything here.</strong> A program that names one of these and
 * is drawn on a frame where the state machine has settled on no sky is handed the number the state
 * machine answers for that state, which is one - the identity of a multiply - rather than a member
 * left at its initial nought. That is the whole reason the source is written as a call into
 * {@link WynncraftSky} rather than as a field the runtime keeps: the state machine is the only place
 * that knows what "no sky" means for each of them.
 */
public final class WynncraftUniforms {

	private WynncraftUniforms() {
	}

	/**
	 * Publishes the fork's own names, called once with the rest of the catalogue.
	 *
	 * @param builder the table being assembled, whose {@code add} refuses a name already in it
	 */
	public static void register(UniformCatalog.Builder builder) {
		builder.add(WynncraftPatch.ENTITY_BOOST, UniformShape.FLOAT, (world, out) ->
				out.set(WynncraftSky.entityBoost(world.nightVision(), hand(world))));
	}

	/**
	 * Whether the pass being drawn is the player's own hand.
	 * <p>
	 * The state hands out the phase as its ordinal, which is the number a pack is given and the
	 * whole content of the enum's order; the two names wanted here are read back out of it rather
	 * than compared as numbers, because a number in this file would be a second copy of a decision
	 * that lives in {@code RenderStage} and would be edited on a different day.
	 */
	private static boolean hand(WorldState world) {
		RenderStage stage = RenderStage.values()[world.renderStage()];

		return stage == RenderStage.HAND_SOLID || stage == RenderStage.HAND_TRANSLUCENT;
	}
}
