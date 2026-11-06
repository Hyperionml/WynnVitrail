package dev.wynnvitrail.ambience;

import dev.vitrail.pack.option.OptionValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbienceProfileTest {
	@Test
	void presetIsIsolatedFromPersonalOptionsAndRespectsForcedValues() {
		var chosen = new HashMap<>(Map.of("personal", OptionValue.on(), "locked", OptionValue.off()));
		var profile = new AmbienceProfile();
		profile.options.putAll(Map.of("locked", "true", "effect", "3", "missing", "1"));
		var loaded = profile.copy();
		profile.options.put("effect", "5");
		loaded.applyOptions(chosen, Set.of("locked"), Set.of("locked", "effect", "personal"));
		assertEquals(Set.of("effect", "locked"), chosen.keySet());
		assertEquals("3", chosen.get("effect").text());
		assertFalse(chosen.get("locked").asBoolean());
		assertNotEquals(loaded.cacheKey("pack"), profile.cacheKey("pack"));
	}
}
