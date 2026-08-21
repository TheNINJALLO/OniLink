package dev.onistone.onilink.modules.connect;

import dev.onistone.onilink.modules.ProxyOperations;
import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.events.OniEvent;
import dev.onistone.onilink.platform.events.OniEventType;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Local replaceable presence provider, XUID roles, and scoped in-game support tickets. */
public final class ConnectService extends ScopedRecords {
    private static final Set<String> TICKET_STATUSES = Set.of(
            "OPEN", "ACKNOWLEDGED", "WAITING_FOR_PLAYER", "WAITING_FOR_STAFF", "RESOLVED", "CLOSED");
    private final ProxyOperations proxy;
    private final BoundedEventBus events;
    private final int presenceExpirationSeconds;
    private final int maxOpenTickets;
    private final int ticketRateLimitMinutes;
    private final Map<String, Presence> presence = new ConcurrentHashMap<>();
    private final Map<String, Instant> ticketRateLimits = new ConcurrentHashMap<>();

    private record Presence(Map<String, Object> value, Instant expiresAt) {}

    public ConnectService(
            PlatformDatabase database, ProxyOperations proxy, BoundedEventBus events,
            int presenceExpirationSeconds, int maxOpenTickets, int ticketRateLimitMinutes
    ) {
        super(database);
        this.proxy = proxy;
        this.events = events;
        this.presenceExpirationSeconds = presenceExpirationSeconds;
        this.maxOpenTickets = maxOpenTickets;
        this.ticketRateLimitMinutes = ticketRateLimitMinutes;
    }

    public List<Map<String, Object>> presence(PlatformDatabase.Scope scope, boolean revealXuid) {
        Instant now = Instant.now();
        Set<String> online = new LinkedHashSet<>();
        for (Map<String, Object> player : proxy.players()) {
            String xuid = String.valueOf(player.get("xuid"));
            online.add(xuid);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", revealXuid ? xuid : pseudonym(xuid));
            value.put("displayLabel", player.getOrDefault("name", "Player"));
            value.put("online", true);
            value.put("proxy", scope.proxyId());
            value.put("backend", player.getOrDefault("backend", "connecting"));
            value.put("connectionMillis", player.getOrDefault("connectedMillis", 0));
            value.put("transferState", Boolean.TRUE.equals(player.get("switching")) ? "TRANSFERRING" : "IDLE");
            value.put("quarantineState", database.get(scope, "quarantine", xuid).isPresent() ? "QUARANTINED" : "NORMAL");
            value.put("lastActivityAt", now.toString());
            value.put("visibility", revealXuid ? "OPERATOR" : "LIMITED");
            presence.put(scope.tenantId() + '\0' + scope.proxyId() + '\0' + xuid,
                    new Presence(Map.copyOf(value), now.plusSeconds(presenceExpirationSeconds)));
        }
        presence.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        String prefix = scope.tenantId() + '\0' + scope.proxyId() + '\0';
        return presence.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> entry.getValue().value())
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("displayLabel")), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<Map<String, Object>> roles(PlatformDatabase.Scope scope) {
        return views(database.list(scope, "global-role", 1_000));
    }

    public List<Map<String, Object>> assignments(PlatformDatabase.Scope scope, boolean revealXuid) {
        return database.list(scope, "role-assignment", 10_000).stream().map(record -> {
            Map<String, Object> result = new LinkedHashMap<>(view(record));
            if (!revealXuid) result.put("xuid", pseudonym(String.valueOf(result.get("xuid"))));
            return Map.copyOf(result);
        }).toList();
    }

    public Map<String, Object> saveRole(
            PlatformDatabase.Scope scope, Map<String, Object> input, Long revision
    ) {
        String roleId = id(required(input, "name", 64).toLowerCase(Locale.ROOT).replace(' ', '-'));
        List<String> permissions = stringList(input.get("permissions"), 100);
        String parent = String.valueOf(input.getOrDefault("parent", "")).trim();
        if (roleId.equals(parent)) throw new IllegalArgumentException("role cannot inherit from itself");
        validateRoleParent(scope, roleId, parent);
        Map<String, Object> value = Map.of(
                "name", required(input, "name", 64), "description", String.valueOf(input.getOrDefault("description", "")),
                "parent", parent, "permissions", permissions, "updatedAt", Instant.now().toString());
        return view(database.put(scope, "global-role", roleId, revision, value));
    }

    public Map<String, Object> assignRole(
            PlatformDatabase.Scope scope, String xuid, String roleId, String expiresAt, String actor
    ) {
        String playerId = validateXuid(xuid);
        PlatformDatabase.StoredRecord role = database.get(scope, "global-role", id(roleId))
                .orElseThrow(() -> new IllegalArgumentException("unknown global role"));
        String assignmentId = UUID.randomUUID().toString();
        Map<String, Object> value = Map.of(
                "xuid", playerId, "role", role.id(), "expiresAt", expiresAt == null ? "" : expiresAt,
                "actor", actor, "syncState", "UNSUPPORTED", "assignedAt", Instant.now().toString());
        return view(database.put(scope, "role-assignment", assignmentId, null, value));
    }

    public Map<String, Object> removeAssignment(
            PlatformDatabase.Scope scope, String assignmentId, long revision
    ) {
        if (!database.delete(scope, "role-assignment", id(assignmentId), revision)) {
            throw new IllegalArgumentException("unknown role assignment");
        }
        return Map.of("removed", true, "assignmentId", assignmentId);
    }

    public Map<String, Object> effectivePermissions(PlatformDatabase.Scope scope, String xuid) {
        String playerId = validateXuid(xuid);
        Set<String> effective = new LinkedHashSet<>();
        List<String> roles = new ArrayList<>();
        Instant now = Instant.now();
        for (PlatformDatabase.StoredRecord assignment : database.list(scope, "role-assignment", 10_000)) {
            if (!playerId.equals(assignment.value().get("xuid"))) continue;
            String expiry = String.valueOf(assignment.value().getOrDefault("expiresAt", ""));
            if (!expiry.isBlank() && Instant.parse(expiry).isBefore(now)) continue;
            String roleId = String.valueOf(assignment.value().get("role"));
            collectRole(scope, roleId, effective, roles, new LinkedHashSet<>());
        }
        return Map.of("xuid", playerId, "roles", roles, "permissions", effective);
    }

    public List<Map<String, Object>> tickets(
            PlatformDatabase.Scope scope, String authenticatedXuid, boolean manage
    ) {
        return database.list(scope, "support-ticket", 10_000).stream()
                .filter(record -> manage || authenticatedXuid.equals(record.value().get("xuid")))
                .map(ScopedRecords::view)
                .toList();
    }

    public Map<String, Object> createTicket(
            PlatformDatabase.Scope scope, String xuid, String displayLabel, String backend,
            String clientProtocol, String category, String message, String journeyId, boolean highPriority
    ) {
        String playerId = validateXuid(xuid);
        Instant now = Instant.now();
        Instant limitedUntil = ticketRateLimits.get(scope.tenantId() + '\0' + playerId);
        if (limitedUntil != null && limitedUntil.isAfter(now)) {
            throw new IllegalStateException("ticket rate limit is active");
        }
        long open = tickets(scope, playerId, false).stream()
                .filter(ticket -> !Set.of("RESOLVED", "CLOSED").contains(ticket.get("status"))).count();
        if (open >= maxOpenTickets) throw new IllegalStateException("open ticket limit reached");
        if (message == null || message.isBlank() || message.length() > 4_000) {
            throw new IllegalArgumentException("ticket message is required and must be at most 4000 characters");
        }
        String ticketId = UUID.randomUUID().toString();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("xuid", playerId);
        value.put("displayLabel", displayLabel == null ? "Player" : displayLabel);
        value.put("backend", backend == null ? "" : backend);
        value.put("clientProtocol", clientProtocol == null ? "" : clientProtocol);
        value.put("category", category == null ? "general" : category);
        value.put("message", message.trim());
        value.put("status", "OPEN");
        value.put("priority", highPriority ? "HIGH" : "NORMAL");
        value.put("assignedOperator", "");
        value.put("journeyId", journeyId == null ? "" : journeyId);
        value.put("replies", List.of());
        value.put("createdAt", now.toString());
        value.put("updatedAt", now.toString());
        ticketRateLimits.put(scope.tenantId() + '\0' + playerId, now.plus(ticketRateLimitMinutes, ChronoUnit.MINUTES));
        Map<String, Object> result = view(database.put(scope, "support-ticket", ticketId, null, value));
        events.publish(OniEvent.of(OniEventType.SUPPORT_TICKET_CREATED,
                scope.tenantId(), scope.proxyId(), Map.of("ticketId", ticketId, "highPriority", highPriority)));
        return result;
    }

    public Map<String, Object> updateTicket(
            PlatformDatabase.Scope scope, String ticketId, long revision, String status,
            String reply, String actor, String authenticatedXuid, boolean manage
    ) {
        PlatformDatabase.StoredRecord existing = database.get(scope, "support-ticket", id(ticketId))
                .orElseThrow(() -> new IllegalArgumentException("unknown ticket"));
        if (!manage && !authenticatedXuid.equals(existing.value().get("xuid"))) {
            throw new SecurityException("players may update only their own tickets");
        }
        Map<String, Object> value = new LinkedHashMap<>(existing.value());
        if (status != null && !status.isBlank()) {
            String normalized = status.toUpperCase(Locale.ROOT);
            if (!TICKET_STATUSES.contains(normalized)) throw new IllegalArgumentException("unsupported ticket status");
            if (!manage && !"WAITING_FOR_STAFF".equals(normalized) && !"CLOSED".equals(normalized)) {
                throw new SecurityException("player cannot set that ticket status");
            }
            value.put("status", normalized);
        }
        if (reply != null && !reply.isBlank()) {
            if (reply.length() > 4_000) throw new IllegalArgumentException("reply is too long");
            List<Object> replies = new ArrayList<>();
            if (value.get("replies") instanceof List<?> stored) replies.addAll(stored);
            if (replies.size() >= 200) throw new IllegalStateException("ticket reply limit reached");
            replies.add(Map.of("actor", actor, "role", manage ? "staff" : "player",
                    "message", reply.trim(), "createdAt", Instant.now().toString()));
            value.put("replies", List.copyOf(replies));
        }
        value.put("updatedAt", Instant.now().toString());
        Map<String, Object> result = view(database.put(scope, "support-ticket", existing.id(), revision, value));
        events.publish(OniEvent.of(OniEventType.SUPPORT_TICKET_UPDATED,
                scope.tenantId(), scope.proxyId(), Map.of("ticketId", existing.id())));
        return result;
    }

    private void collectRole(
            PlatformDatabase.Scope scope, String roleId, Set<String> permissions,
            List<String> roles, Set<String> visiting
    ) {
        if (!visiting.add(roleId)) throw new IllegalStateException("global role hierarchy contains a cycle");
        PlatformDatabase.StoredRecord role = database.get(scope, "global-role", roleId).orElse(null);
        if (role == null) return;
        roles.add(roleId);
        permissions.addAll(stringList(role.value().get("permissions"), 100));
        String parent = String.valueOf(role.value().getOrDefault("parent", ""));
        if (!parent.isBlank()) collectRole(scope, parent, permissions, roles, visiting);
        visiting.remove(roleId);
    }

    private void validateRoleParent(PlatformDatabase.Scope scope, String roleId, String parent) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(roleId);
        String current = parent;
        while (!current.isBlank()) {
            if (!visited.add(current)) {
                throw new IllegalArgumentException("global role hierarchy would contain a cycle");
            }
            String requestedParent = current;
            PlatformDatabase.StoredRecord role = database.get(scope, "global-role", requestedParent)
                    .orElseThrow(() -> new IllegalArgumentException("unknown parent role: " + requestedParent));
            current = String.valueOf(role.value().getOrDefault("parent", "")).trim();
        }
    }

    private static List<String> stringList(Object value, int maximum) {
        if (!(value instanceof List<?> list)) return List.of();
        if (list.size() > maximum) throw new IllegalArgumentException("list exceeds maximum entries");
        return list.stream().map(String::valueOf)
                .filter(item -> item.matches("[a-z][a-z0-9._-]{1,127}"))
                .distinct().toList();
    }

    private static String validateXuid(String xuid) {
        String value = xuid == null ? "" : xuid.trim();
        if (!value.matches("[0-9]{6,20}")) throw new IllegalArgumentException("authenticated XUID is invalid");
        return value;
    }

    private static String pseudonym(String xuid) {
        return "player-" + Integer.toUnsignedString(xuid.hashCode(), 36);
    }
}
