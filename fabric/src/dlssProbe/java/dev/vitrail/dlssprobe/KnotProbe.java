package dev.vitrail.dlssprobe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

import java.nio.file.Path;

/** Loads the actual mod and mixins, but does not start Minecraft's client, a window or a server. */
public final class KnotProbe {
	private KnotProbe() {
	}

	public static void main(String[] args) throws ReflectiveOperationException {
		Knot knot = new Knot(EnvType.CLIENT);
		ClassLoader loader = knot.init(new String[] {"--gameDir", Path.of(".").toAbsolutePath().toString()});
		knot.addToClassPath(Path.of(System.getProperty("dlss.probe.classes")));
		loader.loadClass("dev.vitrail.dlssprobe.GameProbe").getMethod("run").invoke(null);
	}
}
