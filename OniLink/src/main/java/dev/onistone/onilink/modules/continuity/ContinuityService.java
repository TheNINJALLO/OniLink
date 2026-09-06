package dev.onistone.onilink.modules.continuity;

import dev.onistone.onilink.modules.ProxyOperations;
import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.events.OniEvent;
import dev.onistone.onilink.platform.events.OniEventType;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
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
    private final Clock clock;
    private final Duration timeout;

    public ContinuityService(
            PlatformDatabase database, ProxyOperations proxy, BoundedEventBus events,
            String limboBackend, int maxReservations
    ) {
        this(database, proxy, events, limboBackend, maxReservations, Duration.ofSeconds(120), Clock.systemUTC());
    }

    public ContinuityService(PlatformDatabase database, ProxyOperations proxy, BoundedEventBus events,
                             String limboBackend, int maxReservations, Duration timeout, Clock clock) {
        super(database);
        this.proxy = proxy;
        this.events = events;
        this.limboBackend = limboBackend == null ? "" : limboBackend.trim().toLowerCase();
        this.maxReservations = Math.min(10_000, maxReservations);
        this.clock = clock;
        this.timeout = timeout;
        if (maxReservations < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("invalid drain limits");
    }

    public synchronized Map<String, Object> status(PlatformDatabase.Scope scope) {
        reconcile(scope);
        return Map.of("limboBackend", limboBackend, "backends", proxy.backends(),
                "operations", views(database.list(scope, "drain-operation", 500)),
                "reservations", views(database.list(scope, "drain-reservation", maxReservations)));
    }

    public synchronized Map<String, Object> drain(PlatformDatabase.Scope scope, String backend, String actor) {
        String target = id(backend).toLowerCase();
        if (limboBackend.isBlank()) throw new IllegalStateException("continuity.limboBackend is not configured");
        if (target.equals(limboBackend)) throw new IllegalArgumentException("limbo backend cannot be drained into itself");
        requireHealthy(limboBackend);
        for (var operation : database.list(scope, "drain-operation", 500)) {
            if (target.equals(operation.value().get("backend")) && "DRAINING".equals(operation.value().get("state"))) {
                reconcile(scope);
                return view(database.get(scope, operation.kind(), operation.id()).orElseThrow());
            }
        }
        Map<String, PlatformDatabase.StoredRecord> reservations = new LinkedHashMap<>();
        database.list(scope, "drain-reservation", maxReservations).forEach(r -> reservations.put(r.id(), r));
        List<Map<String, Object>> players = proxy.players().stream()
                .filter(player -> target.equalsIgnoreCase(String.valueOf(player.get("backend")))).toList();
        long added = players.stream().filter(p -> !reservations.containsKey(String.valueOf(p.get("xuid")))).count();
        if (reservations.size() + added > maxReservations) throw new IllegalStateException("reservation limit reached");
        for (var player : players) {
            var reserved = reservations.get(required(player, "xuid", 32));
            if (reserved != null && !target.equals(reserved.value().get("backend"))) {
                throw new IllegalStateException("player has a reservation for another backend");
            }
        }
        Map<String, Object> registry = proxy.backendRegistry();
        long revision = longValue(registry.get("revision"), -1);
        proxy.setBackendDraining(target, true, revision);
        String operationId = java.util.UUID.randomUUID().toString();
        events.publish(OniEvent.of(OniEventType.BACKEND_DRAIN_STARTED,
                scope.tenantId(), scope.proxyId(), Map.of("backend", target, "operationId", operationId)));
        List<Map<String, Object>> failures = new ArrayList<>();
        int moved = 0;
        for (Map<String, Object> player : players) {
            String xuid = required(player, "xuid", 32);
            Map<String, Object> reservation = Map.of(
                    "xuid", xuid, "displayLabel", safePlayer(player), "backend", target,
                    "operationId", operationId, "createdAt", clock.instant().toString(), "state", "MOVING_TO_LIMBO",
                    "deadline", clock.instant().plus(timeout).toString());
            database.put(scope, "drain-reservation", id(xuid), reservations.containsKey(xuid) ? reservations.get(xuid).revision() : 0L, reservation);
            if (proxy.transfer(safePlayer(player), limboBackend)) moved++;
            else {
                failures.add(Map.of("player", safePlayer(player), "reason", "transfer did not start"));
                updateReservation(database.get(scope, "drain-reservation", xuid).orElseThrow(), "TRANSFER_TIMED_OUT");
            }
        }
        String state = "DRAINING";
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("backend", target);
        operation.put("limboBackend", limboBackend);
        operation.put("actor", actor);
        operation.put("state", state);
        operation.put("failures", failures);
        operation.put("deadline", clock.instant().plus(timeout).toString());
        operation.put("requested", moved);
        operation.put("moved", 0);
        database.put(scope, "drain-operation", operationId, 0L, operation);
        reconcile(scope);
        return view(database.get(scope, "drain-operation", operationId).orElseThrow());
    }

    public synchronized Map<String, Object> returnPlayers(PlatformDatabase.Scope scope, String backend, String actor) {
        String target = id(backend).toLowerCase();
        requireHealthy(target);
        for (var operation : database.list(scope, "drain-operation", 500)) {
            if (target.equals(operation.value().get("backend")) && "RETURNING".equals(operation.value().get("state"))) {
                reconcile(scope);
                return view(database.get(scope, operation.kind(), operation.id()).orElseThrow());
            }
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        int returned = 0;
        Map<String, Map<String, Object>> online = new java.util.HashMap<>();
        for (Map<String, Object> player : proxy.players()) online.put(String.valueOf(player.get("xuid")), player);
        for (PlatformDatabase.StoredRecord record : database.list(scope, "drain-reservation", maxReservations)) {
            if (!target.equals(record.value().get("backend"))) continue;
            Map<String, Object> player = online.get(String.valueOf(record.value().get("xuid")));
            if (player == null) {
                updateReservation(record, "RETURN_WHEN_ONLINE");
                continue;
            }
            if (arrived(player, target)) {
                database.delete(scope, record.kind(), record.id(), record.revision());
            } else if (arrived(player, limboBackend) && proxy.transfer(safePlayer(player), target)) {
                returned++;
                updateReservation(record, "RETURNING");
            } else {
                failures.add(Map.of("player", safePlayer(player), "reason", "return transfer did not start"));
            }
        }
        String operationId = java.util.UUID.randomUUID().toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backend", target);
        result.put("actor", actor);
        result.put("state", failures.isEmpty() ? "RETURNING" : "FAILED");
        result.put("requested", returned);
        result.put("returningXuids", database.list(scope, "drain-reservation", maxReservations).stream()
                .filter(r -> target.equals(r.value().get("backend")) && "RETURNING".equals(r.value().get("state")))
                .map(PlatformDatabase.StoredRecord::id).toList());
        result.put("returned", 0);
        result.put("deadline", clock.instant().plus(timeout).toString());
        result.put("failures", failures);
        database.put(scope, "drain-operation", operationId, 0L, result);
        reconcile(scope);
        return view(database.get(scope, "drain-operation", operationId).orElseThrow());
    }

    /** Reconciles persisted requests with actual live destinations, including after restart. */
    public synchronized void reconcile(PlatformDatabase.Scope scope) {
        Map<String, Map<String, Object>> online = new LinkedHashMap<>();
        proxy.players().forEach(player -> online.put(String.valueOf(player.get("xuid")), player));
        Instant now = clock.instant();
        for (var record : database.list(scope, "drain-reservation", maxReservations)) {
            var player = online.get(record.id());
            String state = String.valueOf(record.value().get("state"));
            String target = String.valueOf(record.value().get("backend"));
            if (state.equals("RETURNING") && player != null && arrived(player, target)) {
                database.delete(scope, record.kind(), record.id(), record.revision());
            } else if (state.equals("MOVING_TO_LIMBO")) {
                if (player == null) updateReservation(record, "OFFLINE");
                else if (arrived(player, limboBackend)) updateReservation(record, "IN_LIMBO");
                else if (expired(record.value(), now)) updateReservation(record, "TRANSFER_TIMED_OUT");
            } else if (state.equals("RETURNING") && expired(record.value(), now)) {
                updateReservation(record, player == null ? "RETURN_WHEN_ONLINE" : "RETURN_TIMED_OUT");
            } else if (state.equals("RETURN_WHEN_ONLINE") && player != null) {
                if (arrived(player, target)) database.delete(scope, record.kind(), record.id(), record.revision());
                else if (arrived(player, limboBackend)) {
                    try {
                        requireHealthy(target);
                        if (proxy.transfer(safePlayer(player), target)) updateReservation(record, "RETURNING");
                    } catch (IllegalStateException ignored) { /* Retry when the backend is healthy. */ }
                }
            }
        }
        var reservations = database.list(scope, "drain-reservation", maxReservations);
        for (var record : database.list(scope, "drain-operation", 500)) {
            String state = String.valueOf(record.value().get("state"));
            if (!state.equals("DRAINING") && !state.equals("RETURNING")) continue;
            String target = String.valueOf(record.value().get("backend"));
            var relevant = reservations.stream().filter(r -> target.equals(r.value().get("backend"))).toList();
            boolean complete = state.equals("DRAINING")
                    ? online.values().stream().noneMatch(p -> target.equalsIgnoreCase(String.valueOf(p.get("backend"))))
                        && relevant.stream().noneMatch(r -> "MOVING_TO_LIMBO".equals(r.value().get("state")))
                    : relevant.stream().allMatch(r -> "RETURN_WHEN_ONLINE".equals(r.value().get("state")));
            boolean failure = relevant.stream().anyMatch(r -> String.valueOf(r.value().get("state")).endsWith("TIMED_OUT"));
            if (!complete && !failure && !expired(record.value(), now)) continue;
            Map<String, Object> value = new LinkedHashMap<>(record.value());
            String outcome = !failure && complete ? state.equals("DRAINING") ? "DRAINED" : "ACTIVE" : "FAILED";
            value.put("state", outcome);
            value.put("moved", relevant.stream().filter(r -> "IN_LIMBO".equals(r.value().get("state"))).count());
            if (outcome.equals("FAILED")) value.put("failures", List.of(Map.of("reason", "transfer arrival was not confirmed before its deadline")));
            if (outcome.equals("ACTIVE")) {
                proxy.setBackendDraining(target, false, longValue(proxy.backendRegistry().get("revision"), -1));
                Object requested = value.getOrDefault("returningXuids", List.of());
                value.put("returned", ((List<?>) requested).stream().filter(xuid -> online.containsKey(xuid)
                        && arrived(online.get(xuid), target)).count());
            }
            database.put(scope, record.kind(), record.id(), record.revision(), value);
            if (state.equals("DRAINING")) events.publish(OniEvent.of(outcome.equals("DRAINED")
                            ? OniEventType.BACKEND_DRAIN_COMPLETED : OniEventType.BACKEND_DRAIN_FAILED,
                    scope.tenantId(), scope.proxyId(), Map.of("backend", target, "operationId", record.id())));
        }
    }

    public synchronized boolean drained(PlatformDatabase.Scope scope, String backend) {
        reconcile(scope);
        return database.list(scope, "drain-operation", 500).stream()
                .filter(r -> backend.equals(r.value().get("backend"))).findFirst()
                .map(r -> "DRAINED".equals(r.value().get("state"))).orElse(false)
                && proxy.players().stream().noneMatch(p -> backend.equals(p.get("backend")));
    }

    private void updateReservation(PlatformDatabase.StoredRecord record, String state) {
        Map<String, Object> value = new LinkedHashMap<>(record.value());
        value.put("state", state);
        if (state.equals("RETURNING")) value.put("deadline", clock.instant().plus(timeout).toString());
        database.put(record.scope(), record.kind(), record.id(), record.revision(), value);
    }
    private static boolean arrived(Map<String, Object> player, String backend) {
        return backend.equalsIgnoreCase(String.valueOf(player.get("backend"))) && !Boolean.TRUE.equals(player.get("switching"));
    }
    private static boolean expired(Map<String, Object> value, Instant now) {
        Object deadline = value.get("deadline");
        return deadline == null || !Instant.parse(String.valueOf(deadline)).isAfter(now);
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
