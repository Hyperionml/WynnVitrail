package dev.vitrail.render.upscale;

import com.mojang.blaze3d.textures.GpuTextureView;

/** Version-independent access to the optional backend installed by the Vulkan device mixin. */
public final class DlssDevices {
	public interface Device {
		boolean available();
		/** Availability without the render-thread ownership check, for source-loading workers. */
		boolean operational();
		DlssBridge.OptimalSize optimalSize(int width, int height, DlssBridge.Quality quality);
		boolean record(GpuTextureView color, GpuTextureView depth, GpuTextureView motion,
				GpuTextureView output, boolean storage, DlssBridge.Quality quality,
				float jitterX, float jitterY, float deltaMillis, boolean reset);
		boolean recordMasked(GpuTextureView color, GpuTextureView depth, GpuTextureView motion,
				GpuTextureView output, GpuTextureView mask, DlssBridge.Quality quality,
				float jitterX, float jitterY, float deltaMillis, boolean reset);
		void blit(GpuTextureView from, GpuTextureView to);
		void releaseFeature();
		/** Submit recorded work without waiting for GPU completion. */
		void submit(Runnable submitted);
	}

	private static volatile Device installed;

	private DlssDevices() {
	}

	public static Device installed() {
		return installed;
	}

	public static void install(Device device) {
		installed = device;
	}

	public static void remove(Device device) {
		if (installed == device) installed = null;
	}
}
