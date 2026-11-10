package dev.vitrail.render.upscale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DlssSettingsTest {
	@TempDir Path directory;
	@Test void defaultsPersistedModeAndFailureAreSeparate() throws Exception {
		DlssSettings.initialize(directory);
		try {
			assertEquals(DlssSettings.Mode.FSR1, DlssSettings.mode());
			assertFalse(DlssSettings.requested());
			DlssSettings.save(DlssSettings.Mode.BALANCED);
			assertEquals(DlssBridge.Quality.BALANCED, DlssSettings.quality());
			DlssSettings.fail("probe failure");
			assertFalse(DlssSettings.requested());
			assertEquals(DlssSettings.Mode.BALANCED, DlssSettings.mode());
			assertTrue(DlssSettings.takeReload()); assertFalse(DlssSettings.takeReload());
			DlssSettings.initialize(directory.resolve("another-instance"));
			DlssSettings.initialize(directory);
			assertEquals(DlssSettings.Mode.BALANCED, DlssSettings.mode());
			assertTrue(DlssSettings.requested());
		} finally { DlssSettings.save(DlssSettings.Mode.FSR1); DlssSettings.takeReload(); }
	}
	@Test void unknownPreferenceDoesNotEnableDlss() {
		assertEquals(DlssSettings.Mode.FSR1, DlssSettings.parse("bogus"));
		assertEquals(DlssSettings.Mode.FSR1, DlssSettings.parse(null));
		assertEquals(DlssSettings.Mode.DLAA, DlssSettings.parse(" dlaa "));
	}
	@Test void sourceAdapterRequiresPreferenceBackendAndOriginalPackAndRestoresOnFailure() throws Exception {
		String fixture = System.getProperty("solasDlssPack");
		org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "Supply official Solas fixture");
		var oldBackend = DlssDevices.installed();
		// Availability stub only: no GPU evaluation is simulated by this source-loading test.
		var backend = (DlssDevices.Device) java.lang.reflect.Proxy.newProxyInstance(
				DlssDevices.Device.class.getClassLoader(), new Class<?>[]{DlssDevices.Device.class},
				(proxy, method, args) -> {
					if (method.getName().equals("operational")) return true;
					throw new AssertionError("Source loading must not call GPU methods: " + method.getName());
				});
		DlssSettings.initialize(directory);
		try {
			DlssSettings.save(DlssSettings.Mode.QUALITY);
			if (oldBackend != null) DlssDevices.remove(oldBackend);
			try (var source = dev.vitrail.pack.source.ShaderPackSource.open(Path.of(fixture))) {
				assertFalse(source.solasTemporal());
			}
			DlssDevices.install(backend);
			try (var source = dev.vitrail.pack.source.ShaderPackSource.open(Path.of(fixture))) {
				assertTrue(source.solasTemporal());
				assertTrue(source.readLines(source.file("programs/gbuffers_entities.glsl").orElseThrow())
						.stream().anyMatch(line -> line.contains("wynnDlssClipJitter")));
			}
			Path shaders = java.nio.file.Files.createDirectories(directory.resolve("unsupported/shaders"));
			java.nio.file.Files.writeString(shaders.resolve("final.fsh"), "void main() {}\n");
			try (var source = dev.vitrail.pack.source.ShaderPackSource.open(shaders.getParent())) {
				assertFalse(source.solasTemporal());
			}
			DlssSettings.fail("evaluation failed");
			try (var source = dev.vitrail.pack.source.ShaderPackSource.open(Path.of(fixture))) {
				assertFalse(source.solasTemporal());
				var common = source.file("lib/common.glsl").orElseThrow();
				assertEquals(source.readOriginalLines(common), source.readLines(common));
			}
		} finally {
			DlssDevices.remove(backend);
			if (oldBackend != null) DlssDevices.install(oldBackend);
			DlssSettings.save(DlssSettings.Mode.FSR1);
			DlssSettings.takeReload();
		}
	}
}
