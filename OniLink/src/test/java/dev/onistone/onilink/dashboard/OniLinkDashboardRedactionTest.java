package dev.onistone.onilink.dashboard;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OniLinkDashboardRedactionTest {
    @Test
    void supportTextRedactsControlIdentityAndAddressFields() throws Exception {
        Method method = OniLinkDashboard.class.getDeclaredMethod("redactSupportText", String.class);
        method.setAccessible(true);
        String input = "{\"actor\":\"operator-one\",\"targetXuid\":\"1000000000000001\","
                + "\"connectionId\":\"connection-secret\",\"address\":\"198.51.100.42:25570\"}";
        String redacted = (String) method.invoke(null, input);

        assertFalse(redacted.contains("operator-one"));
        assertFalse(redacted.contains("1000000000000001"));
        assertFalse(redacted.contains("connection-secret"));
        assertFalse(redacted.contains("198.51.100.42"));
        assertTrue(redacted.contains("<redacted>"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void supportControlStatusKeepsAggregatesAndDropsPerSessionDetails() throws Exception {
        Method method = OniLinkDashboard.class.getDeclaredMethod("redactedControlStatus", Map.class);
        method.setAccessible(true);
        Map<String, Object> input = Map.of(
                "controlEnabled", true,
                "tenantId", "private-tenant",
                "bridges", List.of(Map.of("backend", "survival", "host", "10.0.0.5",
                        "lastError", "failed at 10.0.0.5:19132")),
                "virtualInventorySessions", List.of(Map.of("connectionId", "private-connection")),
                "privateEntities", List.of(Map.of("connectionId", "private-connection")),
                "fakeBlocks", List.of(Map.of("connectionId", "private-connection")));

        Map<String, Object> redacted = (Map<String, Object>) method.invoke(null, input);
        assertFalse(redacted.containsKey("tenantId"));
        assertEquals(1, redacted.get("virtualInventorySessionCount"));
        assertEquals(1, redacted.get("privateEntityCount"));
        assertEquals(1, redacted.get("fakeBlockCount"));
        String encoded = DashboardJson.encode(redacted);
        assertFalse(encoded.contains("private-connection"));
        assertFalse(encoded.contains("10.0.0.5"));
    }
}
