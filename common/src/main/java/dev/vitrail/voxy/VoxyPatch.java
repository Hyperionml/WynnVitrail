package dev.vitrail.voxy;

import com.google.gson.*;
import dev.vitrail.pack.source.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public record VoxyPatch(String pack, String dimension, List<Integer> opaque, List<Integer> translucent,
        Map<Integer,dev.vitrail.pack.model.BlendMode> blending, boolean separateDepth, List<String> uniforms, Map<String,String> samplers,
        Map<String,String> shaderDefines,
        String opaqueSource, String translucentSource, String taaOffset, ViewOptions viewOptions) {
    public record ViewOptions(float scaleX, float scaleY, boolean useViewportDims, boolean useDynamicFarPlane,
            boolean skipShaderDepthHackFix) {
        private static final ViewOptions DEFAULT = new ViewOptions(1, 1, false, false, false);

        static ViewOptions read(JsonObject json, Map<String,String> defines) {
            var scale = json.getAsJsonArray("renderScale");
            float x = scale == null || scale.isEmpty() ? 1 : scale(scale.get(0), defines);
            float y = scale == null || scale.size() < 2 ? x : scale(scale.get(1), defines);
            return new ViewOptions(x, y, flag(json,"useViewportDims"), flag(json,"useDynamicFarPlane"),
                    flag(json,"skipShaderDepthHackFix"));
        }
        private static boolean flag(JsonObject json, String key) {
            return json.has(key) && json.get(key).getAsBoolean();
        }
        private static float scale(JsonElement element, Map<String,String> defines) {
            float value = Float.parseFloat(Macros.expand(element.getAsString(), defines));
            if (!Float.isFinite(value)) throw new IllegalArgumentException("invalid renderScale");
            return Math.max(0.01f, value);
        }
    }

    /** Binary-compatible constructor for SPI v1 clients. */
    public VoxyPatch(String pack, String dimension, List<Integer> opaque, List<Integer> translucent,
            Map<Integer,dev.vitrail.pack.model.BlendMode> blending, boolean separateDepth, List<String> uniforms,
            Map<String,String> samplers, Map<String,String> shaderDefines,
            String opaqueSource, String translucentSource, String taaOffset) {
        this(pack, dimension, opaque, translucent, blending, separateDepth, uniforms, samplers, shaderDefines,
                opaqueSource, translucentSource, taaOffset, ViewOptions.DEFAULT);
    }
    public static VoxyPatch read(OpenedPack opened, String place) throws IOException {
        String prefix = place.isEmpty() ? "" : place + "/";
        var source = opened.source();
        var config = source.file(prefix + "voxy.json");
        if (config.isEmpty()) return null;
        try {
            var defines = new HashMap<>(opened.settings().globalDefines(opened.options()));
            defines.put("VOXY", "3");
            var expander = new IncludeExpander(source, opened.settings());
            var unit = expander.expandConfiguration(config.get(), defines);
            var json = JsonParser.parseString(configurationText(unit)).getAsJsonObject();
            if (json.has("ssbos") && !json.getAsJsonObject("ssbos").isEmpty())
                throw new IllegalArgumentException("unsupported field ssbos (storage buffer bindings)");
            if (json.get("version").getAsInt() != 1) throw new IllegalArgumentException("unsupported version");
            var opaque = targets(json, "opaqueDrawBuffers");
            var translucent = targets(json, "translucentDrawBuffers");
            Map<Integer,dev.vitrail.pack.model.BlendMode> blend = new LinkedHashMap<>();
            var blending = json.get("blending");
            if (blending != null && blending.isJsonPrimitive() && blending.getAsString().equalsIgnoreCase("off"))
                blend.put(-1, dev.vitrail.pack.model.BlendMode.OFF);
            else if (blending != null) blending.getAsJsonObject().entrySet().forEach(e -> {
                int slot = Integer.parseInt(e.getKey());
                if (slot < -1 || slot >= translucent.size()) throw new IllegalArgumentException("blending slot " + slot);
                var value = e.getValue();
                String factors = value.isJsonArray() ? String.join(" ", value.getAsJsonArray().asList().stream()
                        .map(JsonElement::getAsString).toList()) : value.getAsString();
                blend.put(slot, dev.vitrail.pack.model.BlendMode.parse(factors.toUpperCase(Locale.ROOT).replace("GL_", ""))
                        .orElseThrow(() -> new IllegalArgumentException("invalid blending at slot " + slot)));
            });
            Map<String,String> samplers = new LinkedHashMap<>();
            var samplerList = json.get("samplers");
            if (samplerList != null && samplerList.isJsonArray()) {
                for (var item : samplerList.getAsJsonArray()) {
                    String name = item.getAsString();
                    samplers.put(name, defaultSampler(name));
                }
            } else if (samplerList != null) {
                samplerList.getAsJsonObject().entrySet().forEach(e -> samplers.put(e.getKey(),
                        e.getValue().isJsonNull() ? defaultSampler(e.getKey()) : e.getValue().getAsString()));
            }
            var textures = opened.properties().customTextures(defines);
            for (var binding : samplers.entrySet()) {
                String name = binding.getKey();
                var kind = dev.vitrail.pack.target.SamplerPlan.classify(name);
                boolean supported = switch (kind) {
                    case COLORTEX, DEPTH, SHADOW_DEPTH, SHADOW_COLOUR, DISTANT_DEPTH, NOISE -> true;
                    default -> name.equals("tex") || name.equals("shadowtex") || textures.containsKey("customTexture." + name)
                            || textures.containsKey("texture.gbuffers." + name);
                };
                if (!supported) throw new IllegalArgumentException("unsupported sampler " + name);
                boolean comparison = binding.getValue().equals("sampler2DShadow")
                        && (kind == dev.vitrail.pack.target.SamplerPlan.Kind.SHADOW_DEPTH || name.equals("shadowtex"));
                if (!binding.getValue().equals("sampler2D") && !comparison)
                    throw new IllegalArgumentException("unsupported sampler type for " + name + ": " + binding.getValue());
            }
            String opaqueSource = patch(source,expander,prefix,"voxy_opaque.glsl",json,"opaquePatchData");
            if (opaqueSource.isBlank()) throw new IllegalArgumentException("missing opaquePatchData");
            if (!json.has("uniforms")) throw new IllegalArgumentException("missing uniforms");
            return new VoxyPatch(source.packName(), place, opaque, translucent, Map.copyOf(blend),
                    json.has("excludeLodsFromVanillaDepth") && json.get("excludeLodsFromVanillaDepth").getAsBoolean(),
                    json.getAsJsonArray("uniforms").asList().stream().map(JsonElement::getAsString).toList(),
                    Collections.unmodifiableMap(samplers), Map.copyOf(opened.settings().unitDefines()),
                    opaqueSource,
                    patch(source,expander,prefix,"voxy_translucent.glsl",json,"translucentPatchData"),
                    patch(source,expander,prefix,"voxy_taa.glsl",json,"taaOffset"), ViewOptions.read(json, unit.defines()));
        } catch (RuntimeException e) {
            throw new IOException(source.packName() + " [" + place + "] voxy.json: " + e.getMessage(), e);
        }
    }
    public Optional<dev.vitrail.pack.model.BlendMode> translucentBlend(int outputSlot) {
        return Optional.ofNullable(blending.getOrDefault(outputSlot, blending.get(-1)));
    }

    private static String defaultSampler(String name) {
        return name.equals("shadowtex") ? "sampler2DShadow" : "sampler2D";
    }

    private static String patch(ShaderPackSource source, IncludeExpander expander, String prefix,
            String file, JsonObject json, String key) throws IOException {
        Optional<Path> path = source.file(prefix + file);
        if (path.isPresent()) return expander.expand(path.get()).text();
        return json.has(key) ? expander.expandInline(source.file(prefix + "voxy.json").orElseThrow(),
                json.get(key).getAsString()) : "";
    }
    private static List<Integer> targets(JsonObject json, String key) {
        var targets = json.getAsJsonArray(key).asList().stream().map(JsonElement::getAsInt).toList();
        if (targets.stream().anyMatch(i -> i < 0) || new HashSet<>(targets).size() != targets.size())
            throw new IllegalArgumentException("invalid target in " + key);
        return targets;
    }
    private static String configurationText(IncludeExpander.ExpandedUnit unit) {
        var text = new StringBuilder();
        StringBuilder multiline = null;
        for (int i = 0; i < unit.lines().size(); i++) {
            if (!unit.isLive(i)) continue;
            String line = unit.lines().get(i);
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("#error")) throw new IllegalArgumentException(trimmed);
            if (trimmed.startsWith("#")) {
                if (multiline != null && (trimmed.startsWith("#define ") || trimmed.startsWith("#undef ")))
                    multiline.append(line).append('\n');
                continue;
            }
            if (multiline != null) {
                // Raw GLSL can contain quotes and backslashes in comments and ASCII art.
                // The JSON delimiter follows the GLSL body and precedes a separator or EOL.
                int end = -1;
                for (int q = line.indexOf('"'); q >= 0; q = line.indexOf('"', q + 1)) {
                    String tail = line.substring(q + 1).stripLeading();
                    if (!trimmed.startsWith("//") && (tail.isEmpty() || ",}]".indexOf(tail.charAt(0)) >= 0)) {
                        end = q;
                        break;
                    }
                }
                if (end < 0) {
                    multiline.append(line).append('\n');
                    continue;
                }
                multiline.append(line, 0, end);
                text.append(new JsonPrimitive(multiline.toString()));
                multiline = null;
                line = line.substring(end + 1);
            }
            // Leave ordinary JSON strings and their escape sequences exactly as written.
            int quote = -1;
            for (int j = 0; j < line.length(); j++) {
                char ch = line.charAt(j);
                if (quote >= 0 && ch == '\\') j++;
                else if (ch == '"') quote = quote < 0 ? j : -1;
                else if (quote < 0 && line.startsWith("//", j)) break;
            }
            if (quote >= 0) {
                text.append(line, 0, quote);
                multiline = new StringBuilder(line.substring(quote + 1)).append('\n');
            } else {
                text.append(line).append('\n');
            }
        }
        if (multiline != null) throw new IllegalArgumentException("unterminated multiline string");
        return text.toString();
    }
}
