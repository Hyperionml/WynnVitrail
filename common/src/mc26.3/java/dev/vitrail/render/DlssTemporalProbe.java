package dev.vitrail.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.render.upscale.DlssBridge;
import dev.vitrail.render.upscale.DlssDevices;
import dev.vitrail.render.upscale.DlssTemporalState;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VK12;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Executes the production temporal-input shaders and NGX, checking direction, jitter and rejection. */
public final class DlssTemporalProbe {
	private DlssTemporalProbe() {}
	public static void run(GpuDevice device) {
		for (var quality : new DlssBridge.Quality[] {DlssBridge.Quality.QUALITY, DlssBridge.Quality.BALANCED,
				DlssBridge.Quality.PERFORMANCE, DlssBridge.Quality.DLAA}) runQuality(device, quality);
	}
	private static void runQuality(GpuDevice device, DlssBridge.Quality quality) {
		var backend = DlssDevices.installed();
		if (backend == null || !backend.available()) throw new IllegalStateException("Missing DLSS");
		var vk = (VulkanDevice)((GpuDeviceAccessor)device).vitrail$backend();
		int outputWidth = 960, outputHeight = 540;
		var size = backend.optimalSize(outputWidth, outputHeight, quality);
		int width = size.width(), height = size.height();
		float[] vertices = {0,0,0,0,0, 1,0,0,1,0, 1,1,0,1,1, 0,0,0,0,0, 1,1,0,1,1, 0,1,0,0,1};
		ByteBuffer bytes = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder());
		bytes.asFloatBuffer().put(vertices);
		try (var quad = device.createBuffer(() -> "Temporal probe quad", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes);
			 var color = new TargetSurface(device, "Temporal probe HDR", GpuFormat.RGBA16_FLOAT, false, false, width, height);
			 var depth = new TargetSurface(device, "Temporal probe depth", GpuFormat.R32_FLOAT, false, false, width, height);
			 var output = new TargetSurface(device, "Temporal probe result", GpuFormat.RGBA16_FLOAT, false, true, outputWidth, outputHeight);
			 var readback = device.createBuffer(() -> "Temporal probe readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 32);
			 var inputs = new DlssTemporalInputs()) {
			var state = new DlssTemporalState();
			Object world = new Object(), cameraMode = new Object();
			var projection = new Matrix4f();
			for (int frame = 0; frame < 6; frame++) {
				var camera = new Vector3d(frame >= 2 ? 0.01 : 0, frame >= 2 ? 0.02 : 0, 0);
				if (frame == 4) cameraMode = new Object();
				state.begin(world, cameraMode, width, height, outputWidth, outputHeight, camera, projection,
						1_000_000_000L + frame * 16_000_000L);
				var encoder = device.createCommandEncoder();
				float red = frame >= 3 ? 0.75f : 0.25f;
				encoder.clearColorTexture(color.texture(), new Vector4f(red, 0.5f, 0.75f, 1));
				encoder.clearColorTexture(depth.texture(), new Vector4f(0.75f));
				inputs.draw(device, quad, depth.storageView(), color, state, projection, camera);
				encoder.copyTextureToBuffer(inputs.motion().texture(), readback, 0, () -> {}, 0, width/2, height/2, 1, 1);
				encoder.copyTextureToBuffer(inputs.mask().texture(), readback, 4, () -> {}, 0, width/2, height/2, 1, 1);
				if (!backend.recordMasked(color.storageView(), depth.storageView(), inputs.motion(), output.storageView(), inputs.mask(),
						quality, state.jitter().x(), state.jitter().y(), 16, state.reset()))
					throw new IllegalStateException("Temporal NGX evaluation failed");
				inputs.retain(device, color, state);
				backend.submit(state.recorded(projection, projection, camera));
				encoder.copyTextureToBuffer(output.texture(), readback, 8, () -> {}, 0, outputWidth/2, outputHeight/2, 1, 1);
				vk.createCommandEncoder().submit();
				if (VK12.vkDeviceWaitIdle(vk.vkDevice()) != VK12.VK_SUCCESS) throw new IllegalStateException("GPU wait failed");
				try (var mapped = readback.map(true, false)) {
					var data = mapped.data();
					near(Float.float16ToFloat(data.getShort(0)), frame == 2 ? width * 0.005f : 0, 0.01f);
					near(Float.float16ToFloat(data.getShort(2)), frame == 2 ? height * 0.01f : 0, 0.01f);
					near(Byte.toUnsignedInt(data.get(4)) / 255f, frame == 0 || frame == 3 || frame == 4 ? 1 : 0, 0.01f);
					near(Float.float16ToFloat(data.getShort(8)), red, 0.15f);
				}
			}
			backend.releaseFeature();
		}
		System.out.println("DLSS temporal shaders + NGX PASS (" + quality + "): static jitter, camera XY motion, color rejection, view cut, recovery");
	}
	private static void near(float actual, float expected, float tolerance) {
		if (!Float.isFinite(actual) || Math.abs(actual - expected) > tolerance)
			throw new IllegalStateException("Temporal readback expected " + expected + ", got " + actual);
	}
}
