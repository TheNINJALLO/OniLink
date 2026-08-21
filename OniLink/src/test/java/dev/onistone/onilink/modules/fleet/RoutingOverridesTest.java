package dev.onistone.onilink.modules.fleet;

import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingOverridesTest {
    private final PlatformDatabase.Scope scope = PlatformDatabase.Scope.of("tenant-a", "proxy-a");

    @AfterEach
    void clear() {
        RoutingOverrides.clearForTests();
    }

    @Test
    void quarantineWinsAndReleaseRestoresStickyCanary() {
        RoutingOverrides.canary(scope, "1000000000000001", "green", true);
        assertEquals("green", RoutingOverrides.backend("tenant-a", "proxy-a", "1000000000000001").orElseThrow());

        RoutingOverrides.quarantine(scope, "1000000000000001", "quarantine");
        assertEquals("quarantine", RoutingOverrides.backend("tenant-a", "proxy-a", "1000000000000001").orElseThrow());

        RoutingOverrides.release(scope, "1000000000000001");
        assertEquals("green", RoutingOverrides.backend("tenant-a", "proxy-a", "1000000000000001").orElseThrow());
        RoutingOverrides.canaryEnabled(scope, false);
        assertTrue(RoutingOverrides.backend("tenant-a", "proxy-a", "1000000000000001").isEmpty());
    }

    @Test
    void scopesCannotSeeAnotherTenantsRoutingState() {
        RoutingOverrides.promoted(scope, "green");
        assertEquals("green", RoutingOverrides.backend("tenant-a", "proxy-a", "xuid").orElseThrow());
        assertTrue(RoutingOverrides.backend("tenant-b", "proxy-a", "xuid").isEmpty());
    }

    @Test
    void expiredSafetyAndCanaryAssignmentsAreRemovedFromTheJoinSnapshot() {
        Instant expired = Instant.now().minusSeconds(1);
        RoutingOverrides.canary(scope, "1000000000000001", "green", true, expired);
        RoutingOverrides.quarantine(scope, "1000000000000001", "quarantine", expired);
        assertTrue(RoutingOverrides.backend("tenant-a", "proxy-a", "1000000000000001").isEmpty());
    }
}
