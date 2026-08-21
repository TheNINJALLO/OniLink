package dev.onistone.onilink.modules.continuity;

import dev.onistone.onilink.modules.ProxyOperations;
import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.events.OniEvent;
import dev.onistone.onilink.platform.events.OniEventType;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Limbo-backed drain/return state machine with durable return reservations. */
public final class ContinuityService extends ScopedRecords {
    private final ProxyOperations proxy;
    private final BoundedEventBus events;
    private final String limboBackend;
    private final int maxReservations;

    public ContinuityService(
            PlatformDatabase database, ProxyOperations proxy, BoundedEventBus events,
            String limboBackend, int maxReservations
    ) {
        super(database);
        this.proxy = proxy;
        this.events = events;
        this.limboBackend = limboBackend == null ? "" : limboBackend.trim().toLowerCase();
        this.maxReservations = maxReservations;
    }

    public Map<String, Object> status(PlatformDatabase.Scope scope) {
        return Map.of("limboBackend", limboBackend, "backends", proxy.backends(),
                "operations", views(database.list(scope, "drain-operation", 500)),
                "reservations", views(database.list(scope, "drain-reservation", maxReservations)));
    }

    public Map<String, Object> drain(PlatformDatabase.Scope scope, String backend, String actor) {
        String target = id(backend).toLowerCase();
        if (limboBackend.isBlank()) throw new IllegalStateException("continuity.limboBackend is not configured");
        if (target.equals(limboBackend)) throw new IllegalArgumentException("limbo backend cannot be drained into itself");
        requireHealthy(limboBackend);
        Map<String, Object> registry = proxy.backendRegistry();
        long revision = longValue(registry.get("revision"), -1);
        proxy.setBackendDraining(target, true, revision);
        String operationId = java.util.UUID.randomUUID().toString();
        events.publish(OniEvent.of(OniEventType.BACKEND_DRAIN_STARTED,
                scope.tenantId(), scope.proxyId(), Map.of("backend", target, "operationId", operationId)));
        List<Map<String, Object>> failures = new ArrayList<>();
        int moved = 0;
        for (Map<String, Object> player : proxy.players()) {
            if (!target.equalsIgnoreCase(String.valueOf(player.get("backend")))) continue;
            if (database.list(scope, "drain-reservation", maxReservations + 1).size() >= maxReservations) {
                failures.add(Map.of("player", safePlayer(player), "reason", "reservation limit reached"));
                continue;
            }
            String xuid = required(player, "xuid", 32);
            Map<String, Object> reservation = Map.of(
                    "xuid", xuid, "displayLabel", safePlayer(player), "backend", target,
                    "operationId", operationId, "createdAt", Instant.now().toString(), "state", "RESERVED");
            database.put(scope, "drain-reservation", id(xuid), null, reservation);
            if (proxy.transfer(safePlayer(player), limboBackend)) moved++;
            else failures.add(Map.of("player", safePlayer(player), "reason", "transfer did not start"));
        }
        String state = failures.isEmpty() ? "DRAINED" : "FAILED";
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("backend", target);
        operation.put("limboBackend", limboBackend);
        operation.put("actor", actor);
        operation.put("state", state);
        operation.put("moved", moved);
        operation.put("failures", failures);
        operation.put("updatedAt", Instant.now().toString());
        Map<String, Object> stored = view(database.put(scope, "drain-operation", operationId, null, operation));
        events.publish(OniEvent.of(failures.isEmpty() ? OniEventType.BACKEND_DRAIN_COMPLETED
                        : OniEventType.BACKEND_DRAIN_FAILED,
                scope.tenantId(), scope.proxyId(), Map.of("backend", target, "operationId", operationId)));
        return stored;
    }

    public Map<String, Object> returnPlayers(PlatformDatabase.Scope scope, String backend, String actor) {
        String target = id(backend).toLowerCase();
        requireHealthy(target);
        List<Map<String, Object>> failures = new ArrayList<>();
        int returned = 0;
        Map<String, Map<String, Object>> online = new java.util.HashMap<>();
        for (Map<String, Object> player : proxy.players()) online.put(String.valueOf(player.get("xuid")), player);
        for (PlatformDatabase.StoredRecord record : database.list(scope, "drain-reservation", maxReservations)) {
            if (!target.equals(record.value().get("backend"))) continue;
            Map<String, Object> player = online.get(String.valueOf(record.value().get("xuid")));
            if (player == null) {
                database.delete(scope, "drain-reservation", record.id(), record.revision());
                continue;
            }
            if (proxy.transfer(safePlayer(player), target)) {
                returned++;
                database.delete(scope, "drain-reservation", record.id(), record.revision());
            } else {
                failures.add(Map.of("player", safePlayer(player), "reason", "return transfer did not start"));
            }
        }
        Map<String, Object> registry = proxy.backendRegistry();
        proxy.setBackendDraining(target, false, longValue(registry.get("revision"), -1));
        String operationId = java.util.UUID.randomUUID().toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backend", target);
        result.put("actor", actor);
        result.put("state", failures.isEmpty() ? "ACTIVE" : "FAILED");
        result.put("returned", returned);
        result.put("failures", failures);
        return view(database.put(scope, "drain-operation", operationId, null, result));
    }

    private void requireHealthy(String backend) {
        Map<String, Object> found = proxy.backends().stream()
                .filter(item -> backend.equalsIgnoreCase(String.valueOf(item.get("name"))))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown backend " + backend));
        Object healthValue = found.get("health");
        String status = healthValue instanceof Map<?, ?> map ? String.valueOf(map.get("status")) : "unknown";
        if (!"online".equalsIgnoreCase(status)) throw new IllegalStateException(backend + " is not healthy");
    }

    private static String safePlayer(Map<String, Object> player) {
        return String.valueOf(player.getOrDefault("name", "player"));
    }
}
