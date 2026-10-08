package dev.wynnvitrail;

import dev.vitrail.Vitrail;

import net.minecraft.world.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * What another mod has already decided about drawing an entity, which the shadow map has to be told.
 * <p>
 * <strong>Wynntils hides things from the world by flipping a flag on the entity rather than by
 * taking it out of the level</strong>, and the lootrun and activity beacons are the ones that
 * matter: they are server spawned display entities, they stand where a player is trying to read a
 * map, and Wynntils wraps the game's own entity submission so that they are never drawn. Its gate
 * is inside that submission ({@code LevelRenderer.submitEntities}), and this engine fills its shadow
 * map on a walk of its own ({@code render/ShadowGeometry}) that never goes through it - so a beacon
 * a player cannot see still lays a shadow across the ground where it stands.
 * <p>
 * <strong>Read reflectively, so that neither mod has to know about the other.</strong> Wynntils
 * implements its extension on {@code Entity} itself, which means every entity carries
 * {@code isRendered()} at runtime and no entity carries it without Wynntils - so the class is looked
 * up by name, the answer is a {@code MethodHandle}, and this is a no-op returning false on an
 * installation that does not have it. That is also what keeps this class out of the loader
 * metadata: there is no dependency to declare, and nothing is loaded that the game did not already
 * have.
 * <p>
 * <strong>Not gated on the patch switch, which is a deliberate difference from everything else in
 * this package.</strong> The {@code no-wynncraft} file says that the pack's own programs are left
 * alone, so that a glint can be read against its absence; it does not say that this mod may draw
 * something the player's own interface has removed. A culled shadow is a fact about whether an
 * entity is in the world and belongs to whoever decided it, so it is honoured whether the effects
 * are woven in or not.
 * <p>
 * The lookup runs once, on whichever thread first asks - the render thread, in the first shadow walk
 * of a session - and the failure of it is a log line rather than an exception, because the only
 * thing at stake is a shadow under an entity that is not being drawn.
 */
public final class WynntilsCompat {

	/** The interface Wynntils mixes into {@code Entity}, which is absent on every other install. */
	private static final String EXTENSION_CLASS = "com.wynntils.mc.extension.EntityExtension";

	private static final Class<?> ENTITY_EXTENSION = resolveExtension();

	private static final MethodHandle IS_RENDERED = resolveIsRendered(ENTITY_EXTENSION);

	private WynntilsCompat() {
	}

	/**
	 * Wynntils' own flag, or nothing where it is not installed.
	 * <p>
	 * A missing class is the ordinary case rather than a fault, and it is answered in silence: a
	 * player on another server, or on this one without the companion mod, would otherwise get a
	 * warning about a mod they do not run on every launch. Anything else that goes wrong while
	 * looking it up is worth a line, because it means the mod is there and this cannot read it.
	 */
	private static Class<?> resolveExtension() {
		try {
			return Class.forName(EXTENSION_CLASS);
		} catch (ClassNotFoundException e) {
			return null;
		} catch (Throwable t) {
			logUnavailable("look up", t);
			return null;
		}
	}

	/** The accessor, bound to the interface rather than to the entity it is mixed into. */
	private static MethodHandle resolveIsRendered(Class<?> extension) {
		if (extension == null) {
			return null;
		}

		try {
			return MethodHandles.publicLookup()
					.findVirtual(extension, "isRendered", MethodType.methodType(boolean.class));
		} catch (Throwable t) {
			logUnavailable("resolve isRendered() on", t);
			return null;
		}
	}

	/**
	 * The one line, so that the two failures above say the same thing and a reader who finds one
	 * finds the other. It names what the cost is rather than what broke, because what broke is a
	 * mod's internal and what a player sees is the shadow.
	 */
	private static void logUnavailable(String what, Throwable t) {
		Vitrail.logger().warn("WynnVitrail could not {} Wynntils' entity extension, so an entity it "
				+ "has hidden from rendering will still cast a shadow", what, t);
	}

	/**
	 * Whether something else has taken this entity out of the picture.
	 * <p>
	 * False on every road that is not a yes: no Wynntils, an entity the interface is not on, a flag
	 * that says it is drawn, and a read that threw. The last of those is deliberately silent and
	 * deliberately false rather than a repeated log, for the reason a shadow is the stake here - a
	 * shadow drawn that should not have been is a blemish, and one dropped that should not have been
	 * is a hole in the world, so the doubt is resolved towards drawing it.
	 *
	 * @param entity the caster a shadow walk is about to extract, which may be null on a walk that
	 *               is still being written
	 * @return true where the entity is hidden and casts no shadow
	 */
	public static boolean isHiddenByWynntils(Entity entity) {
		if (IS_RENDERED == null || entity == null || !ENTITY_EXTENSION.isInstance(entity)) {
			return false;
		}

		try {
			return !(boolean) IS_RENDERED.invoke(entity);
		} catch (Throwable t) {
			return false;
		}
	}
}
