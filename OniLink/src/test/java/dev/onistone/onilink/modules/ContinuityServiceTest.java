package dev.onistone.onilink.modules;

import dev.onistone.onilink.modules.continuity.ContinuityService;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ContinuityServiceTest {
    @TempDir Path directory;
    final PlatformDatabase.Scope scope = PlatformDatabase.Scope.of("tenant", "proxy");
    @Test void acceptedRequestsRequireActualArrivalAndOfflineReturnsSurviveRestart() throws Exception {
        FakeProxy proxy = new FakeProxy();
        proxy.player("1", "game", false);
        try (var db = new PlatformDatabase(directory); var events = new BoundedEventBus(64, Runnable::run)) {
            var service = new ContinuityService(db, proxy, events, "limbo", 100);
            var drain = service.drain(scope, "game", "admin");
            assertEquals("DRAINING", drain.get("state"));
            assertFalse(service.drained(scope, "game"));
            service.drain(scope, "game", "admin");
            assertEquals(1, proxy.transfers.size());
            proxy.player("1", "limbo", true);
            assertFalse(service.drained(scope, "game"));
            proxy.player("1", "limbo", false);
            assertTrue(service.drained(scope, "game"));
            assertEquals("RETURNING", service.returnPlayers(scope, "game", "admin").get("state"));
            assertTrue(proxy.draining.get("game"));
            assertTrue(db.get(scope, "drain-reservation", "1").isPresent());
            proxy.player("1", "game", false);
            service.reconcile(scope);
            assertFalse(proxy.draining.get("game"));
            assertTrue(db.list(scope, "drain-reservation", 100).isEmpty());

            service.drain(scope, "game", "admin");
            proxy.online.clear();
            service.reconcile(scope);
            service.returnPlayers(scope, "game", "admin");
            assertEquals("RETURN_WHEN_ONLINE", db.get(scope, "drain-reservation", "1").orElseThrow().value().get("state"));
        }
        try (var db = new PlatformDatabase(directory); var events = new BoundedEventBus(64, Runnable::run)) {
            var service = new ContinuityService(db, proxy, events, "limbo", 100);
            proxy.player("1", "limbo", false);
            service.reconcile(scope);
            assertEquals("RETURNING", db.get(scope, "drain-reservation", "1").orElseThrow().value().get("state"));
            proxy.player("1", "game", false);
            service.reconcile(scope);
            assertTrue(db.list(scope, "drain-reservation", 100).isEmpty());
        }
    }
    @Test void failedTransfersAndReservationCapacityNeverReportSuccess() throws Exception {
        FakeProxy proxy = new FakeProxy();
        proxy.player("1", "game", false);
        proxy.accept = false;
        try (var db = new PlatformDatabase(directory); var events = new BoundedEventBus(64, Runnable::run)) {
            var service = new ContinuityService(db, proxy, events, "limbo", 1);
            assertEquals("FAILED", service.drain(scope, "game", "admin").get("state"));
            assertFalse(service.drained(scope, "game"));
            proxy.player("2", "game", false);
            assertThrows(IllegalStateException.class, () -> service.drain(scope, "game", "admin"));
            assertEquals(1, proxy.transfers.size());
            assertTrue(db.list(PlatformDatabase.Scope.of("other", "proxy"), "drain-reservation", 100).isEmpty());
        }
    }
}
