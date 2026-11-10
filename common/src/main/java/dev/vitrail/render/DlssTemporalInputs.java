package dev.vitrail.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.vitrail.render.upscale.DlssTemporalState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3dc;
import org.joml.Vector4f;
import java.util.Optional;
import java.util.OptionalDouble;

/** Camera reprojection plus conservative color-history rejection, not per-object motion. */
final class DlssTemporalInputs implements AutoCloseable {
	private static final String BLOCK = "WynnDlssFrame";
	private static final Identifier VERTEX = Identifier.fromNamespaceAndPath(dev.vitrail.Vitrail.MOD_ID, "dlss/temporal_vertex");
	private static final String VSH = """
		#version 460 core
		in vec3 Position;
		void main() { gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0); }
		""";
	private static final String FSH = """
		#version 460 core
		uniform sampler2D Depth;
		uniform sampler2D Current;
		uniform sampler2D Previous;
		layout(std140) uniform WynnDlssFrame {
			mat4 fromScreen;
			mat4 toPrevious;
			vec4 sizeJitter;
			vec4 historyInfo;
		};
		layout(location=0) out vec4 result;
		void main() {
			vec2 uv = gl_FragCoord.xy / sizeJitter.xy;
			vec2 center = uv - sizeJitter.zw / sizeJitter.xy;
			float depth = texture(Depth, uv).r;
			vec4 here = fromScreen * vec4(center * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
			vec4 before = toPrevious * here;
			bool invalid = historyInfo.z > 0.5 || before.w <= 0.000001;
			vec2 was = invalid ? center : before.xy / before.w * 0.5 + 0.5;
			vec2 mv = (was - center) * sizeJitter.xy;
			vec2 oldUv = was + historyInfo.xy / sizeJitter.xy;
			invalid = invalid || any(lessThan(oldUv, vec2(0.0))) || any(greaterThan(oldUv, vec2(1.0)));
			vec3 nowColor = texture(Current, uv).rgb;
			vec3 oldColor = texture(Previous, clamp(oldUv, 0.0, 1.0)).rgb;
			vec3 delta = abs(nowColor - oldColor) / max(max(nowColor, oldColor), vec3(0.05));
			float bias = smoothstep(0.08, 0.3, max(delta.r, max(delta.g, delta.b)));
			// The sky and the compressed hand depth cannot be described by world reprojection.
			if (invalid || depth >= 0.999999 || depth < 0.56) bias = 1.0;
			if (depth < 0.56) mv = vec2(0.0);
			if (invalid || any(isnan(mv)) || any(isinf(mv))) mv = vec2(0.0);
			result = MASK_OUTPUT != 0 ? vec4(bias) : vec4(mv, 0.0, 1.0);
		}
		""";
	private TargetSurface motion, mask, previous;
	private static final RenderPipeline[] pipelines = new RenderPipeline[2];
	private float previousJitterX, previousJitterY;

	/**
	 * The uniform is the same 160 bytes every frame and the sampler never changes state, so both
	 * outlive the frame. Making them per frame allocated and destroyed two Vulkan objects sixty
	 * times a second, which is driver work on the render thread and a descriptor rewrite each pass.
	 * The surface below is the thing that has to follow the size; these do not.
	 */
	private GpuBuffer frameUniform;
	private GpuSampler nearestClamp;

	void draw(GpuDevice device, GpuBuffer quad, GpuTextureView depth, TargetSurface color,
			DlssTemporalState state, Matrix4fc clip, Vector3dc camera) {
		int width = color.width(), height = color.height();
		if (motion == null || motion.width() != width || motion.height() != height) {
			close();
			motion = new TargetSurface(device, "DLSS camera motion", GpuFormat.RG16_FLOAT, false, false, width, height);
			mask = new TargetSurface(device, "DLSS current color bias", GpuFormat.R8_UNORM, false, false, width, height);
			previous = new TargetSurface(device, "DLSS previous input", GpuFormat.RGBA16_FLOAT, false, false, width, height);
			device.createCommandEncoder().clearColorTexture(previous.texture(), new Vector4f());
		}
		Matrix4f toPrevious = new Matrix4f(state.reset() ? clip : state.previousClip());
		if (!state.reset()) toPrevious.translate((float)(camera.x() - state.previousCamera().x()),
				(float)(camera.y() - state.previousCamera().y()), (float)(camera.z() - state.previousCamera().z()));
		if (frameUniform == null) {
			frameUniform = device.createBuffer(() -> BLOCK,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 160);
		}
		if (nearestClamp == null) {
			nearestClamp = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
					FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
		}
		try (var mapped = frameUniform.map(false, true)) {
			Std140Builder.intoBuffer(mapped.data()).putMat4f(new Matrix4f(clip).invert()).putMat4f(toPrevious)
					.putVec4(width, height, state.jitter().x(), state.jitter().y())
					.putVec4(previousJitterX, previousJitterY, state.reset() ? 1 : 0, 0);
		}
		// One encoder for both passes. Each used to open its own, which is a second command buffer
		// and a second submission for the same two draws against the same uniform.
		CommandEncoder encoder = device.createCommandEncoder();
		for (int index = 0; index < 2; index++) {
			int which = index;
			var fragment = Identifier.fromNamespaceAndPath(dev.vitrail.Vitrail.MOD_ID, "dlss/temporal_fragment_" + index);
			GpuFormat format = index == 0 ? GpuFormat.RG16_FLOAT : GpuFormat.R8_UNORM;
			if (pipelines[index] == null) pipelines[index] = RenderPipeline.builder()
					.withLocation(Identifier.fromNamespaceAndPath(dev.vitrail.Vitrail.MOD_ID, "dlss/temporal_" + index))
					.withVertexShader(VERTEX).withFragmentShader(fragment)
					.withBindGroupLayout(GraphicsApi.blockAndSamplers(BLOCK, "Depth", "Current", "Previous"))
					.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
					.withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
			var source = GraphicsApi.source((id, type) -> type == ShaderType.FRAGMENT
					? FSH.replace("MASK_OUTPUT", Integer.toString(which)) : VSH);
			if (!GraphicsApi.valid(GraphicsApi.compile(device, pipelines[index], source)))
				throw new IllegalStateException("DLSS temporal input shader did not compile");
			try (var pass = encoder.createRenderPass(() -> "DLSS temporal inputs",
					(index == 0 ? motion : mask).view(), Optional.empty())) {
				GraphicsApi.setPipeline(pass, pipelines[index]);
				pass.setUniform(BLOCK, frameUniform);
				pass.setVertexBuffer(0, quad.slice());
				GraphicsApi.bindTexture(pass, "Depth", depth, nearestClamp);
				GraphicsApi.bindTexture(pass, "Current", color.storageView(), nearestClamp);
				GraphicsApi.bindTexture(pass, "Previous", previous.storageView(), nearestClamp);
				pass.draw(6, 1, 0, 0);
			}
		}
	}
	GpuTextureView motion() { return motion.storageView(); }
	GpuTextureView mask() { return mask.storageView(); }
	void retain(GpuDevice device, TargetSurface color, DlssTemporalState state) {
		device.createCommandEncoder().copyTextureToTexture(color.texture(), previous.texture(), 0, 0, 0, 0, 0, color.width(), color.height());
		previousJitterX = state.jitter().x(); previousJitterY = state.jitter().y();
	}
	@Override public void close() {
		if (motion != null) motion.close();
		if (mask != null) mask.close();
		if (previous != null) previous.close();
		if (frameUniform != null) frameUniform.close();
		if (nearestClamp != null) nearestClamp.close();
		motion = mask = previous = null;
		frameUniform = null;
		nearestClamp = null;
		previousJitterX = previousJitterY = 0;
	}
}
