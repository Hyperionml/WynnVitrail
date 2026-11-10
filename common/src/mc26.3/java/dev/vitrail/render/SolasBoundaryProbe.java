package dev.vitrail.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.upscale.DlssBridge;
import dev.vitrail.render.upscale.DlssDevices;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VK12;

import java.util.Map;
import java.util.Set;

/** Real GPU checks for the HDR boundary, mip chains, persistent post targets and raw depth promotion. */
public final class SolasBoundaryProbe {
	private SolasBoundaryProbe() {
	}

	public static void run(GpuDevice device) {
		var backend = DlssDevices.installed();
		if (backend == null || !backend.available()) throw new IllegalStateException("DLSS backend unavailable");
		var vk = (VulkanDevice) ((GpuDeviceAccessor) device).vitrail$backend();
		Map<Integer, GpuFormat> formats = Map.of(0, GpuFormat.RG11B10_FLOAT,
				1, GpuFormat.RGBA8_UNORM, 2, GpuFormat.RGBA16_UNORM);
		Set<Integer> mips = Set.of(0, 1);
		Set<Integer> doubled = Set.of(1, 2);
		try (SolasPostTargets post = new SolasPostTargets()) {
			for (int width : new int[] {960, 1280, 960}) {
				int height = width * 9 / 16;
				var size = backend.optimalSize(width, height, DlssBridge.Quality.QUALITY);
				post.ensure(device, width, height, formats, mips, doubled, 0);
				try (var source = new TargetSurface(device, "Solas probe packed HDR", GpuFormat.RG11B10_FLOAT, false, false, size.width(), size.height());
					 var input = new TargetSurface(device, "Solas probe DLSS input", GpuFormat.RGBA16_FLOAT, false, false, size.width(), size.height());
					 var depth = new TargetSurface(device, "Solas probe forward depth", GpuFormat.R32_FLOAT, false, false, size.width(), size.height());
					 var motion = new TargetSurface(device, "Solas probe zero motion", GpuFormat.RG16_FLOAT, false, false, size.width(), size.height());
					 var rawDepth = new TargetSurface(device, "Solas probe raw depth", GpuFormat.D32_FLOAT, false, false, size.width(), size.height());
					 var fullDepth = new TargetSurface(device, "Solas probe display depth", GpuFormat.D32_FLOAT, false, false, width, height);
					 var readback = device.createBuffer(() -> "Solas boundary readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 32)) {
					var encoder = device.createCommandEncoder();
					var hdr = post.get(0, TargetSchedule.Side.MAIN);
					var history = post.get(2, TargetSchedule.Side.MAIN);
					var historyAlt = post.get(2, TargetSchedule.Side.ALT);
					encoder.clearColorTexture(historyAlt.texture(), new Vector4f(0.75f, 0.25f, 0.5f, 1));
					encoder.copyTextureToTexture(historyAlt.texture(), history.texture(), 0, 0, 0, 0, 0, width, height);
					for (int frame = 0; frame < 2; frame++) {
						if (post.ensure(device, width, height, formats, mips, doubled, 0)
								|| history != post.get(2, TargetSchedule.Side.MAIN)) {
							throw new IllegalStateException("Post history was recreated between frames");
						}
						encoder.clearColorTexture(source.texture(), new Vector4f(0.25f, 0.5f, 0.75f, 1));
						encoder.clearColorTexture(depth.texture(), new Vector4f(0.5f));
						encoder.clearColorTexture(motion.texture(), new Vector4f());
						encoder.clearDepthTexture(rawDepth.texture(), 0.375);
						backend.blit(source.storageView(), input.storageView());
						backend.blit(rawDepth.storageView(), fullDepth.storageView());
						if (!backend.record(input.storageView(), depth.storageView(), motion.storageView(), hdr.storageView(), true,
								DlssBridge.Quality.QUALITY, 0, 0, 16.667f, true)) throw new IllegalStateException("DLSS evaluation failed");
						hdr.chainWritten(false);
						if (!MipmapReduction.generate(encoder, hdr)) throw new IllegalStateException("DLSS output mip chain failed");
						encoder.copyTextureToBuffer(hdr.texture(), readback, 0, () -> {}, 0, width / 2, height / 2, 1, 1);
						encoder.copyTextureToBuffer(hdr.texture(), readback, 8, () -> {}, 1, width / 4, height / 4, 1, 1);
						encoder.copyTextureToBuffer(history.texture(), readback, 16, () -> {}, 0, 3, 1, 1, 1);
						encoder.copyTextureToBuffer(fullDepth.texture(), readback, 24, () -> {}, 0, width / 2, height / 2, 1, 1);
						vk.createCommandEncoder().submit();
						if (VK12.vkDeviceWaitIdle(vk.vkDevice()) != VK12.VK_SUCCESS) throw new IllegalStateException("GPU wait failed");
						try (var mapped = readback.map(true, false)) {
							var bytes = mapped.data();
							for (int offset : new int[] {0, 8}) {
								for (int c = 0; c < 3; c++) near(Float.float16ToFloat(bytes.getShort(offset + c * 2)), (c + 1) * 0.25f, 0.08f);
							}
							near(Short.toUnsignedInt(bytes.getShort(16)) / 65535f, 0.75f, 0.001f);
							near(bytes.getFloat(24), 0.375f, 0.00001f);
						}
					}
					backend.releaseFeature();
					System.out.println("Solas HDR/mips/history/depth boundary PASS: " + size.width() + "x" + size.height() + " -> " + width + "x" + height);
				}
			}
		}
	}

	private static void near(float value, float expected, float tolerance) {
		if (!Float.isFinite(value) || Math.abs(value - expected) > tolerance) {
			throw new IllegalStateException("Solas boundary readback: expected " + expected + ", got " + value);
		}
	}
}
