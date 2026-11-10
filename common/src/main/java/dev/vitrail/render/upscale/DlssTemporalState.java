package dev.vitrail.render.upscale;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Snapshot of the last submitted reconstruction, independent of Minecraft's frame counter. */
public final class DlssTemporalState {
	private final UpscaleHistory history = new UpscaleHistory();
	private final Matrix4f previousClip = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();
	private final Vector3d previousCamera = new Vector3d();
	private Object world;
	private Object cameraMode;
	private long epoch;
	private long lastNanos;
	private long generation;
	private boolean pending;
	private boolean valid;
	private UpscaleHistory.Frame frame;
	private UpscaleHistory.Jitter jitter = new UpscaleHistory.Jitter(0, 0);
	private boolean reset = true;

	public void begin(Object world, Object cameraMode, int width, int height, int displayWidth,
			int displayHeight, Vector3dc camera, Matrix4fc projection, long nanos) {
		if (this.world != world || this.cameraMode != cameraMode || pending
				|| lastNanos == 0 || nanos - lastNanos > 250_000_000L
				|| (valid && projectionDifference(projection, previousProjection) > 0.03f)) {
			history.invalidate();
			valid = false;
			epoch++;
		}
		this.world = world;
		this.cameraMode = cameraMode;
		lastNanos = nanos;
		generation++;
		pending = true;
		frame = new UpscaleHistory.Frame(epoch, width, height, displayWidth, displayHeight,
				camera.x(), camera.y(), camera.z());
		reset = history.requiresReset(frame);
		jitter = history.jitter(frame);
	}
	private static float projectionDifference(Matrix4fc a, Matrix4fc b) {
		return Math.max(Math.abs(a.m00() - b.m00()), Math.abs(a.m11() - b.m11()));
	}
	public UpscaleHistory.Jitter jitter() { return jitter; }
	public boolean reset() { return reset || !valid; }
	public Matrix4fc previousClip() { return previousClip; }
	public Vector3dc previousCamera() { return previousCamera; }
	public Runnable recorded(Matrix4fc clip, Matrix4fc projection, Vector3dc camera) {
		long ticket = generation;
		var capturedFrame = frame;
		var capturedClip = new Matrix4f(clip);
		var capturedProjection = new Matrix4f(projection);
		var capturedCamera = new Vector3d(camera);
		pending = true;
		return () -> {
			if (ticket != generation) return;
			history.submitted(capturedFrame);
			previousClip.set(capturedClip);
			previousProjection.set(capturedProjection);
			previousCamera.set(capturedCamera);
			pending = false;
			valid = true;
		};
	}
	public void invalidate() {
		history.invalidate(); valid = false; pending = false; generation++;
	}
}
