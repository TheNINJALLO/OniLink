package dev.onistone.onilink.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardAccountsTest {
    @Test
    void firstRunCreatesAHashedOwnerAndExpiringSession(@TempDir Path directory) throws Exception {
        DashboardAccounts accounts = new DashboardAccounts(directory, 60, "http://127.0.0.1:8080");
        String setupCode = Files.readAllLines(accounts.setupPath()).stream()
                .filter(line -> line.startsWith("Setup code: "))
                .findFirst().orElseThrow().substring("Setup code: ".length());

        DashboardAccounts.BrowserSession session = accounts.setupOwner(
                setupCode, "NetworkOwner", "correct horse battery 123");

        assertFalse(accounts.setupRequired());
        assertEquals("NetworkOwner", accounts.authenticate(session.token()).orElseThrow().username());
        assertFalse(Files.exists(accounts.setupPath()));
        String stored = Files.readString(directory.resolve("accounts.properties"));
        assertFalse(stored.contains("correct horse battery 123"));
        assertTrue(stored.contains("passwordHash"));
        assertTrue(stored.contains(Integer.toString(DashboardAccounts.PASSWORD_ITERATIONS)));
    }

    @Test
    void verifiesStandardSixDigitTotpVector() {
        assertTrue(DashboardAccounts.verifyTotp(
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", Instant.ofEpochSecond(59)));
        assertFalse(DashboardAccounts.verifyTotp(
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "000000", Instant.ofEpochSecond(59)));
    }

    @Test
    void jsonNeverEmitsNonFiniteNumbers() {
        assertEquals("[null,null,null]", DashboardJson.encode(
                List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)));
    }
}
