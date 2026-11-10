package dev.vitrail.dlssprobe;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.render.upscale.GameDlssDevice;
import net.minecraft.SharedConstants;
import org.lwjgl.sdl.SDLInit;

/** Invoked inside Knot so these are the same transformed classes that the mod uses in Minecraft. */
public final class GameProbe {
	private GameProbe() {
	}

	public static void run() throws Exception {
		SharedConstants.tryDetectVersion();
		NativeLibrariesBootstrap.loadLibraries();
		com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
		System.setProperty("vitrail.keepPackAcrossReload", "false");
		// The standalone probe has no Minecraft renderer constructor. Supply its device to the
		// real shader compiler, without constructing a world or replacing any production hooks.
		var renderDevice = com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("DEVICE");
		renderDevice.setAccessible(true);
		if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException("SDL video init failed");
		VulkanBackend backend = new VulkanBackend();
		try {
			backend.loadLibrary();
			for (int cycle = 0; cycle < 2; cycle++) {
				GpuDevice device = backend.createDevice(new GpuDebugOptions(1, true, true, true));
				renderDevice.set(null, device);
				try {
					var vk = ((GpuDeviceAccessor) device).vitrail$backend();
					var dlss = ((GameDlssDevice.Owner) vk).vitrail$dlssDevice();
					boolean expected = Boolean.getBoolean("dlss.probe.expectBackend");
					if (expected ? dlss == null || !dlss.available() : dlss != null) {
						throw new IllegalStateException("Unexpected DLSS availability; inspect log");
					}
					if (System.getProperty("dlss.probe.mode").equals("solas")) {
						dev.vitrail.render.SolasBoundaryProbe.run(device);
					}
					if (System.getProperty("dlss.probe.mode").equals("temporal")) {
						dev.vitrail.render.DlssTemporalProbe.run(device);
					}
				} finally {
					dev.vitrail.render.GraphicsApi.purge();
					renderDevice.set(null, null);
					device.close();
				}
			}
		} finally {
			backend.unloadLibrary();
			SDLInit.SDL_Quit();
		}
		System.out.println("Minecraft Vulkan + Fabric mixins: PASS (two device lifetimes, "
				+ System.getProperty("dlss.probe.mode") + ")");
	}
}
