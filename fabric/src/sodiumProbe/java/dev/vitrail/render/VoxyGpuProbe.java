package dev.vitrail.render;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.voxy.VoxyBridge;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import org.joml.*;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.vulkan.VK12;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Exercises both SPI callbacks and samples their independent depths on the Minecraft Vulkan device. */
public final class VoxyGpuProbe {
    private static final GpuFormat FORMAT = GpuFormat.RGBA8_UNORM;
    private static final String VERT = """
            #version 460 core
            void main() {
                vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.5, 1.0);
            }
            """;
    private VoxyGpuProbe() {}

    public static void run() throws Exception {
        SharedConstants.tryDetectVersion();
        NativeLibrariesBootstrap.loadLibraries();
        RenderSystem.initRenderThread();
        var field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException("SDL video");
        var backend = new VulkanBackend();
        try {
            backend.loadLibrary();
            var device = backend.createDevice(new GpuDebugOptions(1,true,true,true));
            try {
                field.set(null,device);
                try {
                    exercise(device,16);
                    exercise(device,32);
                } finally {
                    GraphicsApi.purge();
                    field.set(null,null);
                }
            } finally { device.close(); }
        } finally {
            backend.unloadLibrary();
            SDLInit.SDL_Quit();
        }
        System.out.println("Voxy Vulkan MRT, blend, masked pixels and two sampled depths PASS");
    }

    private static RenderPipeline pipeline(GpuDevice device, String name, String fragment, String[] inputs,
            boolean blend, int outputs) {
        var id = Identifier.fromNamespaceAndPath("wynnvitrail","voxy_probe/" + name);
        var builder = RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if (inputs.length != 0) builder.withBindGroupLayout(GraphicsApi.samplers(inputs));
        for (int i=0; i<outputs; i++) builder.withColorTargetState(i,new ColorTargetState(
                blend && i==0 ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty(),FORMAT,ColorTargetState.WRITE_ALL));
        var result = builder.build();
        if (!GraphicsApi.valid(GraphicsApi.compile(device,result,GraphicsApi.source((key,type) ->
                type == ShaderType.VERTEX ? VERT : fragment)))) throw new IllegalStateException(name);
        return result;
    }

    private static void exercise(GpuDevice device,int size) throws Exception {
        String fragment = """
                #version 460 core
                uniform sampler2D Near;
                layout(location=0) out vec4 scene;
                layout(location=1) out vec4 depth;
                void main() {
                    if (texelFetch(Near,ivec2(gl_FragCoord.xy),0).r > gl_FragCoord.z) discard;
                    scene=vec4(COLOR,0,0,0.5);
                    depth=vec4(DEPTH);
                }
                """;
        var nearPipeline = pipeline(device,"near","""
                #version 460 core
                layout(location=0) out vec4 z;
                void main() { z=vec4(gl_FragCoord.x < 4 ? 0.9 : 0.0); }
                """,new String[0],false,1);
        var opaque = pipeline(device,"opaque",fragment.replace("COLOR","0.25").replace("DEPTH","0.75"),new String[]{"Near"},false,2);
        var trans = pipeline(device,"trans",fragment.replace("COLOR","0.75").replace("DEPTH","0.5"),new String[]{"Near"},true,2);
        var sample = pipeline(device,"sample","""
                #version 460 core
                uniform sampler2D Opaque;
                uniform sampler2D Trans;
                layout(location=0) out vec4 result;
                void main() {
                    ivec2 p=ivec2(gl_FragCoord.xy);
                    result=vec4(texelFetch(Opaque,p,0).r,texelFetch(Trans,p,0).r,0,1);
                }
                """,new String[]{"Opaque","Trans"},false,1);
        var encoder = device.createCommandEncoder();
        try (var color = new TargetSurface("probe scene",FORMAT,false,size,size);
             var near = new TargetSurface("probe near depth",FORMAT,false,size,size);
             var solid = new TargetSurface("probe opaque",FORMAT,false,size,size);
             var liquid = new TargetSurface("probe translucent",FORMAT,false,size,size);
             var output = new TargetSurface("probe sampled",FORMAT,false,size,size);
             var sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.empty());
             var read = device.createBuffer(() -> "probe readback",GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,12)) {
            encoder.clearColorTexture(color.texture(),new Vector4f());
            encoder.clearColorTexture(solid.texture(),new Vector4f(1));
            encoder.clearColorTexture(liquid.texture(),new Vector4f(1));
            try (var pass = encoder.createRenderPass(PassDescriptor.create(() -> "Probe foreground")
                    .withColorAttachment(near.view()).build())) {
                GraphicsApi.setPipeline(pass,nearPipeline);
                pass.draw(3,1,0,0);
            }
            var registration = VoxyBridge.register(VoxyBridge.VERSION,new VoxyBridge.Provider() {
                public boolean enabled() { return true; }
                public VoxyBridge.Camera begin(VoxyBridge.Frame frame) {
                    return new VoxyBridge.Camera(new Matrix4f(),new Matrix4f(),1,256,16);
                }
                public GpuTextureView draw(VoxyBridge.Draw draw) {
                    var descriptor = PassDescriptor.create(() -> "Voxy fixed geometry");
                    draw.colors().forEach(descriptor::withColorAttachment);
                    try (var pass = encoder.createRenderPass(descriptor.build())) {
                        GraphicsApi.setPipeline(pass,draw.stage()==VoxyBridge.Stage.OPAQUE ? opaque : trans);
                        GraphicsApi.bindTexture(pass,"Near",draw.nearDepth(),sampler);
                        pass.draw(3,1,0,0);
                    }
                    return draw.colors().get(1);
                }
            });
            try {
                VoxyBridge.begin(null);
                for (var stage : VoxyBridge.Stage.values()) {
                    var depth = stage == VoxyBridge.Stage.OPAQUE ? solid : liquid;
                    VoxyBridge.draw(new VoxyBridge.Draw(null,stage,device,List.of(color.view(),depth.view()),near.view(),null,null));
                }
                try (var pass = encoder.createRenderPass(PassDescriptor.create(() -> "Probe composite")
                        .withColorAttachment(output.view()).build())) {
                    GraphicsApi.setPipeline(pass,sample);
                    GraphicsApi.bindTexture(pass,"Opaque",PackPass.distant("vxDepthTexOpaque",null,null),sampler);
                    GraphicsApi.bindTexture(pass,"Trans",PackPass.distant("vxDepthTexTrans",null,null),sampler);
                    pass.draw(3,1,0,0);
                }
                encoder.copyTextureToBuffer(output.texture(),read,0,() -> {},0,8,8,1,1);
                encoder.copyTextureToBuffer(color.texture(),read,4,() -> {},0,8,8,1,1);
                encoder.copyTextureToBuffer(output.texture(),read,8,() -> {},0,1,8,1,1);
                var vk = (VulkanDevice)((GpuDeviceAccessor)device).vitrail$backend();
                vk.createCommandEncoder().submit();
                if (VK12.vkDeviceWaitIdle(vk.vkDevice()) != VK12.VK_SUCCESS) throw new IllegalStateException("GPU wait");
                try (var mapped = read.map(true,false)) {
                    var pixels = mapped.data();
                    int[][] expected = {{0,191},{1,128},{4,128},{8,255},{9,255}};
                    for (var check : expected) {
                        int value = Byte.toUnsignedInt(pixels.get(check[0]));
                        if (java.lang.Math.abs(value-check[1])>2)
                            throw new IllegalStateException("byte " + check[0] + " expected "+check[1]+" got "+value);
                    }
                }
            } finally { registration.close(); }
        }
    }
}
