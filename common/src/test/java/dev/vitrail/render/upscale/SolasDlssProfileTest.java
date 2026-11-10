package dev.vitrail.render.upscale;

import dev.vitrail.pack.source.ShaderPackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SolasDlssProfileTest {
	@TempDir
	Path temporary;

	@Test
	void aPackNameCannotImpersonateSolas() throws Exception {
		Path pack = temporary.resolve("Solas Shader V3.9");
		Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(pack.resolve("shaders/final.fsh"), "void main() {}\n");
		try (ShaderPackSource source = ShaderPackSource.open(pack)) {
			assertFalse(SolasDlssProfile.inspect(source).recognized());
		}
	}

	@Test
	void officialArchiveRecognizesButChangesAndAdditionalProgramsDoNot() throws Exception {
		String fixture = System.getProperty("solasDlssPack");
		assumeTrue(fixture != null, "Supply -PsolasDlssPack=<official Solas 3.9 zip> for the integration fixture");
		Path shaders = Files.createDirectories(temporary.resolve("shaders"));
		try (ShaderPackSource source = ShaderPackSource.open(Path.of(fixture))) {
			var result = SolasDlssProfile.inspect(source);
			assertTrue(result.recognized(), () -> result.differences().toString());
			for (Path file : source.sourceFiles()) {
				Path target = shaders.resolve(source.rel(file));
				Files.createDirectories(target.getParent());
				// CRLF conversions and a renamed directory are not a pack version change.
				Files.writeString(target, String.join("\r\n", source.readLines(file)));
			}
			Files.write(shaders.resolve("shaders.properties"), source.bytes(source.file("shaders.properties").orElseThrow()));
		}
		try (ShaderPackSource source = ShaderPackSource.open(temporary)) {
			assertTrue(SolasDlssProfile.inspect(source).recognized());
		}
		Path jitter = shaders.resolve("lib/antialiasing/jitter.glsl");
		Files.writeString(jitter, Files.readString(jitter) + "\n// edited\n");
		try (ShaderPackSource source = ShaderPackSource.open(temporary)) {
			assertEquals(java.util.List.of("Changed source: lib/antialiasing/jitter.glsl"),
					SolasDlssProfile.inspect(source).differences());
		}
		Files.writeString(shaders.resolve("composite99.fsh"), "void main() {}\n");
		try (ShaderPackSource source = ShaderPackSource.open(temporary)) {
			assertTrue(SolasDlssProfile.inspect(source).differences().contains("Unexpected source: composite99.fsh"));
		}
	}
}
