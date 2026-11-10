package dev.vitrail.render.upscale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/** Explicit local developer installation. No DLL downloads or changes to render-scale settings. */
public record DlssInstallation(Path bridge, Path runtime) {

	public static Optional<DlssInstallation> read(Function<String, String> property) throws IOException {
		if (!Boolean.parseBoolean(property.apply("wynnvitrail.dlss.backend"))) {
			return Optional.empty();
		}
		Path bridge = absolute(property.apply("wynnvitrail.dlss.bridge"), "bridge").toRealPath();
		Path runtime = absolute(property.apply("wynnvitrail.dlss.runtime"), "runtime").toRealPath();
		if (!Files.isRegularFile(bridge) || !Files.isDirectory(runtime)
				|| !Files.isRegularFile(runtime.resolve("nvngx_dlss.dll"))) {
			throw new IOException("DLSS requires a bridge DLL and a runtime directory containing nvngx_dlss.dll");
		}
		return Optional.of(new DlssInstallation(bridge, runtime));
	}

	private static Path absolute(String value, String name) {
		if (value == null || value.isBlank() || !Path.of(value).isAbsolute()) {
			throw new IllegalArgumentException("wynnvitrail.dlss." + name + " must be an absolute path");
		}
		return Path.of(value);
	}
}
