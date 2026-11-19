package dev.vitrail.voxy;

import com.google.gson.*;
import dev.vitrail.pack.source.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public record VoxyPatch(String pack, String dimension, List<Integer> opaque, List<Integer> translucent,
        Map<Integer,dev.vitrail.pack.model.BlendMode> blending, boolean separateDepth, List<String> uniforms, Map<String,String> samplers,
        Map<String,String> shaderDefines,
        String opaqueSource, String translucentSource, String taaOffset) {
    public static VoxyPatch read(OpenedPack opened, String place) throws IOException {
        String prefix = place.isEmpty() ? "" : place + "/";
        var source = opened.source();
        var config = source.file(prefix + "voxy.json");
        if (config.isEmpty()) return null;
        try {
            var defines = new HashMap<>(opened.settings().globalDefines(opened.options()));
            defines.put("VOXY", "3");
            var json = JsonParser.parseString(filter(source.readLines(config.get()), defines)).getAsJsonObject();
            for (String key : json.keySet()) {
                if (!Set.of("version","opaqueDrawBuffers","translucentDrawBuffers","blending","excludeLodsFromVanillaDepth",
                        "uniforms","samplers","opaquePatchData","translucentPatchData","taaOffset").contains(key))
                    throw new IllegalArgumentException("unsupported field " + key);
            }
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
            for (String sampler : samplers.keySet()) {
                if (dev.vitrail.pack.model.TargetName.index(sampler).isEmpty()
                        && !Set.of("depthtex0","depthtex1","noisetex","shadowtex0","shadowtex1","shadowcolor0",
                            "vxDepthTexTrans","vxDepthTexOpaque","dhDepthTex0","dhDepthTex1").contains(sampler))
                    throw new IllegalArgumentException("unsupported sampler " + sampler);
                if (!samplers.get(sampler).equals("sampler2D"))
                    throw new IllegalArgumentException("unsupported sampler type for " + sampler + ": " + samplers.get(sampler));
            }
            var expander = new IncludeExpander(source, opened.settings());
            String opaqueSource = patch(source,expander,prefix,"voxy_opaque.glsl",json,"opaquePatchData");
            if (opaqueSource.isBlank()) throw new IllegalArgumentException("missing opaquePatchData");
            if (!json.has("uniforms")) throw new IllegalArgumentException("missing uniforms");
            return new VoxyPatch(source.packName(), place, opaque, translucent, Map.copyOf(blend),
                    json.has("excludeLodsFromVanillaDepth") && json.get("excludeLodsFromVanillaDepth").getAsBoolean(),
                    json.getAsJsonArray("uniforms").asList().stream().map(JsonElement::getAsString).toList(),
                    Collections.unmodifiableMap(samplers), Map.copyOf(opened.settings().unitDefines()),
                    opaqueSource,
                    patch(source,expander,prefix,"voxy_translucent.glsl",json,"translucentPatchData"),
                    patch(source,expander,prefix,"voxy_taa.glsl",json,"taaOffset"));
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
    private static String filter(List<String> lines, Map<String,String> defines) {
        var out = new StringBuilder();
        var stack = new ConditionStack();
        for (String line : lines) {
            String[] p = line.trim().split("\\s+",2);
            String arg = p.length == 2 ? p[1] : "";
            switch (p[0]) {
                case "#ifdef" -> stack.ifDirective(defines.containsKey(arg));
                case "#ifndef" -> stack.ifDirective(!defines.containsKey(arg));
                case "#if" -> stack.ifDirective(() -> PreprocessorExpression.evaluate(arg,defines).orElseThrow());
                case "#elif" -> stack.elifDirective(() -> PreprocessorExpression.evaluate(arg,defines).orElseThrow());
                case "#else" -> stack.elseDirective();
                case "#endif" -> stack.endifDirective();
                case "#include" -> {
                    if (stack.active()) out.append(line.replace("\"", "\\\"")).append('\n');
                }
                default -> { if (stack.active()) out.append(line).append('\n'); }
            }
        }
        if (stack.depth() != 0) throw new IllegalArgumentException("unterminated conditional");
        return out.toString();
    }
}
