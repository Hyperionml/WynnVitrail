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
	/** Which surface the reconstruction writes into; a different one needs its own allocation. */
	private int output = -1;

	boolean ensure(GpuDevice device, int width, int height, Map<Integer, GpuFormat> formats,
			Set<Integer> mipmapped, Set<Integer> doubled, int outputIndex) {
		if (this.width == width && this.height == height && !main.isEmpty() && this.output == outputIndex) {
			return false;
		}
		close();
		try {
			// The pack's post chain names its own buffers, and a pack that reconstructs at the end of
			// it may well ask for one this class never used to allocate. The three it always keeps
			// are the ones the built-in Solas contract reads; the declared output joins them.
			java.util.Set<Integer> indices = new java.util.LinkedHashSet<>(java.util.List.of(0, 1, 2));
			if (outputIndex >= 0) indices.add(outputIndex);
			for (int index : indices) {
				// The reconstruction writes into one of these and NGX only accepts an RGBA16F image
				// as its output, so that surface is allocated as one whatever the pack declared.
				// Every other surface keeps the pack's format, because the programs after the
				// trigger read them with the assumptions they were compiled under.
				GpuFormat format = index == 0 || index == outputIndex
						? GpuFormat.RGBA16_FLOAT : formats.get(index);
				if (format == null) throw new IllegalArgumentException("Solas target missing: " + index);
				boolean written = index == 0 || index == outputIndex;
				main.put(index, new TargetSurface(device, "Solas DLSS colortex" + index, format,
						mipmapped.contains(index), written, width, height));
				// No alt for the surface the reconstruction writes. This target set is allocated
				// fresh while the pack's own set is mid-frame, so the side the program after the
				// trigger will read is decided by a schedule that never saw this set - and a guess
				// that lands on the unwritten side draws nothing at all. One image, read as both
				// sides, is what makes the guess harmless.
				if (index != 0 && index != outputIndex && doubled.contains(index)) {
					alt.put(index, new TargetSurface(device, "Solas DLSS colortex" + index + " alt", format,
							mipmapped.contains(index), false, width, height));
				}
			}
			CommandEncoder encoder = device.createCommandEncoder();
			main.values().forEach(surface -> encoder.clearColorTexture(surface.texture(), new Vector4f()));
			alt.values().forEach(surface -> encoder.clearColorTexture(surface.texture(), new Vector4f()));
			this.width = width;
			this.height = height;
			this.output = outputIndex;
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
		output = -1;
	}
}
