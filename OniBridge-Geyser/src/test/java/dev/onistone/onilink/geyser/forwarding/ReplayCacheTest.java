package dev.onistone.onilink.geyser.forwarding;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayCacheTest {
    @Test
    void consumesOnceAndFailsClosedAtCapacity() {
        ReplayCache cache = new ReplayCache(1);
        OniForwardVerifier.Claims first = claims("session-1", "nonce-1");
        OniForwardVerifier.Claims second = claims("session-2", "nonce-2");

        assertTrue(cache.consume(first, 1_000, 2_000));
        assertFalse(cache.consume(first, 1_001, 2_000));
        assertFalse(cache.consume(second, 1_001, 2_000));
        assertEquals(1, cache.size());
        assertTrue(cache.consume(second, 2_001, 3_000));
    }

    private static OniForwardVerifier.Claims claims(String session, String nonce) {
        return new OniForwardVerifier.Claims(2, "key", "proxy", "bridge", "backend", session, nonce,
                "Alex", "1", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "127.0.0.1", 19132, 1_000, 2_000);
    }
}
