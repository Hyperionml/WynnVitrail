package dev.vitrail.render.upscale;

/** History validity belongs to successfully submitted frames, not render ticks. */
public final class UpscaleHistory {

	/** The caller changes sceneEpoch on world/pack/view-mode changes and camera cuts. */
	public record Frame(long sceneEpoch, int renderWidth, int renderHeight,
			int displayWidth, int displayHeight, double cameraX, double cameraY, double cameraZ) {
		public Frame {
			if (renderWidth <= 0 || renderHeight <= 0 || displayWidth < renderWidth
					|| displayHeight < renderHeight || !Double.isFinite(cameraX)
					|| !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
				throw new IllegalArgumentException("Invalid temporal upscale frame");
			}
		}
	}

	public record Jitter(float x, float y) {
	}

	private Frame previous;
	private long samples;

	public boolean requiresReset(Frame current) {
		if (previous == null || previous.sceneEpoch() != current.sceneEpoch()
				|| previous.renderWidth() != current.renderWidth()
				|| previous.renderHeight() != current.renderHeight()
				|| previous.displayWidth() != current.displayWidth()
				|| previous.displayHeight() != current.displayHeight()) return true;
		double dx = current.cameraX() - previous.cameraX();
		double dy = current.cameraY() - previous.cameraY();
		double dz = current.cameraZ() - previous.cameraZ();
		// Explicit cuts handle shorter teleports; this is a guard for an unreported large jump.
		return dx * dx + dy * dy + dz * dz > 16 * 16;
	}

	/** Centered Halton samples in render pixels, before conversion into clip-space offsets. */
	public Jitter jitter(Frame current) {
		double ratio = (double) current.displayWidth() / current.renderWidth();
		int phases = (int) Math.min(4096, Math.max(8, Math.ceil(8 * ratio * ratio)));
		long index = requiresReset(current) ? 1 : samples % phases + 1;
		return new Jitter(radicalInverse(index, 2) - 0.5f, radicalInverse(index, 3) - 0.5f);
	}

	/** Call only after the frame's command buffer has been submitted successfully. */
	public void submitted(Frame frame) {
		samples = requiresReset(frame) ? 1 : samples + 1;
		previous = frame;
	}

	public void invalidate() {
		previous = null;
		samples = 0;
	}

	private static float radicalInverse(long index, int base) {
		float value = 0;
		float fraction = 1.0f / base;
		while (index > 0) {
			value += (index % base) * fraction;
			index /= base;
			fraction /= base;
		}
		return value;
	}
}
