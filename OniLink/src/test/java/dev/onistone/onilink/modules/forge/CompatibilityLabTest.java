package dev.onistone.onilink.modules.forge;

import dev.onistone.onilink.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompatibilityLabTest {
    @Test void allBaselineCategoriesReplayExactWireBytesAndIncorrectExpectationsFail() {
        var lab = new CompatibilityLab();
        var registry = ProtocolRegistry.createDefault();
        var suite = ProtocolFixtureSuites.suite();
        var result = lab.run(registry, suite);
        assertEquals("PASS", result.get("status"), result.toString());
        assertEquals(true, result.get("coverageComplete"));
        assertEquals(false, result.get("liveAcceptance"));
        var first = new LinkedHashMap<String, Object>(); ((Map<?, ?>) ((List<?>) suite.get("fixtures")).getFirst()).forEach((k, v) -> first.put(String.valueOf(k), v));
        first.put("expected", "AQIDBA==");
        assertEquals("FAIL", lab.run(registry, Map.of("sanitized", true, "fixtures", List.of(first))).get("status"));
        first.put("expected", first.get("input")); first.put("category", "inventory");
        assertEquals("FAIL", lab.run(registry, Map.of("sanitized", true, "fixtures", List.of(first))).get("status"));
        assertThrows(IllegalArgumentException.class, () -> lab.run(registry, Map.of("fixtures", suite.get("fixtures"))));
    }
}
