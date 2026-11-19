package dev.vitrail.voxy;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.WorldState;
import java.util.*;
import java.util.function.Function;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Optional SPI. Registration and callbacks run on the client render thread. */
public final class VoxyBridge {
    public static final int VERSION = 1;
    public enum Stage { OPAQUE, TRANSLUCENT }
    public record Frame(int load, long serial, int width, int height, int displayWidth, int displayHeight,
            WorldState camera, UniformCatalog uniforms, VoxyPatch patch) {
        public int material(net.minecraft.world.level.block.state.BlockState state) {
            return dev.vitrail.render.BlockStateIds.id(state);
        }
        public int materialGeneration() { return dev.vitrail.render.BlockStateIds.generation(); }
        /** Build once per patch from the member declarations actually emitted by the provider. */
        public dev.vitrail.uniform.UniformBlock uniformBlock(List<dev.vitrail.glsl.TranslatedUnit.Uniform> members) {
            return new dev.vitrail.uniform.UniformBlock(members, uniforms);
        }
    }
    /** Colors are in fragment-output order. Borrowed views are valid only during draw. */
    public record Draw(Frame frame, Stage stage, GpuDevice device, List<GpuTextureView> colors,
            GpuTextureView nearDepth, GpuTextureView coverage, Function<String,GpuTextureView> sampler) {
        public String[] samplerNames() { return frame.patch().samplers().keySet().toArray(String[]::new); }
    }
    public record Camera(Matrix4fc modelView, Matrix4fc projection, float near, float far, int chunks) {}
    public interface Provider {
        boolean enabled();
        Camera begin(Frame frame);
        /** Record on the game encoder, close passes, return provider-owned forward sampleable depth. */
        GpuTextureView draw(Draw draw);
        default void invalidate() {}
    }
    private static Provider provider;
    private static boolean reload;
    private static volatile boolean macro;
    private static boolean enabled;
    private static final Map<String,Matrix4fc> matrices = new HashMap<>();
    private static final Matrix4fc IDENTITY = new Matrix4f();
    private static Camera camera;
    private static GpuTextureView opaque, translucent;
    private VoxyBridge() {}
    public static AutoCloseable register(int version, Provider p) {
        if (version != VERSION || provider != null) throw new IllegalStateException("Voxy SPI version or owner mismatch");
        provider = Objects.requireNonNull(p);
        reload = true;
        return new AutoCloseable() {
            private boolean closed;
            public void close() {
                if (closed) return;
                closed = true;
                try {
                    invalidate();
                } finally {
                    provider = null;
                    reload = true;
                }
            }
        };
    }
    public static boolean requested() { return provider != null && provider.enabled(); }
    public static boolean pollReload() {
        boolean now = requested();
        boolean changed = reload || now != enabled;
        reload = false;
        enabled = now;
        return changed;
    }
    public static boolean macro() { return macro; }
    public static void macro(boolean value) { macro = value; }
    public static void invalidate() {
        opaque = translucent = null;
        camera = null;
        matrices.clear();
        if (provider != null) provider.invalidate();
    }
    public static boolean begin(Frame frame) {
        opaque = translucent = null;
        if (!requested()) return false;
        camera = provider.begin(frame);
        if (camera == null) { matrices.clear(); return false; }
        publish("vxModelView", new Matrix4f(camera.modelView()));
        publish("vxProj", new Matrix4f(camera.projection()));
        publish("vxViewProj", new Matrix4f(camera.projection()).mul(camera.modelView()));
        return true;
    }
    private static void publish(String name, Matrix4f current) {
        matrices.put(name + "Prev", matrices.getOrDefault(name,current));
        matrices.put(name,current);
        matrices.put(name + "Inv",new Matrix4f(current).invert());
    }
    public static Matrix4fc matrix(String name) { return matrices.getOrDefault(name,IDENTITY); }
    public static int distance() { return camera == null ? 0 : camera.chunks(); }
    public static float near() { return camera == null ? 0 : camera.near(); }
    public static float far() { return camera == null ? 0 : camera.far(); }
    public static void draw(Draw draw) {
        if (!requested() || camera == null) return;
        var view = provider.draw(draw);
        if (draw.stage() == Stage.OPAQUE) opaque = view;
        else translucent = view;
    }
    public static GpuTextureView depth(String name) {
        return name.equals("vxDepthTexOpaque") || translucent == null ? opaque : translucent;
    }
}
