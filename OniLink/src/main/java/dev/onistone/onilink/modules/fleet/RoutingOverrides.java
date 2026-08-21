package dev.onistone.onilink.modules.fleet;

import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-blocking routing snapshot consumed by the RakNet join path.
 *
 * <p>Expansion services update this snapshot after their durable transaction commits. The packet
 * thread never performs database or control-plane I/O.</p>
 */
public final class RoutingOverrides {
    private record Assignment(String backend, Instant expiresAt) {
        private boolean expired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }

    private static final Map<String, Assignment> QUARANTINE = new ConcurrentHashMap<>();
    private static final Map<String, Assignment> CANARY = new ConcurrentHashMap<>();
    private static final Map<String, String> PROMOTED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> CANARY_ENABLED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> RESERVED_BACKENDS = new ConcurrentHashMap<>();

    private RoutingOverrides() {}

    public static Optional<String> backend(String tenantId, String proxyId, String xuid) {
        String scope = scope(tenantId, proxyId);
        String player = player(scope, xuid);
        Instant now = Instant.now();
        Assignment quarantined = active(QUARANTINE, player, now);
        if (quarantined != null) return Optional.of(quarantined.backend());
        if (CANARY_ENABLED.getOrDefault(scope, true)) {
            Assignment candidate = active(CANARY, player, now);
            if (candidate != null) return Optional.of(candidate.backend());
        }
        return Optional.ofNullable(PROMOTED.get(scope));
    }

    public static void quarantine(PlatformDatabase.Scope scope, String xuid, String backend) {
        quarantine(scope, xuid, backend, null);
    }

    public static void quarantine(
            PlatformDatabase.Scope scope, String xuid, String backend, Instant expiresAt
    ) {
        QUARANTINE.put(player(scopeKey(scope), xuid), new Assignment(normalized(backend), expiresAt));
    }

    public static void release(PlatformDatabase.Scope scope, String xuid) {
        QUARANTINE.remove(player(scopeKey(scope), xuid));
    }

    public static void canary(PlatformDatabase.Scope scope, String xuid, String backend, boolean selected) {
        canary(scope, xuid, backend, selected, null);
    }

    public static void canary(
            PlatformDatabase.Scope scope, String xuid, String backend, boolean selected, Instant expiresAt
    ) {
        String key = player(scopeKey(scope), xuid);
        if (selected) CANARY.put(key, new Assignment(normalized(backend), expiresAt)); else CANARY.remove(key);
    }

    public static void canaryEnabled(PlatformDatabase.Scope scope, boolean enabled) {
        CANARY_ENABLED.put(scopeKey(scope), enabled);
    }

    public static void promoted(PlatformDatabase.Scope scope, String backend) {
        String key = scopeKey(scope);
        if (backend == null || backend.isBlank()) PROMOTED.remove(key); else PROMOTED.put(key, normalized(backend));
    }

    public static void reserveBackend(String backend) {
        if (backend != null && !backend.isBlank()) RESERVED_BACKENDS.put(normalized(backend), true);
    }

    public static boolean reservedBackend(String backend) {
        return RESERVED_BACKENDS.containsKey(normalized(backend));
    }

    static void clearForTests() {
        QUARANTINE.clear();
        CANARY.clear();
        PROMOTED.clear();
        CANARY_ENABLED.clear();
        RESERVED_BACKENDS.clear();
    }

    private static Assignment active(Map<String, Assignment> assignments, String key, Instant now) {
        Assignment assignment = assignments.get(key);
        if (assignment != null && assignment.expired(now)) {
            assignments.remove(key, assignment);
            return null;
        }
        return assignment;
    }

    private static String scopeKey(PlatformDatabase.Scope scope) {
        return scope(scope.tenantId(), scope.proxyId());
    }

    private static String scope(String tenantId, String proxyId) {
        return normalized(tenantId) + '/' + normalized(proxyId);
    }

    private static String player(String scope, String xuid) {
        return scope + '/' + (xuid == null ? "" : xuid.trim());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
