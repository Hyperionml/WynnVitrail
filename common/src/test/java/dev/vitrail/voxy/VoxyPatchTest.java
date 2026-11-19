package dev.vitrail.voxy;

import dev.vitrail.pack.source.OpenedPack;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VoxyPatchTest {
    @Test void modrinthPacks() throws Exception {
        String directory = System.getProperty("voxyCorpus");
        assumeTrue(directory != null);
        try (var files = java.nio.file.Files.list(Path.of(directory))) {
            var packs = files.filter(p -> p.toString().endsWith(".zip")).sorted().toList();
            assertFalse(packs.isEmpty());
            for (Path path : packs) {
                VoxyBridge.macro(true);
                try (var opened = OpenedPack.open(path.toRealPath(), Map.of(), "")) {
                    var dimensions = dev.vitrail.pack.source.DimensionSet.discover(opened.source());
                    for (String world : List.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end")) {
                        var patch = VoxyPatch.read(opened,dimensions.place(world));
                        assertNotNull(patch, path + " " + world);
                        assertTrue(patch.opaqueSource().contains("voxy_emitFragment"), path.toString());
                        assertFalse(patch.uniforms().isEmpty());
                        assertTrue(patch.samplers().containsKey("noisetex"));
                    }
                } finally { VoxyBridge.macro(false); }
            }
        }
    }

    @Test void solasDimensions() throws Exception {
        String path = System.getProperty("solasVoxyPack");
        assumeTrue(path != null);
        VoxyBridge.macro(true);
        try (var opened = OpenedPack.open(Path.of(path).toRealPath(),Map.of(),"")) {
            for (String dim : List.of("", "world-1", "world1")) {
                var patch = VoxyPatch.read(opened,dim);
                assertNotNull(patch, "missing patch in " + dim);
                assertEquals(List.of(0,3),patch.opaque());
                assertEquals(List.of(0,1,7),patch.translucent());
                assertTrue(patch.blending().get(-1).off());
                assertEquals("SRC_ALPHA",patch.blending().get(0).srcRgb());
                assertTrue(patch.separateDepth());
                assertTrue(patch.samplers().containsKey("vxDepthTexOpaque"));
                assertTrue(patch.opaqueSource().contains("void voxy_emitFragment"));
                assertTrue(patch.translucentSource().contains("void voxy_emitFragment"));
                assertTrue(patch.taaOffset().contains("return "));
            }
        } finally { VoxyBridge.macro(false); }
    }

    @Test void inlineIncludesAndDiagnostics(@org.junit.jupiter.api.io.TempDir Path pack) throws Exception {
        var shaders = java.nio.file.Files.createDirectory(pack.resolve("shaders"));
        var config = shaders.resolve("voxy.json");
        var body = """
                {"version":1,"uniforms":[],"opaqueDrawBuffers":[0],"translucentDrawBuffers":[0,7],
                 "samplers":["depthtex0"],"blending":{-1:"off",1:["GL_ONE","GL_ZERO","ONE","ZERO"]},
                 "opaquePatchData":"
                 #include "/body.glsl"
                 "}
                """;
        java.nio.file.Files.writeString(config,body);
        java.nio.file.Files.writeString(shaders.resolve("body.glsl"),"void voxy_emitFragment() {}");
        try (var opened = OpenedPack.open(pack,Map.of(),"")) {
            var patch = VoxyPatch.read(opened,"");
            assertTrue(patch.opaqueSource().contains("void voxy_emitFragment()"));
            assertEquals("sampler2D",patch.samplers().get("depthtex0"));
            assertTrue(patch.translucentBlend(0).orElseThrow().off());
            assertEquals("ONE",patch.translucentBlend(1).orElseThrow().srcRgb());
            assertNull(VoxyPatch.read(opened,"world1"));
        }
        java.nio.file.Files.writeString(config,body.replace("\"uniforms\":[]","\"ssbos\":{0:\"binding\"},\"uniforms\":[]"));
        try (var opened = OpenedPack.open(pack,Map.of(),"")) {
            var failure = assertThrows(java.io.IOException.class,() -> VoxyPatch.read(opened,""));
            assertTrue(failure.getMessage().contains("unsupported field ssbos"));
        }
    }

    @Test void forwardedConfigUsesSettingsFromSharedInclude(@org.junit.jupiter.api.io.TempDir Path pack) throws Exception {
        Path shaders = java.nio.file.Files.createDirectory(pack.resolve("shaders"));
        Path world = java.nio.file.Files.createDirectory(shaders.resolve("world0"));
        java.nio.file.Files.writeString(world.resolve("voxy.json"), "#include \"/common.json\"");
        java.nio.file.Files.writeString(shaders.resolve("settings.glsl"), "#define LOCAL_SCALE 0.75\n");
        java.nio.file.Files.writeString(shaders.resolve("common.json"), """
                {
                    "unusedString": "
                        // An author's "quoted" note with a \\backslash.
                        #include "settings.glsl"
                    ",
                    "version":1, "uniforms":[], "opaqueDrawBuffers":[0], "translucentDrawBuffers":[1],
                    "opaquePatchData":"void voxy_emitFragment() {}",
                    "samplers":{"shadowtex0":"sampler2DShadow", "tex":"sampler2D"},
                    #ifdef LOCAL_SCALE
                    "renderScale":[LOCAL_SCALE], "useViewportDims":true,
                    #endif
                    "taaOffset":"{ return vec2(0); }"
                }
                """);
        try (var opened = OpenedPack.open(pack,Map.of(),"")) {
            var patch = VoxyPatch.read(opened,"world0");
            assertEquals(0.75f,patch.viewOptions().scaleX());
            assertEquals(0.75f,patch.viewOptions().scaleY());
            assertTrue(patch.viewOptions().useViewportDims());
            assertEquals("sampler2DShadow",patch.samplers().get("shadowtex0"));
        }
    }

    @Test void solasTaaFollowsPackOption() throws Exception {
        String path = System.getProperty("solasVoxyPack");
        assumeTrue(path != null);
        for (boolean on : List.of(false,true)) {
            var value = on ? dev.vitrail.pack.option.OptionValue.on() : dev.vitrail.pack.option.OptionValue.off();
            try (var opened = OpenedPack.open(Path.of(path).toRealPath(),Map.of("TAA", value),"")) {
                var patch = VoxyPatch.read(opened,"");
                assertEquals(on,patch.taaOffset().contains("return jitterOffsets8"));
                assertEquals(!on,patch.taaOffset().contains("return vec2(0.0)"));
            }
        }
    }

    @Test void frameHistoryAndDetach() throws Exception {
        var pose = new Matrix4f();
        var provider = (VoxyBridge.Provider) java.lang.reflect.Proxy.newProxyInstance(
                VoxyBridge.Provider.class.getClassLoader(), new Class<?>[]{VoxyBridge.Provider.class},
                (self, method, args) -> switch (method.getName()) {
                    case "enabled" -> true;
                    case "begin" -> new VoxyBridge.Camera(pose,new Matrix4f(),1,128,8);
                    default -> null;
                });
        var registration = VoxyBridge.register(1,provider);
        try {
            assertTrue(VoxyBridge.pollReload());
            VoxyBridge.begin(null);
            pose.translation(12,0,0);
            VoxyBridge.begin(null);
            assertEquals(0f,VoxyBridge.matrix("vxModelViewPrev").m30());
            assertEquals(12f,VoxyBridge.matrix("vxModelView").m30());
            assertEquals(0f,VoxyBridge.matrix("vxModelViewPrev").m30());
            VoxyBridge.invalidate();
            VoxyBridge.begin(null);
            assertEquals(12f,VoxyBridge.matrix("vxModelViewPrev").m30());
        } finally { registration.close(); }
        assertFalse(VoxyBridge.requested());
        assertTrue(VoxyBridge.pollReload());
    }
}
