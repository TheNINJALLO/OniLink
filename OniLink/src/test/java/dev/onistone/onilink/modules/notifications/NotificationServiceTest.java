package dev.onistone.onilink.modules.notifications;

import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationServiceTest {
    @TempDir Path directory;

    @Test
    void subscriptionSecretsStayOutOfApiViewsAndPayloadsAreRedacted() throws Exception {
        PlatformDatabase.Scope scope = PlatformDatabase.Scope.of("tenant-a", "proxy-a");
        try (PlatformDatabase database = new PlatformDatabase(directory);
             BoundedEventBus events = new BoundedEventBus(16, Runnable::run);
             NotificationService service = new NotificationService(
                     database, events, 2, "", "", "mailto:test@example.com", false)) {
            service.subscribe(scope, "operator", Map.of(
                    "endpoint", "https://push.example.test/device/one",
                    "p256dh", "A".repeat(64), "auth", "B".repeat(16),
                    "deviceName", "iPhone", "topics", List.of("DRAIN_FAILED")));
            Map<String, Object> snapshot = service.snapshot(scope, "operator");
            String publicJson = snapshot.toString();
            assertFalse(publicJson.contains("push.example.test"));
            assertFalse(publicJson.contains("A".repeat(64)));

            Map<String, Object> notification = service.enqueue(scope, "operator", "DRAIN_FAILED",
                    "Player 1000000000000001 at 192.0.2.10:19132 token=super-secret", "/#/continuity");
            String summary = String.valueOf(notification.get("summary"));
            assertTrue(summary.contains("[player]"));
            assertTrue(summary.contains("[address]"));
            assertTrue(summary.contains("[redacted]"));
            assertFalse(summary.contains("1000000000000001"));
            assertFalse(summary.contains("super-secret"));
        }
    }
}
