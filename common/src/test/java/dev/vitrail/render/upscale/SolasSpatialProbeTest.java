package dev.vitrail.render.upscale;

import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.target.TargetPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SolasSpatialProbeTest {
	private static final String PROPERTY = "wynnvitrail.dlss.solasSpatialProbe";
	@TempDir Path temporary;

	@Test
	void refusesIncompleteOrReorderedPostprocessing() {
		assertDoesNotThrow(() -> SolasSpatialProbe.validatePasses(List.of("composite", "composite13", "composite14", "composite15", "final")));
		assertThrows(IllegalArgumentException.class, () -> SolasSpatialProbe.validatePasses(List.of("composite14", "composite15", "final")));
		assertThrows(IllegalArgumentException.class, () -> SolasSpatialProbe.validatePasses(List.of("composite13", "composite15", "composite14", "final")));
		assertThrows(IllegalArgumentException.class, () -> SolasSpatialProbe.validatePasses(List.of("composite13", "composite14", "final")));
	}

	@Test
	void unrecognizedPackIsNotPatchedEvenWhenExplicitlyRequested() throws Exception {
		Path shaders = Files.createDirectories(temporary.resolve("shaders/lib"));
		Files.writeString(shaders.resolve("common.glsl"), "#define TAA\n");
		String previous = System.getProperty(PROPERTY);
		System.setProperty(PROPERTY, "true");
		try (ShaderPackSource source = ShaderPackSource.open(temporary)) {
			assertFalse(source.solasSpatialProbe());
			Path common = source.file("lib/common.glsl").orElseThrow();
			assertEquals(source.readOriginalLines(common), source.readLines(common));
		} finally {
			restore(previous);
		}
	}

	@Test
	void officialPackKeepsLensFlareHistoryAndAllLateStagesWithoutOldTaaOrJitter() throws Exception {
		String fixture = System.getProperty("solasDlssPack");
		assumeTrue(fixture != null, "Supply the official Solas archive fixture");
		String previous = System.getProperty(PROPERTY);
		System.setProperty(PROPERTY, "true");
		Map<String, OptionValue> options = Map.of("TAA", OptionValue.on(), "LENS_FLARE", OptionValue.on());
		try (OpenedPack pack = OpenedPack.open(Path.of(fixture), options, "")) {
			var source = pack.source();
			assertTrue(source.solasSpatialProbe());
			assertTrue(SolasDlssProfile.inspect(source).recognized(), "Identity must use original source bytes");
			var expander = new IncludeExpander(source, pack.settings());
			for (String dimension : List.of("", "world-1", "world1")) {
				String prefix = dimension.isEmpty() ? "" : dimension + "/";
				var finalAa = expander.expand(source.file(prefix + "composite15.fsh").orElseThrow());
				assertFalse(finalAa.defines().containsKey("TAA"));
				assertFalse(live(finalAa).contains("TemporalAA("));
				assertTrue(live(finalAa).contains("vec4(texture2D(colortex2, texCoord).r"));
				assertTrue(live(finalAa).contains("gl_FragData[1] = vec4(previousColor)"));
				var terrain = expander.expand(source.file(prefix + "gbuffers_terrain.vsh").orElseThrow());
				assertFalse(live(terrain).contains("TAAJitter("));
				var plan = TargetPlan.build(source, pack.options(), pack.settings(), pack.properties(), dimension);
				SolasSpatialProbe.validatePasses(plan.running());
				assertTrue(plan.persistent().contains(2), "colortex2 must keep its lens-flare history");
			}
			var lens = expander.expand(source.file("composite14.fsh").orElseThrow());
			assertTrue(live(lens).contains("tempVisibleSun"));
			assertTrue(live(lens).contains("temporalData = mix(tempVisibleSun, visibleSun"));
		} finally {
			restore(previous);
		}
		// Reopening with the diagnostic off must restore the original source and temporal AA.
		System.setProperty(PROPERTY, "false");
		try (ShaderPackSource source = ShaderPackSource.open(Path.of(fixture))) {
			assertFalse(source.solasSpatialProbe());
			var unit = new IncludeExpander(source, SettingSet.defaults()).expand(source.file("composite15.fsh").orElseThrow());
			assertTrue(live(unit).contains("TemporalAA("));
		} finally {
			restore(previous);
		}
	}

	private static String live(IncludeExpander.ExpandedUnit unit) {
		return IntStream.range(0, unit.lines().size()).filter(unit::isLive)
				.mapToObj(i -> unit.lines().get(i)).collect(Collectors.joining("\n"));
	}

	private static void restore(String previous) {
		if (previous == null) System.clearProperty(PROPERTY);
		else System.setProperty(PROPERTY, previous);
	}
}
