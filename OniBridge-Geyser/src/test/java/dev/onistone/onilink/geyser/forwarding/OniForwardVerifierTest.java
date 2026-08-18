package dev.onistone.onilink.geyser.forwarding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OniForwardVerifierTest {
    private static final String TOKEN = "T05JRgEOAQABMgIAC2tleS0yMDI2LTAxAwAGZWRnZS0xBAAMa2luZ2RvbS1tYWlu"
            + "BQAHa2luZ2RvbQYAJDAxOGY0N2YyLWMwMDEtNzAwMC04MDAwLTAwMDAwMDAwMDAwMQcAIDAwMTEyMjMz"
            + "NDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZmCAAEQWxleAkAEDI1MzMyNzQ3OTAzOTU5MDQKACQxMjNlNDU2"
            + "Ny1lODliLTEyZDMtYTQ1Ni00MjY2MTQxNzQwMDALAAwyMDAxOmRiODo6NDIMAAU1NDMyMQ0ADTE4MDAw"
            + "MDAwMDAwMDAOAA0xODAwMDAwMDA1MDAw.922WXG-qG04OJiAFAzPSlrNh4mi7LObu0V2oDdc9KX0";

    private final OniForwardVerifier verifier = new OniForwardVerifier(new OniForwardVerifier.KeyRing(
            new OniForwardVerifier.Key("key-2026-01", "correct horse battery staple".getBytes(StandardCharsets.UTF_8)),
            null));

    @Test
    void acceptsSharedProtocolVector() {
        OniForwardVerifier.Result result = verifier.verify(TOKEN, validation("Alex", "2533274790395904",
                "kingdom-main", "kingdom", 1_800_000_003_000L));

        assertTrue(result.valid(), result.error());
        assertEquals("2001:db8::42", result.claims().realIp());
        assertEquals(54321, result.claims().realPort());
    }

    @Test
    void rejectsSignatureMutation() {
        String mutated = TOKEN.substring(0, TOKEN.length() - 1) + (TOKEN.endsWith("A") ? "B" : "A");
        assertFalse(verifier.verify(mutated, validation("Alex", "2533274790395904",
                "kingdom-main", "kingdom", 1_800_000_003_000L)).valid());
    }

    @Test
    void bindsNameXuidBridgeAndBackend() {
        assertTrue(verifier.verify(TOKEN, validation("aLeX", "2533274790395904",
                "kingdom-main", "kingdom", 1_800_000_003_000L)).valid());
        assertFalse(verifier.verify(TOKEN, validation("Steve", "2533274790395904",
                "kingdom-main", "kingdom", 1_800_000_003_000L)).valid());
        assertFalse(verifier.verify(TOKEN, validation("Alex", "1",
                "kingdom-main", "kingdom", 1_800_000_003_000L)).valid());
        assertFalse(verifier.verify(TOKEN, validation("Alex", "2533274790395904",
                "other", "kingdom", 1_800_000_003_000L)).valid());
        assertFalse(verifier.verify(TOKEN, validation("Alex", "2533274790395904",
                "kingdom-main", "other", 1_800_000_003_000L)).valid());
    }

    @Test
    void rejectsExpiredAndFutureTokens() {
        assertFalse(verifier.verify(TOKEN, validation("Alex", "2533274790395904",
                "kingdom-main", "kingdom", 1_800_000_008_001L)).valid());
        assertFalse(verifier.verify(TOKEN, validation("Alex", "2533274790395904",
                "kingdom-main", "kingdom", 1_799_999_997_999L)).valid());
    }

    @Test
    void rejectsPaddingAndOversizeBeforeDecoding() {
        assertFalse(verifier.verify(TOKEN + "=", validation("Alex", "2533274790395904",
                "kingdom-main", "kingdom", 1_800_000_003_000L)).valid());
        OniForwardVerifier.Validation small = new OniForwardVerifier.Validation(
                "Alex", "2533274790395904", "kingdom-main", "kingdom",
                1_800_000_003_000L, 10_000, 2_000, 256);
        assertFalse(verifier.verify(TOKEN, small).valid());
    }

    private static OniForwardVerifier.Validation validation(
            String name, String xuid, String bridge, String backend, long now
    ) {
        return new OniForwardVerifier.Validation(name, xuid, bridge, backend, now, 10_000, 2_000, 4_096);
    }
}
