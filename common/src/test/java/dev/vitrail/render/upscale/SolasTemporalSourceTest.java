package dev.vitrail.render.upscale;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.ShaderPackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SolasTemporalSourceTest {
	@TempDir Path temporary;
	@Test void patchedGeometryAndPostprocessingCompileInEveryDimension() throws Exception {
		String fixture = System.getProperty("solasDlssPack");
		assumeTrue(fixture != null, "Supply official Solas 3.9 fixture");
		Path shaders = Files.createDirectories(temporary.resolve("shaders"));
		try (var source = ShaderPackSource.open(Path.of(fixture))) {
			for (Path file : source.sourceFiles()) {
				String relative = source.rel(file);
				Path target = shaders.resolve(relative);
				Files.createDirectories(target.getParent());
				Files.writeString(target, String.join("\n", SolasSpatialProbe.patch(relative, source.readOriginalLines(file), true, true)));
			}
			Files.write(shaders.resolve("shaders.properties"), source.bytes(source.file("shaders.properties").orElseThrow()));
		}
		for (String dimension : java.util.List.of("", "world-1/", "world1/")) {
			for (String name : java.util.List.of("gbuffers_terrain", "gbuffers_entities", "gbuffers_hand",
					"gbuffers_water", "gbuffers_block", "gbuffers_textured", "gbuffers_basic", "gbuffers_armor_glint",
					"gbuffers_beaconbeam", "gbuffers_clouds", "gbuffers_damagedblock", "gbuffers_spidereyes", "gbuffers_weather",
					"dh_terrain", "dh_water", "composite13", "composite14", "composite15", "final")) {
				// Clouds and distant-water programs are intentionally absent in some dimensions.
				if (!Files.isRegularFile(shaders.resolve(dimension + name + ".vsh"))
						|| !Files.isRegularFile(shaders.resolve(dimension + name + ".fsh"))) continue;
				var inputs = name.startsWith("composite") || name.equals("final") ? VertexInputs.FULLSCREEN
						: name.equals("gbuffers_terrain") || name.equals("gbuffers_water") ? VertexInputs.TERRAIN
						: name.startsWith("dh_") ? VertexInputs.DISTANT : VertexInputs.ENTITY;
				System.out.println("Translating " + dimension + name);
				Files.writeString(Path.of("solas-temporal-stage.txt"), "Translating " + dimension + name);
				var loaded = PackProgram.load(temporary, dimension + name, inputs, Map.of(), "").orElseThrow();
				var stages = loaded.program().stages();
				String vertex = stages.get(ProgramStage.VERTEX).text();
				if (name.startsWith("gbuffers_") || name.startsWith("dh_")) assertTrue(vertex.contains("wynnDlssClipJitter"), dimension + name);
				for (var stage : stages.entrySet()) {
					Files.writeString(Path.of("solas-temporal-stage.txt"), "Compiling " + dimension + name + " " + stage.getKey());
					Files.writeString(Path.of("solas-temporal-debug.glsl"), stage.getValue().text());
					dev.wynnvitrail.ShaderCompileCheck.compile(stage.getKey(), stage.getValue().text());
				}
			}
		}
	}
}
