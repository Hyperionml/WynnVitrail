package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.uniform.WorldState;
import dev.wynnvitrail.WynncraftMist;
import dev.wynnvitrail.WynncraftTransition;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * The Wynncraft effects that are painted over the finished picture rather than woven into a
 * program: the biome fog of the Mist Woods and the transition screens.
 * <p>
 * <strong>Two passes and one holding texture.</strong> Both read the colour the chain has just
 * left on the game's own target and write back over it, and a pass may not sample what it is
 * attached to, so the colour is copied into a swap texture first and sampled from there - the
 * same shape WynnIris's two renderers keep ({@code pathways/WynncraftBiomeFogRenderer} and
 * {@code WynncraftTransitionRenderer}, their swap textures and {@code copyTexSubImage2D} back).
 * The copy is a bit copy rather than a draw, which is safe here in the one way it was not for
 * {@link ChainPresent}: both sides are the game's own RGBA8.
 * <p>
 * <strong>The fog's depth is the pack's window and not the game's.</strong> The pass rebuilds a
 * view space distance out of the depth, which only means anything against the volume the depth
 * was rasterised in, and the images {@link PackDepth} serves are already in the OpenGL window a
 * pack reads - near at nought, far at one, sky at exactly one - which is the volume
 * {@code gbufferProjection} and its inverse describe. WynnIris reads the same pair of images
 * ({@code DepthTex}, {@code DepthTexNoTranslucents}) against the same inverse, and the shader
 * here is its own, the one arithmetic and the same.
 * <p>
 * <strong>The transitions are caught on the way past, and the fog fades in and out.</strong> A
 * transition is noted the moment its entity walks by and consumed here, once; the fog is noted
 * per frame and its opacity ramps towards the frame's answer by a twentieth, which is
 * WynnIris's own smoothing ({@code IrisRenderingPipeline.java:1675-1677}) and takes about a
 * second to cross either way.
 * <p>
 * <strong>The day clock and not a frame counter</strong>, for the transitions' noise, which is
 * the choice {@code WynncraftPatch#DAY_CLOCK} documents for the effects woven into a program:
 * a pattern that advanced on real time would advance while the world stood still, and the two
 * clocks that drove it on either side of a sleep would disagree.
 */
final class WynncraftOverlay {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/wynn_overlay_vertex");
	private static final Identifier FOG_FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/wynn_fog_fragment");
	private static final Identifier TRANSITION_FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/wynn_transition_fragment");

	private static final String COLOUR = "InSampler";
	private static final String SCENE_DEPTH = "DepthScene";
	private static final String OPAQUE_DEPTH = "DepthOpaque";

	private static final String FOG_BLOCK = "OfWynnFog";
	private static final String TRANSITION_BLOCK = "OfWynnTrans";

	/** Two triangles over the whole screen, the quad every full screen pass of this engine draws. */
	private static final int VERTICES = 6;

	private static final String LABEL = "Vitrail Wynncraft overlay";

	private static final String VERTEX = """
			#version 460 core

			in vec3 Position;
			in vec2 UV0;

			out vec2 ofTexCoord;

			void main() {
				ofTexCoord = UV0;
				gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	/**
	 * The Mist Woods fog, which is WynnIris's own fragment with its three inputs kept and its one
	 * setting left at the value it ships at: the sun tint reduction stands at nought, so the
	 * untinted colour it would mix towards is never mixed to and is served the tinted one.
	 * <p>
	 * The sky and the glass over it are the two cases the opaque depth serves: a fragment behind
	 * glass has to be fogged by the terrain behind it rather than by the pane, which is the
	 * reason WynnIris gives for reading two depths ({@code WynncraftBiomeFogRenderer.java:108-118}).
	 */
	private static final String FOG = """
			#version 460 core

			uniform sampler2D InSampler;
			uniform sampler2D DepthScene;
			uniform sampler2D DepthOpaque;

			layout(std140) uniform OfWynnFog {
				mat4 InvProjMat;
				vec4 FogColor;
				vec4 FogParams;
			};

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				float fogStart = FogParams.x;
				float fogEnd = FogParams.y;
				float opacity = FogParams.z;
				float fogDensity = FogParams.w;

				float depth = texture(DepthScene, ofTexCoord).r;
				vec4 existing = texture(InSampler, ofTexCoord);

				// The fog colour with the minimum brightness the woods are drawn with, which is
				// WynnIris's own floor: dense fog scatters light, so even at night it reads as
				// mist rather than as a hole ({@code WynncraftBiomeFogRenderer.java:89-100}).
				float fogLuma = dot(FogColor.rgb, vec3(0.2126, 0.7152, 0.0722));
				vec3 fog = FogColor.rgb;
				if (fogLuma < 0.12 && fogLuma > 0.001) {
					fog = FogColor.rgb * (0.12 / fogLuma);
				} else if (fogLuma <= 0.001) {
					fog = vec3(0.08, 0.10, 0.08);
				}

				// Sky pixels, which are the far plane in the window this depth is served in.
				if (depth > 0.999999) {
					ofFragData0 = vec4(mix(existing.rgb, fog, fogDensity * opacity), existing.a);
					return;
				}

				// The opaque depth, for the distance: a pane of glass is not what the mist sits
				// over, and reading the scene depth there would fog the pane instead of the world
				// behind it.
				float opaqueDepth = texture(DepthOpaque, ofTexCoord).r;
				if (opaqueDepth > 0.999999) {
					ofFragData0 = vec4(mix(existing.rgb, fog, fogDensity * opacity), existing.a);
					return;
				}

				// The linear distance, rebuilt against the inverse of the projection in the
				// window the depth is served in.
				vec4 viewPos = InvProjMat * vec4(ofTexCoord * 2.0 - 1.0, opaqueDepth * 2.0 - 1.0, 1.0);
				float linearDist = (abs(viewPos.w) > 1e-6) ? -viewPos.z / viewPos.w : 0.0;
				linearDist = max(linearDist, 0.0);

				// Linear fog, with the degenerate range WynnIris guards: a start past the end
				// would divide by nought and take the screen with it.
				float thickness = fogEnd - fogStart;
				float fogFactor = thickness > 0.001
					? clamp((linearDist - fogStart) / thickness, 0.0, 1.0)
					: (linearDist > fogStart ? 1.0 : 0.0);
				fogFactor *= fogDensity * opacity;

				ofFragData0 = vec4(mix(existing.rgb, fog, fogFactor), existing.a);
			}
			""";

	/**
	 * The transition screens, which are the resource pack's own nineteen patterns. The arithmetic
	 * of each is the pack's, transcribed from WynnIris's fragment ({@code
	 * WynncraftTransitionRenderer.java:59-103}), noise and all: the hash, the two noise calls and
	 * the fbm are the pattern's own and the day clock drives them, which is
	 * {@code WynncraftPatch#DAY_CLOCK}'s argument in a pass rather than a program.
	 * <p>
	 * The blend is the pack's own too: the pattern's alpha decides how much of the finished
	 * picture shows through, and a kind standing at nought - the entity has not arrived yet, or
	 * the frame is between two screens - discards, which leaves the picture exactly as it was.
	 */
	private static final String TRANSITION = """
			#version 460 core

			uniform sampler2D InSampler;

			layout(std140) uniform OfWynnTrans {
				vec4 TransColor;
				vec4 TransParams;
				vec4 ScreenSize;
			};

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			const float PI = 3.14159265359;
			const float TAU = PI * 2.0;

			int wynnHash(int x) { x += (x << 10); x ^= (x >> 6); x += (x << 3); x ^= (x >> 11); x += (x << 15); return x; }
			float wynnNoise2(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
			float wynnNoiseT(vec2 uv, float t1, float t2) { return fract(sin(uv.x * t1 + uv.y * t2) * 56789.0); }
			float wynnSmoothNoise(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); float a = wynnNoise2(i); float b = wynnNoise2(i + vec2(1.0, 0.0)); float c = wynnNoise2(i + vec2(0.0, 1.0)); float d = wynnNoise2(i + vec2(1.0, 1.0)); vec2 u = f * f * (3.0 - 2.0 * f); return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y; }
			float wynnFbm(vec2 p) { float v = 0.0; float a = 0.5; float freq = 1.0; for (int i = 0; i < 5; i++) { v += a * wynnSmoothNoise(p * freq); freq *= 2.0; a *= 0.5; } return v; }

			void main() {
				float gameTime = TransParams.x;
				float rawA = TransParams.y;
				int transType = int(TransParams.z);
				if (transType <= 0) { discard; }

				vec2 ss = ScreenSize.xy;
				vec2 cuv = gl_FragCoord.xy / ss - 0.5;
				float ar = ss.y / ss.x;
				vec2 UV = cuv / vec2(ar, 1.0);
				float prog = cos(rawA * PI / 2.0);
				float gt = gameTime;
				vec3 rgb = TransColor.rgb;
				vec4 result = vec4(0.0);

				switch (transType) {
				    case 1: { result = vec4(rgb, (length((gl_FragCoord.xy / ss - 0.5) / vec2(ar, 1.0)) + 0.1 - prog * 1.5) * (1.0 - prog) * 100.0); break; }
				    case 2: { result = vec4(rgb, clamp(length(cuv * vec2(1.0, 2.0 / max(1.0 - rawA, 0.001))) - 1.0, 0.0, 1.0)); break; }
				    case 3: { result = vec4(0.0); float a3 = (atan(cuv.y, cuv.x) / PI / 2.0 + 0.5) * 30.0; float t3 = gt * 2000.0 + float(wynnHash(int(a3)) % 100) * 64.2343; float s3 = (abs(fract(a3) - 0.5) * 20.0 / 30.0 - 0.2) * length(cuv) + 0.07 + (1.0 - rawA) * 0.05 + abs(fract(t3) - 0.5) * 0.25; if (s3 < 0.0) result = vec4(rgb, clamp(-s3 * 200.0, 0.0, 1.0)); break; }
				    case 4: { vec2 g4 = vec2(ivec2(gl_FragCoord.xy / 64.0) * 64); vec2 ig4 = gl_FragCoord.xy - g4 - 32.0; float sz4 = (g4.y / ss.y - rawA * 2.0 + 1.0) * 64.0; result = (abs(ig4.x) + abs(ig4.y) > sz4) ? vec4(rgb, 1.0) : vec4(0.0); break; }
				    case 5: { ivec2 g5 = ivec2(gl_FragCoord.xy / 64.0) * 64; result = abs(wynnHash(g5.x ^ wynnHash(g5.y)) % 256) < int(rawA * (length(vec2(g5) / ss - 0.5) * 2.0 + 1.0) * 256.0) ? vec4(rgb, 1.0) : vec4(0.0); break; }
				    case 6: { result = vec4(0.0); float r6 = length(UV); if (r6 >= 0.07 && r6 < 0.1 && rawA >= 0.99) { float a6 = fract(-atan(UV.y, UV.x) / TAU - gt * 1000.0); result = vec4(rgb, a6); } break; }
				    case 7: { vec2 c7 = cuv * (ss.x / ss.y); float d7 = length(c7) / length(vec2(1.0, ss.y / ss.x)); result = vec4(rgb, smoothstep(0.0, 1.0, d7 * 0.4)) * (1.0 - prog); break; }
				    case 8: { float s8 = 2.0 - abs((cuv.y - 0.5) / max(1.0 - prog, 0.001) - 1.0); result = vec4(rgb, step(0.0, s8)); break; }
				    case 9: { float s9 = 2.0 - abs((cuv.y + 0.5) / max(1.0 - prog, 0.001) - 1.0); result = vec4(rgb, step(0.0, s9)); break; }
				    case 10: { float s10 = 2.0 - abs((cuv.x - 0.5) / max(1.0 - prog, 0.001) - 1.0); result = vec4(rgb, step(0.0, s10)); break; }
				    case 11: { float s11 = 2.0 - abs((cuv.x + 0.5) / max(1.0 - prog, 0.001) - 1.0); result = vec4(rgb, step(0.0, s11)); break; }
				    case 12: { result = vec4(rgb, 1.0 - prog); break; }
				    case 13: { result = vec4(0.0); float top = 2.0 - abs((cuv.y - 0.5) / max(1.0 - prog, 0.001)); float bot = 2.0 - abs((cuv.y + 0.5) / max(1.0 - prog, 0.001)); if (UV.y > 0.4) result = vec4(rgb, step(0.0, top)); if (UV.y < -0.4) result = vec4(rgb, step(0.0, bot)); break; }
				    case 14: { float cp14 = atan(UV.y, UV.x) + prog * 2.0; result = vec4(rgb, step(sign(prog - mod(cp14, PI / 4.0)), 0.5)); break; }
				    case 15: { float os15 = PI / 2.0; float a15 = atan(UV.y, UV.x) + os15; float n15 = (a15 + PI) / TAU; n15 = n15 - floor(n15); result = vec4(rgb, step(n15, 1.0 - prog)); break; }
				    case 16: { float t16 = gt * 2000.0; result = vec4(rgb, wynnNoiseT(UV, t16 * 0.654321, t16 * (t16 * 0.654321 * 0.123456)) * (1.0 - prog)); break; }
				    case 17: { vec2 c17 = cuv * (ss.x / ss.y); float d17 = length(c17) / length(vec2(1.0, ss.y / ss.x)); float v17 = smoothstep(0.0, 1.0, d17 * 0.5) * wynnFbm(c17 * 1.5 + vec2(gt * 4000.0 * 0.1, 0.0)); result = vec4(rgb, v17) * (1.0 - prog); break; }
				    case 18: { vec2 c18 = cuv * (ss.x / ss.y); float d18 = length(c18) / length(vec2(1.0, ss.y / ss.x)); float p18 = (ss.x / ss.y) + 0.1 * sin(gt * 3000.0); result = vec4(rgb, smoothstep(0.1, 1.0, d18 * 0.25 * p18)) * (1.0 - prog); break; }
				    case 19: { vec2 uv19 = (gl_FragCoord.xy / ss) * 2.0 - 1.0; uv19.x *= ss.x / ss.y; float p19 = rawA; float t19 = p19 * 8.5; float r19 = length(uv19); float th19 = atan(uv19.y, uv19.x); th19 += mix(0.0, 4.0, p19) * sin(r19 * 8.0 - t19 * 3.0) * (1.0 - p19); float mr19 = length(vec2(ss.x / ss.y, 1.0)); float a19 = smoothstep((1.0 - p19) * mr19 - 0.25, (1.0 - p19) * mr19, r19); float sc19 = sin(th19 * 6.0 + t19) * 0.5 + 0.5; vec3 pc19 = mix(vec3(0.741, 0.282, 0.910), vec3(0.545, 0.098, 0.749), sc19); result = mix(vec4(pc19, a19), vec4(0.667, 0.153, 0.812, 1.0), smoothstep(0.8, 1.0, p19)); break; }
				}

				vec4 existing = texture(InSampler, ofTexCoord);
				ofFragData0 = mix(existing, vec4(result.rgb, 1.0), result.a);
			}
			""";

	private static final ShaderSource SOURCE = GraphicsApi.source((id, type) -> {
		if (type == ShaderType.FRAGMENT) {
			if (FOG_FRAGMENT_ID.equals(id)) {
				return FOG;
			}

			return TRANSITION_FRAGMENT_ID.equals(id) ? TRANSITION : null;
		}

		return VERTEX_ID.equals(id) ? VERTEX : null;
	});

	/** The fog block: one matrix, two colours' worth of parameters, and the screen. */
	private static final int FOG_BLOCK_BYTES = 96;

	/** The transition block: three vec4s. */
	private static final int TRANSITION_BLOCK_BYTES = 48;

	/** How far the fog's opacity moves towards its answer in one frame, WynnIris's own rate. */
	private static final float FOG_RAMP = 0.05F;

	private final String swapLabel;

	private RenderPipeline fogPipeline;
	private RenderPipeline transitionPipeline;
	private TargetSurface swap;
	private MappableRingBuffer fogBlock;
	private MappableRingBuffer transitionBlock;

	/** That the pipelines did not compile, said once rather than per frame. */
	private boolean refused;

	/** The fog's opacity, ramped rather than taken, for the biome transitions. */
	private float fogOpacity;

	WynncraftOverlay() {
		this.swapLabel = LABEL + ", swap";
	}

	/**
	 * Draws the two effects over the finished picture, in WynnIris's order: the fog first, so that
	 * whatever comes after it is drawn over misted terrain, and the transition last, over
	 * everything.
	 * <p>
	 * Must run on the render thread and outside any render pass, which is where the chain calls
	 * it from - after the whole chain and its final, before the kept targets are copied back.
	 *
	 * @param quad     the two triangles every full screen pass of this engine draws
	 * @param colour   the game's own colour target, which holds the finished picture
	 * @param scene    the whole scene's depth in the pack's window, or null on a frame that kept
	 *                 none, which skips the fog
	 * @param opaque   the opaque world's depth in the pack's window, or null likewise
	 * @param world    the frame's state, whose inverse projection and fog colour the fog reads
	 */
	void draw(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, GpuTextureView colour,
			GpuTextureView scene, GpuTextureView opaque, WorldState world) {
		if (this.refused || quad == null || colour == null) {
			return;
		}

		// The ramp moves every frame the woods are entered or left, whether this frame draws the
		// fog or not: a frame without its depths starts the next one from where this one ended,
		// and the fade out runs on the frames after the woods are gone, which draw nothing else.
		rampFog(WynncraftMist.active());

		boolean wantsFog = WynncraftMist.active() && scene != null && opaque != null
				&& this.fogOpacity > 0.001F;
		boolean wantsTransition = WynncraftTransition.consume();
		if (!wantsFog && !wantsTransition) {
			return;
		}

		int width = colour.getWidth(0);
		int height = colour.getHeight(0);
		if (!ensure(width, height)) {
			return;
		}

		// The picture into the swap, as a bit copy: both sides are the game's own RGBA8, and the
		// pass about to run samples the one while attached to the other. The textures and not the
		// views, which is what a transfer takes; level nought and the origin both ways, the whole
		// picture being what is copied.
		encoder.copyTextureToTexture(colour.texture(), this.swap.texture(), 0, 0, 0, 0, 0, width,
				height);

		if (wantsFog) {
			drawFog(encoder, device, quad, colour, scene, opaque, world);
		}

		if (wantsTransition) {
			drawTransition(encoder, device, quad, colour, world, width, height);
		}
	}

	private void drawFog(CommandEncoder encoder, GpuDevice device, GpuBuffer quad,
			GpuTextureView colour, GpuTextureView scene, GpuTextureView opaque, WorldState world) {
		RenderPipeline compiled = fogPipeline(device);
		if (compiled == null) {
			return;
		}

		// The density WynnIris's own curve answers for the hundred its slider ships at, spelled
		// out rather than folded: an inverse power over the complement, which pulls the middle of
		// the range up towards one so a fraction of the fog keeps more of its presence than the
		// number suggests ({@code IrisRenderingPipeline.java:1700-1701}). At the hundred itself
		// the complement is nought and the curve is one, which is the full fog.
		float density = 1.0F - (float) Math.pow(1.0F - 1.0F, 1.5F);

		this.fogBlock.rotate();
		try (GpuBufferSlice.MappedView view =
				this.fogBlock.currentBuffer().map(false, true)) {
			Std140Builder.intoBuffer(view.data())
					.putMat4f(world.gbufferProjectionInverse())
					.putVec4(world.fogR(), world.fogG(), world.fogB(), 1.0F)
					.putVec4(WynncraftMist.start(), WynncraftMist.end(), this.fogOpacity, density);
		}

		try (RenderPass pass = encoder.createRenderPass(() -> LABEL + " fog", colour,
				Optional.empty())) {
			GraphicsApi.setPipeline(pass, compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(FOG_BLOCK, this.fogBlock.currentBuffer());
			pass.setVertexBuffer(0, quad.slice());
			bindClamped(pass, COLOUR, this.swap.view());
			bindClamped(pass, SCENE_DEPTH, scene);
			bindClamped(pass, OPAQUE_DEPTH, opaque);
			pass.draw(VERTICES, 1, 0, 0);
		}
	}

	private void drawTransition(CommandEncoder encoder, GpuDevice device, GpuBuffer quad,
			GpuTextureView colour, WorldState world, int width, int height) {
		RenderPipeline compiled = transitionPipeline(device);
		if (compiled == null) {
			return;
		}

		// The day as a fraction, which is the clock WynnIris drives its transitions on
		// ({@code computeWynncraftGameTime}): the noise advances with the world and not with the
		// frame rate, and a world standing still has a pattern standing still with it.
		float day = (world.worldTime() + world.partialTick()) / 24000.0F;
		int rgb = WynncraftTransition.colour();

		this.transitionBlock.rotate();
		try (GpuBufferSlice.MappedView view =
				this.transitionBlock.currentBuffer().map(false, true)) {
			Std140Builder.intoBuffer(view.data())
					.putVec4(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F,
							(rgb & 0xFF) / 255.0F, 1.0F)
					.putVec4(day, WynncraftTransition.progress(), WynncraftTransition.kind(), 0.0F)
					.putVec4(width, height, 0.0F, 0.0F);
		}

		try (RenderPass pass = encoder.createRenderPass(() -> LABEL + " transition", colour,
				Optional.empty())) {
			GraphicsApi.setPipeline(pass, compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(TRANSITION_BLOCK, this.transitionBlock.currentBuffer());
			pass.setVertexBuffer(0, quad.slice());
			bindClamped(pass, COLOUR, this.swap.view());
			pass.draw(VERTICES, 1, 0, 0);
		}

		WynncraftTransition.taken();
	}

	private void bindClamped(RenderPass pass, String name, GpuTextureView view) {
		GraphicsApi.bindTexture(pass, name, view,
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
	}

	/** Moves the fog's opacity one frame's worth towards its answer. */
	private void rampFog(boolean towardsActive) {
		float target = towardsActive ? 1.0F : 0.0F;
		this.fogOpacity += (target - this.fogOpacity) * FOG_RAMP;
		if (Math.abs(this.fogOpacity - target) < 0.005F) {
			this.fogOpacity = target;
		}
	}

	/** Makes the swap and the two blocks exist at this size. */
	private boolean ensure(int width, int height) {
		try {
			if (this.swap == null) {
				this.swap = new TargetSurface(this.swapLabel, GpuFormat.RGBA8_UNORM, false,
						width, height);
			} else if (this.swap.width() != width || this.swap.height() != height) {
				this.swap.resize(width, height);
			}

			if (this.fogBlock == null) {
				this.fogBlock = new MappableRingBuffer(() -> LABEL + " fog",
						GpuBuffer.USAGE_UNIFORM
								| GpuBuffer.USAGE_MAP_WRITE,
						FOG_BLOCK_BYTES);
			}

			if (this.transitionBlock == null) {
				this.transitionBlock = new MappableRingBuffer(() -> LABEL + " transition",
						GpuBuffer.USAGE_UNIFORM
								| GpuBuffer.USAGE_MAP_WRITE,
						TRANSITION_BLOCK_BYTES);
			}

			return true;
		} catch (GpuDeviceLossException e) {
			throw e;
		} catch (RuntimeException e) {
			release();
			this.refused = true;
			Vitrail.logger().error("Vitrail could not allocate the Wynncraft overlay pass, so the "
					+ "Mist Woods fog and the transition screens are not drawn", e);
			return false;
		}
	}

	private RenderPipeline fogPipeline(GpuDevice device) {
		if (this.fogPipeline == null) {
			this.fogPipeline = RenderPipeline.builder()
					.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID,
							"pipeline/wynn_fog"))
					.withVertexShader(VERTEX_ID)
					.withFragmentShader(FOG_FRAGMENT_ID)
					.withBindGroupLayout(BindGroupLayouts.GLOBALS)
					.withBindGroupLayout(GraphicsApi.blockAndSamplers(FOG_BLOCK, COLOUR,
							SCENE_DEPTH, OPAQUE_DEPTH))
					.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
					.withColorTargetState(new ColorTargetState(Optional.empty(),
							GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.withCull(false)
					.build();
		}

		if (GraphicsApi.valid(GraphicsApi.compile(device, this.fogPipeline, SOURCE))) {
			return this.fogPipeline;
		}

		return null;
	}

	private RenderPipeline transitionPipeline(GpuDevice device) {
		if (this.transitionPipeline == null) {
			this.transitionPipeline = RenderPipeline.builder()
					.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID,
							"pipeline/wynn_transition"))
					.withVertexShader(VERTEX_ID)
					.withFragmentShader(TRANSITION_FRAGMENT_ID)
					.withBindGroupLayout(BindGroupLayouts.GLOBALS)
					.withBindGroupLayout(GraphicsApi.blockAndSamplers(TRANSITION_BLOCK, COLOUR))
					.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
					.withColorTargetState(new ColorTargetState(Optional.empty(),
							GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.withCull(false)
					.build();
		}

		if (GraphicsApi.valid(GraphicsApi.compile(device, this.transitionPipeline, SOURCE))) {
			return this.transitionPipeline;
		}

		return null;
	}

	/** Frees the swap and the two blocks. Called where the chain releases its own. */
	void release() {
		if (this.swap != null) {
			this.swap.close();
			this.swap = null;
		}

		if (this.fogBlock != null) {
			this.fogBlock.close();
			this.fogBlock = null;
		}

		if (this.transitionBlock != null) {
			this.transitionBlock.close();
			this.transitionBlock = null;
		}

		this.fogOpacity = 0.0F;
	}
}
