package dev.wynnvitrail.ambience;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import dev.vitrail.pack.option.OptionValue;

public class AmbienceProfile {
	public String id = "";
	public String shaderPack = "";
	public Map<String, String> options = new LinkedHashMap<>();

	public void applyOptions(Map<String, OptionValue> chosen, Set<String> forced, Set<String> declared) {
		// A transient preset starts with the shader's defaults, except for explicitly forced engine settings.
		chosen.keySet().retainAll(forced);
		if (options == null) return;
		options.forEach((name, value) -> {
			if (declared.contains(name) && !forced.contains(name) && value != null) {
				chosen.put(name, OptionValue.parse(value));
			}
		});
	}

	public String cacheKey(String resolvedShaderPack) {
		StringBuilder builder = new StringBuilder(resolvedShaderPack).append('|');
		if (options == null || options.isEmpty()) {
			return builder.append("{}").toString();
		}
		options.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> builder
				.append(entry.getKey())
				.append('=')
				.append(entry.getValue())
				.append(';'));
		return builder.toString();
	}

	public AmbienceProfile copy() {
		AmbienceProfile copy = new AmbienceProfile();
		copy.id = id;
		copy.shaderPack = shaderPack;
		copy.options = options == null ? new LinkedHashMap<>() : new LinkedHashMap<>(options);
		return copy;
	}
}
