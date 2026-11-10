package dev.vitrail.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.vitrail.pack.target.TargetSchedule;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Display-resolution targets that survive while the next frame renders into its low-res bank. */
final class SolasPostTargets implements AutoCloseable {
	private final Map<Integer, TargetSurface> main = new HashMap<>();
	private final Map<Integer, TargetSurface> alt = new HashMap<>();
	private int width;
	private int height;

	boolean ensure(GpuDevice device, int width, int height, Map<Integer, GpuFormat> formats,
			Set<Integer> mipmapped, Set<Integer> doubled) {
		if (this.width == width && this.height == height && !main.isEmpty()) return false;
		close();
		try {
			for (int index : new int[] {0, 1, 2}) {
				GpuFormat format = index == 0 ? GpuFormat.RGBA16_FLOAT : formats.get(index);
				if (format == null) throw new IllegalArgumentException("Solas target missing: " + index);
				main.put(index, new TargetSurface(device, "Solas DLSS colortex" + index, format,
						mipmapped.contains(index), index == 0, width, height));
				if (index != 0 && doubled.contains(index)) {
					alt.put(index, new TargetSurface(device, "Solas DLSS colortex" + index + " alt", format,
							mipmapped.contains(index), false, width, height));
				}
			}
			CommandEncoder encoder = device.createCommandEncoder();
			main.values().forEach(surface -> encoder.clearColorTexture(surface.texture(), new Vector4f()));
			alt.values().forEach(surface -> encoder.clearColorTexture(surface.texture(), new Vector4f()));
			this.width = width;
			this.height = height;
			return true;
		} catch (RuntimeException e) {
			close();
			throw e;
		}
	}

	TargetSurface get(int index, TargetSchedule.Side side) {
		return side == TargetSchedule.Side.ALT ? alt.getOrDefault(index, main.get(index)) : main.get(index);
	}

	@Override
	public void close() {
		main.values().forEach(TargetSurface::close);
		alt.values().forEach(TargetSurface::close);
		main.clear();
		alt.clear();
		width = height = 0;
	}
}
