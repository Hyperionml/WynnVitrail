package dev.wynnvitrail;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;

/**
 * Whether the camera stands in Wynncraft's Mist Woods, and how thick the mist there is.
 * <p>
 * <strong>One biome, and nothing else.</strong> Wynncraft marks the woods with
 * {@code minecraft:mushroom_fields}, whose {@code EnvironmentAttributes} carry a close fog
 * vanilla itself would draw, and WynnIris answers by fogging the finished picture on top of
 * whatever the pack left there ({@code pathways/WynncraftBiomeFogRenderer}, called from
 * {@code IrisRenderingPipeline.java:1671-1715}). A pack draws its own fog or none, and either
 * way the woods lose theirs, so the fog is put back as a post process on the main target rather
 * than asked of the pack. Only that biome is handled - a fog forced on every biome would ride
 * over the far terrain the compatibility half serves and break it, which is the reason
 * WynnIris gives for the same restriction ({@code MixinFogRenderer.java:181-183}).
 * <p>
 * <strong>The numbers come from the game and not from a copy of them.</strong> The start and the
 * end are the camera's own {@code FOG_START_DISTANCE} and {@code FOG_END_DISTANCE}, probed at the
 * frame's tick delta exactly as WynnIris probes them ({@code MixinFogRenderer.java:195-198}); the
 * biome is the one under the camera's entity ({@code :188-193}); and the colour is not probed at
 * all but read where the frame state already keeps it, which is the same relay WynnIris ends at
 * after its own fog walk and is the fog vanilla would have drawn had the pack not taken the screen.
 * <p>
 * Noted once a frame from the frame state's own walk of the player, on the render thread, and
 * read by the pass that draws the fog from the chain's execution; {@code volatile} for the same
 * reason {@code EntityIdentifiers} is, which is that the keyword costs less than the arbitration.
 */
public final class WynncraftMist {

	/** The one biome the woods are marked with. */
	private static final String MIST_BIOME = "minecraft:mushroom_fields";

	private static volatile boolean active;

	private static volatile float start;

	private static volatile float end;

	private static final org.joml.Vector3f smooth = new org.joml.Vector3f();
	private static final org.joml.Vector3f correction = new org.joml.Vector3f();
	private static long previous;
	private static ClientLevel previousWorld;

	private WynncraftMist() {
	}

	/**
	 * Takes the frame's answer, from the same walk of the player the frame state makes.
	 *
	 * @param level     the level the camera stands in
	 * @param camera    the frame's camera, whose entity the attributes are probed on
	 * @param tickDelta how far between ticks the frame stands, which is what the attribute probe
	 *                  interpolates on
	 */
	public static void note(ClientLevel level, Camera camera, float tickDelta, org.joml.Vector4fc fog) {
		// The patch off is the whole of this: no biome is asked and the fog is left where the pack
		// drew it. Asked here rather than at the pass so that a session with the patch off pays
		// neither the biome lookup below nor the attribute probes after it.
		correction.zero();
		if (!WynncraftSettings.effects() || !WynnOption.MIST.enabled()) {
			active = false;
			previous = 0L;
			previousWorld = null;
			return;
		}

		Entity entity = camera.entity();
		if (entity == null) {
			active = false;
			previous = 0L;
			previousWorld = null;
			return;
		}

		Holder<Biome> biome = level.getBiome(entity.blockPosition());
		boolean inWoods = biome.unwrapKey()
				.map(key -> key.identifier().toString().equals(MIST_BIOME))
				.orElse(false);
		active = inWoods;
		if (inWoods) {
			start = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_START_DISTANCE, tickDelta);
			end = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_END_DISTANCE, tickDelta);
			if (WynnOption.DISTANCE.value() > 0 && end < WynnOption.DISTANCE.value()) {
				float thickness = Math.max(1.0F, end - start);
				end = WynnOption.DISTANCE.value();
				start = end - thickness;
			}
		}
		if (!WynnOption.UNTINT.enabled() || WynnOption.WARMTH.value() == 0
				|| camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) {
			previous = 0L;
			previousWorld = null;
			return;
		}
		var pure = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR, tickDelta);
		float rawLuma = fog.x() * 0.2126F + fog.y() * 0.7152F + fog.z() * 0.0722F;
		float pureLuma = pure.x() * 0.2126F + pure.y() * 0.7152F + pure.z() * 0.0722F;
		if (pureLuma > 0.0001F) {
			correction.set(pure).mul(rawLuma / pureLuma);
			correction.set(Math.clamp(correction.x, 0F, 1F), Math.clamp(correction.y, 0F, 1F), Math.clamp(correction.z, 0F, 1F));
		} else {
			correction.set(fog.x(), fog.y(), fog.z());
		}
		long now = System.nanoTime();
		float dt = Math.clamp((now - previous) * 1e-9F, 0F, 0.25F);
		float alpha = previous == 0L || previousWorld != level ? 1F : 1F - (float) Math.exp(-dt / 0.4F);
		smooth.lerp(correction, alpha);
		previous = now;
		previousWorld = level;
		correction.set(smooth).sub(fog.x(), fog.y(), fog.z()).mul(WynnOption.WARMTH.scale());
	}

	public static org.joml.Vector3fc correction() { return correction; }

	public static void blendSky(float amount) { correction.mul(1F - amount); }

	/** Whether this frame's camera stands in the woods. */
	public static boolean active() {
		return active;
	}

	/** The distance the mist starts at, in the biome's own attribute. */
	public static float start() {
		return start;
	}

	/** The distance the mist ends at, in the biome's own attribute. */
	public static float end() {
		return end;
	}
}
