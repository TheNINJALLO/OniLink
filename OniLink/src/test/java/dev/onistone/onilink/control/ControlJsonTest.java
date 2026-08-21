package dev.onistone.onilink.control;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlJsonTest {
    @Test
    void roundTripsStrictTypedObjects() {
        Map<String, Object> input = Map.of("action", "SEND_MESSAGE", "payload",
                Map.of("message", "hello", "count", 2), "enabled", true);
        Map<String, Object> decoded = ControlJson.parseObject(ControlJson.encode(input), 4096);
        assertEquals("SEND_MESSAGE", decoded.get("action"));
        assertEquals(true, decoded.get("enabled"));
        assertEquals("hello", ((Map<?, ?>) decoded.get("payload")).get("message"));
        assertEquals(2L, ((Map<?, ?>) decoded.get("payload")).get("count"));
    }

    @Test
    void rejectsDuplicateUnknownSyntaxAndOversizeInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ControlJson.parseObject("{\"a\":1,\"a\":2}", 1024));
        assertThrows(IllegalArgumentException.class,
                () -> ControlJson.parseObject("{\"a\":NaN}", 1024));
        assertThrows(IllegalArgumentException.class,
                () -> ControlJson.parseObject("{\"a\":1}", 2));
    }

    @Test
    void actionPayloadRejectsRawProtocolAndCredentialFieldsRecursively() {
        assertThrows(IllegalArgumentException.class,
                () -> new ValidatedActionPayload(1, Map.of("rawBytes", "AA")));
        assertThrows(IllegalArgumentException.class,
                () -> new ValidatedActionPayload(1, Map.of("nested", Map.of("jwt", "secret"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ValidatedActionPayload(1,
                        Map.of("steps", List.of(Map.of("networkStackId", 42)))));
    }
}
