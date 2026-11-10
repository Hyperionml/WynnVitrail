package dev.vitrail.render.upscale;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DlssTemporalStateTest {
	@Test void commitsOnlySubmittedFramesAndDiscardsStaleCallbacks() {
		var state = new DlssTemporalState(); var world = new Object(); var mode = new Object();
		var matrix = new Matrix4f(); var camera = new Vector3d();
		state.begin(world, mode, 640, 360, 960, 540, camera, matrix, 1);
		assertTrue(state.reset()); var first = state.jitter();
		Runnable stale = state.recorded(matrix, matrix, camera);
		state.begin(world, mode, 640, 360, 960, 540, camera, matrix, 16_000_001);
		stale.run(); assertTrue(state.reset()); assertEquals(first, state.jitter());
		state.recorded(matrix, matrix, camera).run();
		state.begin(world, mode, 640, 360, 960, 540, camera, matrix, 32_000_001);
		assertFalse(state.reset()); assertNotEquals(first, state.jitter());
		// No evaluation was recorded for this frame. The next frame cannot reuse its history.
		state.begin(world, mode, 640, 360, 960, 540, camera, matrix, 48_000_001);
		assertTrue(state.reset());
	}
	@Test void cutsResizeWorldViewFovPauseAndTeleportResetHistory() {
		for (int change = 0; change < 7; change++) {
			var state = new DlssTemporalState(); var world = new Object(); var mode = new Object();
			var projection = new Matrix4f(); var camera = new Vector3d();
			state.begin(world, mode, 640, 360, 960, 540, camera, projection, 1);
			state.recorded(projection, projection, camera).run();
			if (change == 0) world = new Object();
			if (change == 1) mode = new Object();
			if (change == 2) camera.x = 40;
			if (change == 3) projection.m00(1.3f);
			if (change == 6) state.invalidate();
			state.begin(world, mode, change == 4 ? 600 : 640, 360, 960, 540, camera, projection,
					change == 5 ? 500_000_001 : 16_000_001);
			assertTrue(state.reset(), "change=" + change);
		}
	}
	@Test void validatesReactiveMaskExtentFormatAndAliasing() {
		var color = new DlssBridge.Image(1, 11, 97, 640, 360);
		var depth = new DlssBridge.Image(2, 12, 100, 640, 360);
		var motion = new DlssBridge.Image(3, 13, 83, 640, 360);
		var output = new DlssBridge.Image(4, 14, 97, 960, 540);
		assertDoesNotThrow(() -> DlssBridge.validateMask(new DlssBridge.Image(5, 15, 9, 640, 360), color, depth, motion, output));
		for (var mask : java.util.List.of(new DlssBridge.Image(5, 15, 97, 640, 360),
				new DlssBridge.Image(5, 15, 9, 960, 540), new DlssBridge.Image(1, 15, 9, 640, 360)))
			assertThrows(IllegalArgumentException.class, () -> DlssBridge.validateMask(mask, color, depth, motion, output));
	}
}
