package dev.vitrail.render.upscale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DlssInstallationTest {
	@TempDir Path directory;

	@Test
	void disabledDoesNotReadAnyInstallationPaths() throws IOException {
		assertTrue(DlssInstallation.read(key -> {
			assertEquals("wynnvitrail.dlss.backend", key);
			return null;
		}).isEmpty());
	}

	@Test
	void explicitOptInRequiresAbsoluteExistingFiles() throws IOException {
		Path bridge = Files.createFile(directory.resolve("wynn_dlss.dll"));
		var properties = Map.of("wynnvitrail.dlss.backend", "true",
				"wynnvitrail.dlss.bridge", bridge.toString(),
				"wynnvitrail.dlss.runtime", directory.toString());
		assertThrows(IOException.class, () -> DlssInstallation.read(properties::get));
		Files.createFile(directory.resolve("nvngx_dlss.dll"));
		var installation = DlssInstallation.read(properties::get).orElseThrow();
		assertEquals(bridge.toRealPath(), installation.bridge());
		assertEquals(directory.toRealPath(), installation.runtime());
		assertThrows(IllegalArgumentException.class, () -> DlssInstallation.read(key -> switch (key) {
			case "wynnvitrail.dlss.backend" -> "true";
			case "wynnvitrail.dlss.bridge" -> "wynn_dlss.dll";
			default -> directory.toString();
		}));
	}
}
