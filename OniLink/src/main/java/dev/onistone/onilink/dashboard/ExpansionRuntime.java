package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.modules.ProxyOperations;
import dev.onistone.onilink.modules.connect.ConnectService;
import dev.onistone.onilink.modules.connect.SupportCommandGateway;
import dev.onistone.onilink.modules.continuity.ContinuityService;
import dev.onistone.onilink.modules.fleet.FleetService;
import dev.onistone.onilink.modules.fleet.RoutingOverrides;
import dev.onistone.onilink.modules.flow.OniFlowService;
import dev.onistone.onilink.modules.forge.ForgeService;
import dev.onistone.onilink.modules.notifications.NotificationService;
import dev.onistone.onilink.modules.packs.PackScannerService;
import dev.onistone.onilink.modules.pulse.JourneyService;
import dev.onistone.onilink.modules.sentinel.QuarantineService;
import dev.onistone.onilink.platform.actions.ActionRegistry;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.modules.ConfiguredModule;
import dev.onistone.onilink.platform.modules.ExpansionSettings;
import dev.onistone.onilink.platform.modules.ModuleCapabilities;
import dev.onistone.onilink.platform.modules.ModuleContext;
import dev.onistone.onilink.platform.modules.ModuleManager;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns the isolated expansion modules used by the authenticated HTTP plane. */
final class ExpansionRuntime implements AutoCloseable {
    private final ExpansionSettings settings;
    private final ExecutorService workers;
    private final BoundedEventBus events;
    private final ActionRegistry actions = new ActionRegistry();
    private final PlatformDatabase database;
    private final ModuleManager modules;
    private final ProxyOperations proxy;
    private final ThreadLocal<PlatformDatabase.Scope> activeScope = new ThreadLocal<>();
    private final OniFlowService flow;
    private final ContinuityService continuity;
    private final QuarantineService quarantine;
    private final JourneyService journeys;
    private final ForgeService forge = new ForgeService();
    private final FleetService fleet;
    private final ConnectService connect;
    private final PackScannerService packs;
    private final NotificationService notifications;
    private final SupportCommandGateway.Handler supportCommands;

    ExpansionRuntime(
            Path configPath,
            Path dataDirectory,
            Function<PlatformDatabase.Scope, DashboardControl> controlResolver
    ) throws IOException {
        this.settings = ExpansionSettings.load(configPath);
        this.workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "onilink-platform-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.events = new BoundedEventBus(4_096, workers);
        this.database = new PlatformDatabase(dataDirectory);
        this.proxy = adapter(controlResolver, activeScope);
        this.continuity = new ContinuityService(database, proxy, events,
                settings.value("continuity.limboBackend", ""),
                settings.integer("continuity.maxReservations", 10_000, 1, 100_000));
        this.quarantine = new QuarantineService(database, proxy, events,
                settings.value("sentinel.quarantineBackend", ""));
        if (enabled("continuity")) {
            RoutingOverrides.reserveBackend(settings.value("continuity.limboBackend", ""));
        }
        if (enabled("sentinel")) {
            RoutingOverrides.reserveBackend(settings.value("sentinel.quarantineBackend", ""));
        }
        this.journeys = new JourneyService(proxy,
                settings.integer("journeys.maxRecords", 10_000, 10, 100_000),
                settings.integer("journeys.retentionHours", 72, 1, 8_760));
        this.fleet = new FleetService(database, proxy, events,
                settings.integer("fleet.maxDynamicBackends", 1_000, 1, 10_000),
                settings.integer("fleet.maxCanaryPercentage", 25, 0, 100),
                settings.integer("fleet.canaryAssignmentMinutes", 120, 1, 10_080));
        this.connect = new ConnectService(database, proxy, events,
                settings.integer("presence.expirationSeconds", 60, 5, 3_600),
                settings.integer("support.maxOpenTicketsPerPlayer", 5, 1, 100),
                settings.integer("support.ticketRateLimitMinutes", 10, 1, 1_440));
        this.supportCommands = this::supportCommand;
        if (enabled("connect")) SupportCommandGateway.install(workers, supportCommands);
        this.packs = new PackScannerService(database, events,
                settings.integer("packs.scanner.maxArchiveBytes", 268_435_456, 1_024, Integer.MAX_VALUE),
                settings.integer("packs.scanner.maxEntries", 10_000, 1, 100_000),
                longSetting("packs.scanner.maxExpandedBytes", 1_073_741_824L, 1_024, 8_589_934_592L));
        this.notifications = new NotificationService(database, events,
                settings.integer("notifications.maxSubscriptionsPerUser", 10, 1, 100),
                settings.value("notifications.vapidPublicKey", ""),
                environmentSecret("notifications.vapidPrivateKeyEnvironment"),
                settings.value("notifications.vapidSubject", "mailto:admin@onilink.local"),
                enabled("notifications"));
        if (enabled("fleet")) restoreDynamicBackends();
        restoreRoutingSnapshots();
        registerActions();
        this.flow = new OniFlowService(database, actions, events, workers,
                settings.integer("flow.maxWorkflows", 500, 1, 10_000),
                settings.integer("flow.maxSteps", 100, 1, 1_000),
                settings.integer("flow.maxParallelBranches", 8, 1, 32),
                settings.integer("flow.maxExecutionSeconds", 3_600, 1, 86_400),
                settings.integer("flow.maxConcurrentExecutions", 32, 1, 256),
                enabled("flow"));
        ModuleContext context = new ModuleContext(events, actions, database, workers, settings.values());
        this.modules = new ModuleManager(context);
        registerModules();
        modules.startAll();
    }

    private List<String> supportCommand(
            PlatformDatabase.Scope scope, String xuid, String displayLabel, String backend,
            List<String> arguments
    ) {
        if (arguments.isEmpty()) {
            return List.of("Usage: /support create <message> | status [ticket-id] | reply <ticket-id> <message>");
        }
        String operation = arguments.get(0).toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "create" -> {
                String message = String.join(" ", arguments.subList(1, arguments.size())).trim();
                Map<String, Object> ticket = connect.createTicket(
                        scope, xuid, displayLabel, backend, "", "in-game", message, "", false);
                yield List.of("Support ticket created: " + ticket.get("id"));
            }
            case "status" -> {
                String requested = arguments.size() > 1 ? arguments.get(1) : "";
                List<Map<String, Object>> tickets = connect.tickets(scope, xuid, false).stream()
                        .filter(ticket -> requested.isBlank() || requested.equals(ticket.get("id")))
                        .limit(10).toList();
                if (tickets.isEmpty()) yield List.of("No matching support tickets.");
                yield tickets.stream().map(ticket -> ticket.get("id") + " — " + ticket.get("status"))
                        .toList();
            }
            case "reply" -> {
                if (arguments.size() < 3) throw new IllegalArgumentException(
                        "Usage: /support reply <ticket-id> <message>");
                String ticketId = arguments.get(1);
                PlatformDatabase.StoredRecord ticket = database.get(scope, "support-ticket", ticketId)
                        .orElseThrow(() -> new IllegalArgumentException("unknown ticket"));
                connect.updateTicket(scope, ticketId, ticket.revision(), "WAITING_FOR_STAFF",
                        String.join(" ", arguments.subList(2, arguments.size())), displayLabel, xuid, false);
                yield List.of("Reply added to support ticket " + ticketId + '.');
            }
            default -> List.of("Usage: /support create <message> | status [ticket-id] | reply <ticket-id> <message>");
        };
    }

    private void restoreRoutingSnapshots() {
        Instant now = Instant.now();
        if (enabled("sentinel")) {
            for (PlatformDatabase.StoredRecord record : database.listAll("quarantine", 50_000)) {
                String expiration = String.valueOf(record.value().getOrDefault("expiresAt", ""));
                if (!expiration.isBlank() && Instant.parse(expiration).isBefore(now)) continue;
                String backend = String.valueOf(record.value().getOrDefault("backend", ""));
                if (!backend.isBlank()) RoutingOverrides.quarantine(record.scope(), record.id(), backend,
                        expiration.isBlank() ? null : Instant.parse(expiration));
            }
        }
        if (enabled("fleet")) {
            for (PlatformDatabase.StoredRecord record : database.listAll("canary", 50_000)) {
                String expiration = String.valueOf(record.value().getOrDefault("expiresAt", ""));
                if (!expiration.isBlank() && Instant.parse(expiration).isBefore(now)) continue;
                String backend = String.valueOf(record.value().getOrDefault("backend", ""));
                boolean selected = Boolean.TRUE.equals(record.value().get("selected"));
                if (!backend.isBlank()) RoutingOverrides.canary(record.scope(), record.id(), backend, selected,
                        expiration.isBlank() ? null : Instant.parse(expiration));
            }
            for (PlatformDatabase.StoredRecord record : database.listAll("deployment", 10_000)) {
                String color = String.valueOf(record.value().getOrDefault("activeColor", "BLUE"));
                String backend = String.valueOf(record.value().getOrDefault(
                        "GREEN".equals(color) ? "greenBackend" : "blueBackend", ""));
                if (!backend.isBlank()) RoutingOverrides.promoted(record.scope(), backend);
                if ("CANARY_STOPPED".equals(record.value().get("promotionState"))) {
                    RoutingOverrides.canaryEnabled(record.scope(), false);
                }
            }
        }
    }

    private void restoreDynamicBackends() {
        for (PlatformDatabase.StoredRecord record : database.listAll("dynamic-backend", 50_000)) {
            try {
                withScope(record.scope(), () -> {
                    String name = String.valueOf(record.value().getOrDefault("name", record.id()));
                    boolean exists = proxy.backends().stream()
                            .anyMatch(backend -> name.equalsIgnoreCase(String.valueOf(backend.get("name"))));
                    if (exists) return null;
                    Map<String, String> values = new LinkedHashMap<>();
                    values.put("name", name);
                    values.put("host", String.valueOf(record.value().get("host")));
                    values.put("port", String.valueOf(record.value().get("port")));
                    values.put("protocol", String.valueOf(record.value().getOrDefault("protocol", "auto")));
                    values.put("proxyId", String.valueOf(record.value().get("proxyId")));
                    values.put("bridgeId", String.valueOf(record.value().get("bridgeId")));
                    values.put("keyId", String.valueOf(record.value().get("keyId")));
                    String secret = String.valueOf(record.value().getOrDefault("secretReference", ""));
                    if (secret.startsWith("env:")) values.put("secretEnvironment", secret.substring(4));
                    if (secret.startsWith("file:")) values.put("secretFile", secret.substring(5));
                    values.put("revision", String.valueOf(proxy.backendRegistry().get("revision")));
                    try {
                        Map<String, Object> restored = proxy.registerBackend(values);
                        long restoredRevision = ((Number) restored.get("revision")).longValue();
                        if (Boolean.FALSE.equals(record.value().get("enabled"))) {
                            Map<String, Object> disabled = proxy.setBackendEnabled(name, false, restoredRevision);
                            restoredRevision = ((Number) disabled.get("revision")).longValue();
                        }
                        Map<String, Object> updated = new LinkedHashMap<>(record.value());
                        updated.put("runtimeRevision", restoredRevision);
                        updated.put("restoredAt", Instant.now().toString());
                        updated.remove("restoreError");
                        database.put(record.scope(), record.kind(), record.id(), record.revision(), updated);
                    } catch (RuntimeException failure) {
                        Map<String, Object> updated = new LinkedHashMap<>(record.value());
                        updated.put("restoreError", "Runtime validation failed; review this backend definition");
                        database.put(record.scope(), record.kind(), record.id(), record.revision(), updated);
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                PlatformDatabase.StoredRecord current = database.get(
                        record.scope(), record.kind(), record.id()).orElse(null);
                if (current != null) {
                    Map<String, Object> updated = new LinkedHashMap<>(current.value());
                    updated.put("restoreError", "The scoped proxy is unavailable; start it and retry");
                    database.put(current.scope(), current.kind(), current.id(), current.revision(), updated);
                }
            }
        }
    }

    boolean enabled(String id) {
        return switch (id) {
            case "forge" -> true;
            case "packs" -> settings.enabled("packs.scanner");
            default -> settings.enabled(id);
        };
    }

    List<Map<String, Object>> modules() { return modules.snapshot(); }
    Map<String, Long> eventMetrics() { return events.metrics(); }
    List<Map<String, Object>> actionDescriptors() { return actions.descriptors(); }
    OniFlowService flow() { return flow; }
    ContinuityService continuity() { return continuity; }
    QuarantineService quarantine() { return quarantine; }
    JourneyService journeys() { return journeys; }
    ForgeService forge() { return forge; }
    FleetService fleet() { return fleet; }
    ConnectService connect() { return connect; }
    PackScannerService packs() { return packs; }
    NotificationService notifications() { return notifications; }
    ActionRegistry actions() { return actions; }

    <T> T withScope(PlatformDatabase.Scope scope, Supplier<T> operation) {
        PlatformDatabase.Scope previous = activeScope.get();
        activeScope.set(scope);
        try {
            return operation.get();
        } finally {
            if (previous == null) activeScope.remove(); else activeScope.set(previous);
        }
    }

    private void registerModules() {
        modules.register(module("shared-platform", true, Set.of(), Set.of("/api/modules"), Set.of(),
                Set.of("platform_events_total", "platform_events_dropped_total")));
        modules.register(module("control", enabled("control"), Set.of("shared-platform"),
                Set.of("/api/control/status", "/api/control/capabilities", "/api/control/protocol-lab/send"),
                Set.of("control.view", "control.protocol_lab"), Set.of("control_requests_total")));
        modules.register(module("flow", enabled("flow"), Set.of("shared-platform"),
                Set.of("/api/flow/workflows", "/api/flow/executions"),
                Set.of("flow.view", "flow.manage", "flow.execute"), Set.of("flow_executions_total")));
        modules.register(module("continuity", enabled("continuity"), Set.of("shared-platform"),
                Set.of("/api/continuity/backends"), Set.of("continuity.view", "continuity.drain", "continuity.return"),
                Set.of("drain_operations_total")));
        modules.register(module("sentinel", enabled("sentinel"), Set.of("shared-platform"),
                Set.of("/api/security/quarantine"), Set.of("security.quarantine.view", "security.quarantine.manage"),
                Set.of("quarantine_assignments")));
        modules.register(module("pulse", enabled("pulse"), Set.of("shared-platform"),
                Set.of("/api/journeys"), Set.of("journeys.view"), Set.of("journey_stage_milliseconds")));
        modules.register(module("forge", true, Set.of("shared-platform"),
                Set.of("/api/protocols/diff", "/api/compatibility/matrix"),
                Set.of("protocols.compare", "compatibility.view"), Set.of("compatibility_rows")));
        modules.register(module("fleet", enabled("fleet"), Set.of("shared-platform", "pulse"),
                Set.of("/api/fleet/backends", "/api/fleet/canaries", "/api/fleet/deployments"),
                Set.of("fleet.view", "fleet.manage", "fleet.promote"), Set.of("canary_assignments")));
        modules.register(module("connect", enabled("connect"), Set.of("shared-platform"),
                Set.of("/api/presence", "/api/roles", "/api/support/tickets"),
                Set.of("presence.view", "roles.view", "roles.manage", "support.create", "support.view_own", "support.manage"),
                Set.of("presence_online", "support_tickets")));
        modules.register(module("packs", enabled("packs"), Set.of("shared-platform"),
                Set.of("/api/packs/scan", "/api/packs/scans"), Set.of("packs.scan"), Set.of("pack_scans_total")));
        modules.register(module("notifications", enabled("notifications"), Set.of("shared-platform"),
                Set.of("/api/notifications/subscriptions", "/api/notifications/test"),
                Set.of("notifications.manage", "notifications.subscribe"), Set.of("notifications_queued_total")));
    }

    private ConfiguredModule module(
            String id, boolean enabled, Set<String> dependencies, Set<String> routes,
            Set<String> permissions, Set<String> metrics
    ) {
        return new ConfiguredModule(id, enabled, dependencies, new ModuleCapabilities(
                "1", routes, permissions, Set.of(), Set.of(), metrics,
                Set.of(id), Set.of("platform-schema-v1"), Set.of("platform/onilink-platform.db"),
                Map.of("enabled", enabled, "defaultEnabled", Set.of("pulse", "packs").contains(id))));
    }

    private void registerActions() {
        action("SEND_PLAYER_MESSAGE", "operator", Set.of("xuid", "message"), false, true,
                (context, input) -> result(proxy.message(text(input, "xuid"), text(input, "message"))));
        action("START_PLAYER_TRACE", "operator", Set.of("xuid"), false, true,
                (context, input) -> result(proxy.trace(text(input, "xuid"), number(input, "milliseconds", 60_000))));
        action("STOP_PLAYER_TRACE", "operator", Set.of("xuid"), false, true,
                (context, input) -> result(proxy.trace(text(input, "xuid"), 0)));
        action("TRANSFER_PLAYER", "operator", Set.of("xuid", "backend"), true, false,
                (context, input) -> result(transferXuid(text(input, "xuid"), text(input, "backend"))));
        action("SET_BACKEND_DRAINING", "admin", Set.of("backend"), true, false,
                (context, input) -> continuity.drain(scope(context), text(input, "backend"), context.actor()));
        action("CLEAR_BACKEND_DRAINING", "admin", Set.of("backend"), true, false,
                (context, input) -> continuity.returnPlayers(scope(context), text(input, "backend"), context.actor()));
        action("MOVE_PLAYERS_TO_LIMBO", "admin", Set.of("backend"), true, false,
                (context, input) -> continuity.drain(scope(context), text(input, "backend"), context.actor()));
        action("RETURN_DRAINED_PLAYERS", "admin", Set.of("backend"), true, false,
                (context, input) -> continuity.returnPlayers(scope(context), text(input, "backend"), context.actor()));
        action("QUARANTINE_PLAYER", "admin", Set.of("xuid", "reason"), true, false,
                (context, input) -> quarantine.assign(scope(context), text(input, "xuid"), text(input, "reason"),
                        context.actor(), String.valueOf(input.getOrDefault("expiresAt", ""))));
        action("RELEASE_QUARANTINED_PLAYER", "admin", Set.of("xuid"), true, false,
                (context, input) -> quarantine.release(scope(context), text(input, "xuid")));
        action("REGISTER_BACKEND", "admin", Set.of("name", "host", "port", "proxyId", "bridgeId", "keyId", "secretEnvironment"),
                true, false, (context, input) -> fleet.register(scope(context), strings(input)));
        action("UPDATE_BACKEND", "admin", Set.of("name", "host", "port", "proxyId", "bridgeId", "keyId", "secretEnvironment", "recordRevision"),
                true, false, (context, input) -> fleet.update(scope(context), strings(input), number(input, "recordRevision", -1)));
        action("REMOVE_BACKEND", "admin", Set.of("name", "runtimeRevision", "recordRevision"), true, false,
                (context, input) -> fleet.remove(scope(context), text(input, "name"),
                        number(input, "runtimeRevision", -1), number(input, "recordRevision", -1)));
        action("ASSIGN_CANARY", "admin", Set.of("xuid", "backend", "percentage"), true, true,
                (context, input) -> fleet.assignCanary(scope(context), text(input, "xuid"), text(input, "backend"),
                        (int) number(input, "percentage", 0), Boolean.TRUE.equals(input.get("testAccount")),
                        String.valueOf(input.getOrDefault("globalRole", ""))));
        action("PROMOTE_GREEN_BACKEND", "owner", Set.of("deploymentId", "revision"), true, false,
                (context, input) -> fleet.deploymentAction(scope(context), text(input, "deploymentId"),
                        "PROMOTE_GREEN", number(input, "revision", -1)));
        action("ROLLBACK_TO_BLUE_BACKEND", "owner", Set.of("deploymentId", "revision"), true, false,
                (context, input) -> fleet.deploymentAction(scope(context), text(input, "deploymentId"),
                        "ROLLBACK_TO_BLUE", number(input, "revision", -1)));
        action("CREATE_SUPPORT_TICKET", "viewer", Set.of("xuid", "message"), false, true,
                (context, input) -> connect.createTicket(scope(context), text(input, "xuid"),
                        String.valueOf(input.getOrDefault("displayLabel", "Player")),
                        String.valueOf(input.getOrDefault("backend", "")),
                        String.valueOf(input.getOrDefault("clientProtocol", "")),
                        String.valueOf(input.getOrDefault("category", "general")), text(input, "message"),
                        String.valueOf(input.getOrDefault("journeyId", "")), Boolean.TRUE.equals(input.get("highPriority"))));
        action("UPDATE_SUPPORT_TICKET", "operator", Set.of("ticketId", "revision"), false, true,
                (context, input) -> connect.updateTicket(scope(context), text(input, "ticketId"),
                        number(input, "revision", -1), String.valueOf(input.getOrDefault("status", "")),
                        String.valueOf(input.getOrDefault("reply", "")), context.actor(), "", true));
        action("REQUEST_PUSH_NOTIFICATION", "admin", Set.of("user", "summary"), false, true,
                (context, input) -> notifications.enqueue(scope(context), text(input, "user"),
                        String.valueOf(input.getOrDefault("topic", "TEST")), text(input, "summary"),
                        String.valueOf(input.getOrDefault("route", "/#/notifications"))));
    }

    private void action(
            String id, String role, Set<String> required, boolean confirmation, boolean safeTransfer,
            BiFunction<ActionRegistry.Context, Map<String, Object>, Map<String, Object>> handler
    ) {
        actions.register(new ActionRegistry.Descriptor(id, 1, role, ActionRegistry.TenantBehavior.EXACT_SCOPE,
                required, Set.of("milliseconds", "expiresAt", "testAccount", "displayLabel", "backend",
                        "clientProtocol", "category", "journeyId", "highPriority", "status", "reply", "topic", "route",
                        "revision", "recordRevision", "runtimeRevision", "secretFile", "protocol", "globalRole"),
                Duration.ofSeconds(30), true, confirmation, safeTransfer,
                id.toLowerCase(Locale.ROOT).replace('_', '.'),
                (context, input) -> withScope(scope(context), () -> handler.apply(context, input))));
    }

    private boolean transferXuid(String xuid, String backend) {
        return proxy.players().stream().filter(player -> xuid.equals(String.valueOf(player.get("xuid"))))
                .findFirst().map(player -> proxy.transfer(String.valueOf(player.get("name")), backend)).orElse(false);
    }

    private long longSetting(String key, long fallback, long minimum, long maximum) {
        String raw = settings.value(key, Long.toString(fallback));
        try {
            long result = Long.parseLong(raw);
            if (result < minimum || result > maximum) throw new IllegalArgumentException(key + " is outside its limit");
            return result;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private String environmentSecret(String key) {
        String name = settings.value(key, "");
        if (name.isBlank()) return "";
        if (!name.matches("[A-Z_][A-Z0-9_]{1,127}")) {
            throw new IllegalArgumentException(key + " must name an environment variable");
        }
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static ProxyOperations adapter(
            Function<PlatformDatabase.Scope, DashboardControl> resolver,
            ThreadLocal<PlatformDatabase.Scope> activeScope
    ) {
        return new ProxyOperations() {
            private DashboardControl control() {
                PlatformDatabase.Scope scope = activeScope.get();
                if (scope == null) throw new IllegalStateException("proxy scope is not bound");
                return resolver.apply(scope);
            }
            @Override public List<Map<String, Object>> players() { return control().players(true); }
            @Override public List<Map<String, Object>> backends() { return control().backends(true); }
            @Override public Map<String, Object> backendRegistry() { return control().backendRegistry(); }
            @Override public Map<String, Object> registerBackend(Map<String, String> values) { return control().registerBackend(values); }
            @Override public Map<String, Object> updateBackend(Map<String, String> values) { return control().updateBackend(values); }
            @Override public Map<String, Object> removeBackend(Map<String, String> values) { return control().removeBackend(values); }
            @Override public Map<String, Object> setBackendDraining(String backend, boolean draining, long revision) {
                return control().setBackendDraining(backend, draining, revision);
            }
            @Override public Map<String, Object> setBackendEnabled(String backend, boolean enabled, long revision) {
                return control().setBackendEnabled(backend, enabled, revision);
            }
            @Override public Map<String, Object> controlStatus() { return control().oniControlStatus(); }
            @Override public boolean transfer(String displayName, String backend) {
                return control().transfer(displayName, backend).success();
            }
            @Override public boolean message(String xuid, String message) {
                return control().messagePlayer(xuid, message).success();
            }
            @Override public boolean trace(String xuid, long milliseconds) {
                return control().traceXuid(xuid, milliseconds).success();
            }
        };
    }

    private static PlatformDatabase.Scope scope(ActionRegistry.Context context) {
        return PlatformDatabase.Scope.of(context.tenantId(), context.proxyId());
    }
    private static Map<String, Object> result(boolean success) { return Map.of("success", success); }
    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(key + " is required");
        return String.valueOf(value).trim();
    }
    private static long number(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(key + " must be a number"); }
    }
    private static Map<String, String> strings(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, String.valueOf(value)));
        return result;
    }

    @Override
    public void close() {
        SupportCommandGateway.uninstall(supportCommands);
        flow.close();
        notifications.close();
        modules.close();
        events.close();
        workers.shutdownNow();
        database.close();
    }
}
