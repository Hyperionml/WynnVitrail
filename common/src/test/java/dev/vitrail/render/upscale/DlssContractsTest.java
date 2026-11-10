package dev.vitrail.render.upscale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DlssContractsTest {
	private static UpscaleHistory.Frame frame(long epoch, double x, int outputWidth) {
		return new UpscaleHistory.Frame(epoch, 1280, 720, outputWidth, 1080, x, 64, 0);
	}

	@Test
	void onlySubmittedFramesAdvanceJitter() {
		UpscaleHistory history = new UpscaleHistory();
		var frame = frame(1, 0, 1920);
		var first = history.jitter(frame);
		assertEquals(first, history.jitter(frame));
		assertTrue(history.requiresReset(frame));
		history.submitted(frame);
		assertFalse(history.requiresReset(frame));
		assertNotEquals(first, history.jitter(frame));
		history.invalidate();
		assertEquals(first, history.jitter(frame));
	}

	@Test
	void sceneChangesTeleportsAndBothResolutionsResetHistory() {
		UpscaleHistory history = new UpscaleHistory();
		history.submitted(frame(1, 0, 1920));
		assertFalse(history.requiresReset(frame(1, 1, 1920)));
		assertTrue(history.requiresReset(frame(2, 1, 1920)));
		assertTrue(history.requiresReset(frame(1, 17, 1920)));
		assertTrue(history.requiresReset(frame(1, 0, 2560)));
		assertTrue(history.requiresReset(new UpscaleHistory.Frame(1, 960, 540, 1920, 1080, 0, 64, 0)));
		assertThrows(IllegalArgumentException.class, () -> frame(1, Double.NaN, 1920));
	}

	@Test
	void jitterIsCenteredAndRepeatsAtScaleDependentPeriod() {
		UpscaleHistory history = new UpscaleHistory();
		var frame = frame(1, 0, 1920);
		var first = history.jitter(frame);
		for (int i = 0; i < 18; i++) {
			var jitter = history.jitter(frame);
			assertTrue(jitter.x() >= -0.5f && jitter.x() < 0.5f);
			assertTrue(jitter.y() >= -0.5f && jitter.y() < 0.5f);
			history.submitted(frame);
		}
		assertEquals(first, history.jitter(frame));
	}

	@Test
	void rejectsUnsafeFramesBeforeNativeCalls() {
		var color = new DlssBridge.Image(1, 11, 97, 1280, 720);
		var depth = new DlssBridge.Image(2, 12, 100, 1280, 720);
		var motion = new DlssBridge.Image(3, 13, 83, 1280, 720);
		var output = new DlssBridge.Image(4, 14, 97, 1920, 1080);
		assertDoesNotThrow(() -> DlssBridge.validateFrame(1, color, depth, motion, output, 0, 0, 16));
		assertThrows(IllegalArgumentException.class, () -> DlssBridge.validateFrame(
				1, color, depth, motion, new DlssBridge.Image(1, 15, 97, 1920, 1080), 0, 0, 16));
		assertThrows(IllegalArgumentException.class, () -> DlssBridge.validateFrame(
				1, color, new DlssBridge.Image(2, 12, 100, 1920, 1080), motion, output, 0, 0, 16));
		assertThrows(IllegalArgumentException.class, () -> DlssBridge.validateFrame(
				1, color, new DlssBridge.Image(1, 12, 100, 1280, 720), motion, output, 0, 0, 16));
		assertThrows(IllegalArgumentException.class, () -> DlssBridge.validateFrame(
				1, color, depth, motion, output, Float.NaN, 0, 16));
		assertThrows(IllegalArgumentException.class, () -> DlssBridge.validateFrame(
				0, color, depth, motion, output, 0, 0, 16));
		assertThrows(IllegalArgumentException.class, () -> DlssBridge.validateFrame(
				1, new DlssBridge.Image(1, 11, 37, 1280, 720), depth, motion, output, 0, 0, 16));
	}
}
