package dev.vitrail.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.vitrail.Vitrail;
import dev.vitrail.render.upscale.DlssBridge;
import dev.vitrail.render.upscale.GameDlssDevice;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VK12;

/** Explicit startup diagnostic using Minecraft allocations, commands, and CPU readback. */
public final class DlssDeviceProbe {
	private DlssDeviceProbe() {
	}

	public static void run(VulkanDevice device, GameDlssDevice dlss) {
		// Repeated frames exercise GENERAL -> sampled -> GENERAL; the size round trip also
		// exercises draining pending game commands before replacing the native feature.
		for (int width : new int[] {960, 1280, 960}) {
			int height = width * 9 / 16;
			var size = dlss.optimalSize(width, height, DlssBridge.Quality.QUALITY);
			try (var color = texture(device, "color", GpuFormat.RGBA16_FLOAT, size.width(), size.height(), false);
				 var depth = texture(device, "depth", GpuFormat.R32_FLOAT, size.width(), size.height(), false);
				 var motion = texture(device, "motion", GpuFormat.RG16_FLOAT, size.width(), size.height(), false);
				 var output = texture(device, "output", GpuFormat.RGBA16_FLOAT, width, height, true);
				 var colorView = device.createTextureView(color, 0, 1);
				 var depthView = device.createTextureView(depth, 0, 1);
				 var motionView = device.createTextureView(motion, 0, 1);
				 var outputView = device.createTextureView(output, 0, 1);
				 var readback = device.createBuffer(() -> "DLSS probe readback",
						 GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 8)) {
				var encoder = device.createCommandEncoder();
				for (int frame = 0; frame < 2; frame++) {
					encoder.clearColorTexture(color, new Vector4f(0.25f, 0.5f, 0.75f, 1));
					encoder.clearColorTexture(depth, new Vector4f(0.5f));
					encoder.clearColorTexture(motion, new Vector4f(0));
					if (!dlss.record(colorView, depthView, motionView, outputView, true,
							DlssBridge.Quality.QUALITY, 0, 0, 16.667f, frame == 0)) {
						throw new IllegalStateException("DLSS game-device probe evaluation failed");
					}
					encoder.copyTextureToBuffer(output, readback, 0, () -> {}, 0,
							width / 2, height / 2, 1, 1);
					encoder.submit();
					int status = VK12.vkDeviceWaitIdle(device.vkDevice());
					if (status != VK12.VK_SUCCESS) {
						throw new IllegalStateException("DLSS probe device wait failed: " + status);
					}
					try (var mapped = readback.map(true, false)) {
						for (int channel = 0; channel < 3; channel++) {
							float actual = Float.float16ToFloat(mapped.data().getShort(channel * 2));
							if (!Float.isFinite(actual) || Math.abs(actual - (channel + 1) * 0.25f) > 0.08f) {
								throw new IllegalStateException("DLSS probe unexpected output channel "
										+ channel + ": " + actual);
							}
						}
					}
				}
				Vitrail.logger().info("DLSS game-device probe passed: {}x{} -> {}x{}, two frames",
						size.width(), size.height(), width, height);
			}
		}
		dlss.releaseFeature();
		Vitrail.logger().info("DLSS game-device probe PASS: six readbacks, resize round trip and cleanup");
	}

	private static GpuTexture texture(VulkanDevice device, String label, GpuFormat format,
			int width, int height, boolean storage) {
		boolean previous = TextureUsage.storageRequested();
		TextureUsage.requestStorage(storage);
		try {
			return device.createTexture("DLSS probe " + label, GpuTexture.USAGE_TEXTURE_BINDING
					| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST, format, width, height, 1, 1);
		} finally {
			TextureUsage.requestStorage(previous);
		}
	}
}
