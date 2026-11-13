package dev.vitrail.sodiumprobe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import java.nio.file.Path;

/** Applies actual production mixins without opening a world or touching a game installation. */
public final class KnotProbe {
	private KnotProbe() {}
	public static void main(String[] args) throws Exception {
		Knot knot = new Knot(EnvType.CLIENT);
		ClassLoader loader = knot.init(new String[] {"--gameDir", Path.of(".").toAbsolutePath().toString()});
		for (String name : new String[] {
			"net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer",
			"net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer",
			"net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer",
			"net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer",
			"net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats",
			"net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder$Vertex",
			"net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion",
			"net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",
			"net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
			"net.caffeinemc.mods.sodium.client.gpu.device.context.VKDrawContext"
		}) {
			Class.forName(name, false, loader);
			System.out.println("Sodium production mixins PASS: " + name);
		}
	}
}
