package dev.vitrail.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vitrail.render.upscale.DlssBridge;
import dev.vitrail.render.upscale.DlssDevices;
import org.joml.Vector4f;

/** HDR boundary before Solas composite13, with optional spatial-only diagnostics. */
final class SolasUpscaleBoundary implements AutoCloseable {
	private TargetSurface hdrInput;
	private TargetSurface zeroMotion;
	private int outputWidth;
	private int outputHeight;
	private DlssDevices.Device backend;
	private final dev.vitrail.render.upscale.DlssTemporalState temporal = new dev.vitrail.render.upscale.DlssTemporalState();
	private final DlssTemporalInputs inputs = new DlssTemporalInputs();
	private Object cameraEntity;

	void begin(PackValues values, int width, int height, Object world, Object cameraMode) {
		Object nextCamera = net.minecraft.client.Minecraft.getInstance().getCameraEntity();
		if (cameraEntity != nextCamera || dev.wynnvitrail.ambience.WynnAmbience.switching()) temporal.invalidate();
		cameraEntity = nextCamera;
		var state = values.world();
		temporal.begin(world, cameraMode, (int)state.viewWidth(), (int)state.viewHeight(), width, height,
				state.cameraPositionUnshifted(), state.gbufferProjection(), System.nanoTime());
		values.dlssJitter(2 * temporal.jitter().x() / state.viewWidth(), 2 * temporal.jitter().y() / state.viewHeight());
	}

	void reconstruct(GpuDevice device, ColorTargets targets, TargetSurface source, GpuTextureView depth,
			int width, int height, float deltaMillis, com.mojang.blaze3d.buffers.GpuBuffer quad, PackValues values, boolean useTemporal) {
		DlssDevices.Device current = DlssDevices.installed();
		if (current == null || !current.available() || source == null || depth == null) {
			throw new IllegalStateException("Solas DLSS probe requires a live backend, HDR color and scene depth");
		}
		if (hdrInput == null || hdrInput.width() != source.width() || hdrInput.height() != source.height()
				|| outputWidth != width || outputHeight != height || backend != current) {
			close();
			backend = current;
			try {
				hdrInput = new TargetSurface(device, "Solas DLSS HDR input", GpuFormat.RGBA16_FLOAT,
						false, false, source.width(), source.height());
				zeroMotion = new TargetSurface(device, "Solas DLSS diagnostic zero motion", GpuFormat.RG16_FLOAT,
						false, false, source.width(), source.height());
				device.createCommandEncoder().clearColorTexture(zeroMotion.texture(), new Vector4f());
				outputWidth = width;
				outputHeight = height;
			} catch (RuntimeException e) {
				close();
				throw e;
			}
		}
		TargetSurface output = targets.prepareSolasOutput(device, width, height);
		backend.blit(source.storageView(), hdrInput.storageView());
		try {
			boolean success;
			if (useTemporal) {
				var world = values.world();
				var clip = new org.joml.Matrix4f(world.gbufferProjection()).mul(world.gbufferModelView());
				inputs.draw(device, quad, depth, hdrInput, temporal, clip, world.cameraPositionUnshifted());
				success = backend.recordMasked(hdrInput.storageView(), depth, inputs.motion(), output.storageView(), inputs.mask(),
						dev.vitrail.render.upscale.DlssSettings.quality(), temporal.jitter().x(), temporal.jitter().y(),
						Math.max(0.001f, deltaMillis), temporal.reset());
				if (success) {
					inputs.retain(device, hdrInput, temporal);
					backend.submit(temporal.recorded(clip, world.gbufferProjection(), world.cameraPositionUnshifted()));
				}
			} else success = backend.record(hdrInput.storageView(), depth, zeroMotion.storageView(), output.storageView(), true,
					DlssBridge.Quality.QUALITY, 0, 0, Math.max(0.001f, deltaMillis), true);
			if (!success) throw new IllegalStateException("DLSS evaluation rejected");
		} catch (com.mojang.blaze3d.GpuDeviceLossException e) {
			throw e;
		} catch (RuntimeException | LinkageError e) {
			temporal.invalidate();
			dev.vitrail.render.upscale.DlssSettings.fail(e.toString());
			dev.vitrail.Vitrail.logger().error("DLSS failed; presenting spatial fallback and restoring the normal pack next frame", e);
			backend.blit(hdrInput.storageView(), output.storageView());
		}
		output.chainWritten(false);
		targets.solasDisplayResolution();
	}

	@Override
	public void close() {
		try {
			if (backend != null) backend.releaseFeature();
		} finally {
			if (hdrInput != null) hdrInput.close();
			if (zeroMotion != null) zeroMotion.close();
			inputs.close();
			hdrInput = zeroMotion = null;
			backend = null;
			outputWidth = outputHeight = 0;
		}
	}
}
