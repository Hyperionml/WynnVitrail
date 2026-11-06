package dev.vitrail.render;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.wynnvitrail.WynnOption;
import dev.wynnvitrail.ambience.WynnAmbience;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Retain the last world image across an ambience reload, before the HUD is drawn. */
final class AmbienceFrameHistory {
	private static TargetSurface history;
	private static ClientLevel level;

	private AmbienceFrameHistory() {}

	static void present(GpuDevice device, GpuTextureView colour, boolean switched) {
		var current = Minecraft.getInstance().level;
		boolean tracking = WynnOption.AMBIENCE.enabled() || WynnAmbience.warming();
		if (level != current || current == null || (!tracking && !switched)) {
			close();
		}
		if (current == null || (!tracking && history == null)) return;
		var main = colour.texture();
		int width = colour.getWidth(0);
		int height = colour.getHeight(0);
		if (history == null || !history.reusable(main.getFormat(), false, false, width, height)) {
			close();
			if (!tracking) return;
			history = new TargetSurface("Ambience previous frame", main.getFormat(), false, width, height);
			level = current;
		} else if (switched) {
			device.createCommandEncoder().copyTextureToTexture(history.texture(), main, 0, 0, 0, 0, 0, width, height);
			return;
		}
		device.createCommandEncoder().copyTextureToTexture(main, history.texture(), 0, 0, 0, 0, 0, width, height);
	}

	static void close() {
		if (history != null) history.close();
		history = null;
		level = null;
	}
}
