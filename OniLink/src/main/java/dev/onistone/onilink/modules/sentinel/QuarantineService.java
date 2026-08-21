package dev.onistone.onilink.modules.sentinel;

import dev.onistone.onilink.modules.ProxyOperations;
import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.events.OniEvent;
import dev.onistone.onilink.platform.events.OniEventType;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import dev.onistone.onilink.modules.fleet.RoutingOverrides;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** XUID-based, tenant-scoped quarantine assignments and immediate safe routing. */
public final class QuarantineService extends ScopedRecords {
    private final ProxyOperations proxy;
    private final BoundedEventBus events;
    private final String quarantineBackend;

    public QuarantineService(
            PlatformDatabase database, ProxyOperations proxy, BoundedEventBus events, String quarantineBackend
    ) {
        super(database);
        this.proxy = proxy;
        this.events = events;
        this.quarantineBackend = quarantineBackend == null ? "" : quarantineBackend.trim().toLowerCase();
    }

    public List<Map<String, Object>> list(PlatformDatabase.Scope scope) {
        return views(database.list(scope, "quarantine", 10_000));
    }

    public Map<String, Object> assign(
            PlatformDatabase.Scope scope, String xuid, String reason, String actor, String expiresAt
    ) {
        String playerId = xuid == null ? "" : xuid.trim();
        if (!playerId.matches("[0-9]{6,20}")) throw new IllegalArgumentException("authenticated XUID is invalid");
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("reason is required and must be at most 500 characters");
        }
        if (quarantineBackend.isBlank()) throw new IllegalStateException("sentinel.quarantineBackend is not configured");
        Map<String, Object> backend = proxy.backends().stream()
                .filter(item -> quarantineBackend.equalsIgnoreCase(String.valueOf(item.get("name"))))
                .findFirst().orElseThrow(() -> new IllegalStateException("quarantine backend is not configured"));
        Object health = backend.get("health");
        if (!(health instanceof Map<?, ?> map) || !"online".equalsIgnoreCase(String.valueOf(map.get("status")))) {
            throw new IllegalStateException("quarantine backend is not healthy");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("xuid", playerId);
        value.put("reason", reason.trim());
        value.put("actor", actor);
        value.put("backend", quarantineBackend);
        value.put("assignedAt", Instant.now().toString());
        Instant expiration = parseExpiration(expiresAt);
        value.put("expiresAt", expiration == null ? "" : expiration.toString());
        value.put("state", "ACTIVE");
        Map<String, Object> stored = view(database.put(scope, "quarantine", playerId, null, value));
        RoutingOverrides.quarantine(scope, playerId, quarantineBackend, expiration);
        proxy.players().stream()
                .filter(player -> playerId.equals(String.valueOf(player.get("xuid"))))
                .findFirst()
                .ifPresent(player -> proxy.transfer(String.valueOf(player.get("name")), quarantineBackend));
        events.publish(OniEvent.of(OniEventType.PLAYER_QUARANTINED,
                scope.tenantId(), scope.proxyId(), Map.of("assignmentId", playerId)));
        return stored;
    }

    public Map<String, Object> release(PlatformDatabase.Scope scope, String xuid) {
        PlatformDatabase.StoredRecord existing = database.get(scope, "quarantine", id(xuid))
                .orElseThrow(() -> new IllegalArgumentException("player is not quarantined"));
        database.delete(scope, "quarantine", existing.id(), existing.revision());
        RoutingOverrides.release(scope, existing.id());
        events.publish(OniEvent.of(OniEventType.PLAYER_RELEASED_FROM_QUARANTINE,
                scope.tenantId(), scope.proxyId(), Map.of("assignmentId", existing.id())));
        return Map.of("released", true, "xuid", existing.id());
    }

    public String route(PlatformDatabase.Scope scope, String xuid) {
        PlatformDatabase.StoredRecord assignment = database.get(scope, "quarantine", id(xuid)).orElse(null);
        if (assignment == null) return "";
        String expiration = String.valueOf(assignment.value().getOrDefault("expiresAt", ""));
        if (!expiration.isBlank() && Instant.parse(expiration).isBefore(Instant.now())) {
            database.delete(scope, "quarantine", assignment.id(), assignment.revision());
            RoutingOverrides.release(scope, assignment.id());
            return "";
        }
        return quarantineBackend;
    }

    private static Instant parseExpiration(String value) {
        if (value == null || value.isBlank()) return null;
        Instant expiration = Instant.parse(value.trim());
        if (!expiration.isAfter(Instant.now())) {
            throw new IllegalArgumentException("quarantine expiration must be in the future");
        }
        return expiration;
    }
}
