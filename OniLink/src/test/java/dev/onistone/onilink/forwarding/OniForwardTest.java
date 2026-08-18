package dev.onistone.onilink.forwarding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OniForwardTest {
    private static final String VECTOR = "T05JRgEOAQABMgIAC2tleS0yMDI2LTAxAwAGZWRnZS0xBAAMa2luZ2RvbS1tYWluBQAHa2luZ2RvbQYAJDAxOGY0N2YyLWMwMDEtNzAwMC04MDAwLTAwMDAwMDAwMDAwMQcAIDAwMTEyMjMzNDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZmCAAEQWxleAkAEDI1MzMyNzQ3OTAzOTU5MDQKACQxMjNlNDU2Ny1lODliLTEyZDMtYTQ1Ni00MjY2MTQxNzQwMDALAAwyMDAxOmRiODo6NDIMAAU1NDMyMQ0ADTE4MDAwMDAwMDAwMDAOAA0xODAwMDAwMDA1MDAw.922WXG-qG04OJiAFAzPSlrNh4mi7LObu0V2oDdc9KX0";

    private OniForward.Claims claims() {
        return new OniForward.Claims(2, "key-2026-01", "edge-1", "kingdom-main", "kingdom",
                "018f47f2-c001-7000-8000-000000000001", "00112233445566778899aabbccddeeff",
                "Alex", "2533274790395904", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "2001:db8::42", 54321, 1_800_000_000_000L, 1_800_000_005_000L);
    }

    private OniForward.Key key() {
        return new OniForward.Key("key-2026-01", "correct horse battery staple".getBytes(StandardCharsets.UTF_8));
    }

    private OniForward.Validation validation() {
        return new OniForward.Validation("aLeX", "kingdom-main", "kingdom", 1_800_000_001_000L, 10_000, 2_000, 4_096);
    }

    @Test
    void matchesSharedVector() {
        assertEquals(VECTOR, OniForward.sign(claims(), key()));
        assertTrue(OniForward.verify(VECTOR, new OniForward.KeyRing(key(), null), validation()).valid());
    }

    @Test
    void tamperingFails() {
        assertFalse(OniForward.verify(VECTOR.substring(0, VECTOR.length() - 1) + "A", new OniForward.KeyRing(key(), null), validation()).valid());
    }

    @Test
    void contextBindingFailsClosed() {
        var wrong = new OniForward.Validation("Alex", "other", "kingdom", 1_800_000_001_000L, 10_000, 2_000, 4_096);
        assertFalse(OniForward.verify(VECTOR, new OniForward.KeyRing(key(), null), wrong).valid());
    }

    @Test
    void rotationAcceptsPreviousKey() {
        var active = new OniForward.Key("key-2026-02", "another secret that is rotated in".getBytes(StandardCharsets.UTF_8));
        assertTrue(OniForward.verify(VECTOR, new OniForward.KeyRing(active, key()), validation()).valid());
    }
}

