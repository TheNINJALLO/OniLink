package dev.onistone.onilink.control.wire;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OniControlWireTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

    @Test
    void matchesTheSharedCppSignatureVector() {
        ControlEnvelope unsigned = new ControlEnvelope(
                1, "control-key-1", "123e4567-e89b-12d3-a456-426614174000",
                "idempotency-1", 1_787_184_000_000L, "AAECAwQFBgcICQoLDA0ODw",
                "survival-main", "survival", "2533274790000001", "TELEPORT",
                "eyJwYXlsb2FkVmVyc2lvbiI6MSwidmFsdWVzIjp7IngiOjEuMjUsInkiOjY0LCJ6IjotMi41fX0",
                "");
        String expected = "Y_aDTdDJCzyiZEDsuUMyypns_xltMjDRZD5DiYMiTjY";
        assertEquals(expected, ControlSigner.signRequest(unsigned, SECRET));
        assertTrue(ControlSigner.verifyRequest(unsigned.withSignature(expected), SECRET));
    }

    @Test
    void signedEnvelopeRoundTripsThroughBoundedFraming() throws Exception {
        ControlEnvelope envelope = ControlEnvelope.signed("key-1", UUID.randomUUID().toString(),
                "once-1", "survival-main", "survival", "2533274790000001",
                "GET_PLAYER_STATE", Map.of("payloadVersion", 1), SECRET);
        assertTrue(ControlSigner.verifyRequest(envelope, SECRET));
        assertFalse(ControlSigner.verifyRequest(envelope, "different-secret-different-secret!".getBytes()));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ControlFrameCodec.write(new DataOutputStream(bytes), envelope.asMap(), 262_144);
        ControlEnvelope decoded = ControlEnvelope.parse(ControlFrameCodec.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 262_144));
        assertEquals(envelope, decoded);
        assertEquals(1L, decoded.decodedPayload(1024).get("payloadVersion"));
    }

    @Test
    void frameDecoderRejectsOversizedAndTruncatedFrames() {
        assertThrows(java.io.IOException.class, () -> ControlFrameCodec.read(
                new DataInputStream(new ByteArrayInputStream(new byte[]{0, 1, 0, 0})), 1024));
        assertThrows(java.io.IOException.class, () -> ControlFrameCodec.read(
                new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0, 0, 4, '{'})), 1024));
    }

    @Test
    void replayCacheIsBoundedAndRejectsDuplicateNonces() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        ControlReplayCache cache = new ControlReplayCache(2, 120_000, clock);
        assertTrue(cache.consume("nonce-a"));
        assertFalse(cache.consume("nonce-a"));
        assertTrue(cache.consume("nonce-b"));
        assertFalse(cache.consume("nonce-c"));
        assertEquals(2, cache.size());
    }
}
