package dev.onistone.onilink.geyser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientDataJwtTest {
    @Test
    void decodesCanonicalCompactJwsPayload() {
        String json = "{\"OniForward\":\"abc.def\"}";
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(json, ClientDataJwt.payloadJson("header." + payload + ".signature"));
    }

    @Test
    void acceptsOlderDecodedPayloadStorage() {
        String json = "{\"OniForward\":\"abc.def\"}";
        assertEquals(json, ClientDataJwt.payloadJson(json));
    }

    @Test
    void rejectsMalformedPaddedAndMultipleSegmentInput() {
        assertThrows(IllegalArgumentException.class, () -> ClientDataJwt.payloadJson("not-a-jwt"));
        assertThrows(IllegalArgumentException.class, () -> ClientDataJwt.payloadJson("a.e30=.b"));
        assertThrows(IllegalArgumentException.class, () -> ClientDataJwt.payloadJson("a.e30.b.c"));
    }
}
