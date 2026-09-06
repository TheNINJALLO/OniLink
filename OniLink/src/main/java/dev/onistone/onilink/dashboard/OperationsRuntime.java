package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.modules.*;
import dev.onistone.onilink.modules.operations.*;
import dev.onistone.onilink.modules.connect.*;
import dev.onistone.onilink.modules.continuity.ContinuityService;
import dev.onistone.onilink.modules.packs.*;
import dev.onistone.onilink.modules.pulse.OperationalMetrics;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.modules.ExpansionSettings;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Wires operational services to real scoped proxies and advances their durable work off packet loops. */
final class OperationsRuntime implements AutoCloseable {
    final ArtifactStore artifacts;
    final UpdateCenter updates;
    final ProtocolPackages protocols;
    final PackReleases packs;
    final PlayerNetwork players;
    final NetworkCoordinator cluster;
    final OperationalMetrics metrics;
    private final PlatformDatabase database;
    private final ProxyOperations proxy;
    private final ContinuityService continuity;
    private final BoundedEventBus events;
    private final ThreadPoolExecutor workers;
    private final boolean playerServices;
    private final boolean continuityEnabled;
    private final ScheduledExecutorService scheduler;
    private final Set<PlatformDatabase.Scope> scopes = ConcurrentHashMap.newKeySet();
    private final Set<PlatformDatabase.Scope> restored = ConcurrentHashMap.newKeySet();
    private final Set<PlatformDatabase.Scope> pending = ConcurrentHashMap.newKeySet();
    private final Map<PlatformDatabase.Scope, String> errors = new ConcurrentHashMap<>();
    private final NetworkCommandGateway.Handler commands;
    private final AutoCloseable playerEvents;
    OperationsRuntime(PlatformDatabase db, Path data, Path configPath, ExpansionSettings settings, ProxyOperations proxy,
                      ContinuityService continuity, PackScannerService scanner, BoundedEventBus events,
                      ThreadPoolExecutor workers, BiConsumer<PlatformDatabase.Scope, Runnable> scoped) throws IOException {
        database = db; this.proxy = proxy; this.continuity = continuity; this.events = events; this.workers = workers;
        playerServices = settings.enabled("connect"); continuityEnabled = settings.enabled("continuity");
        artifacts = new ArtifactStore(db, data, settings.integer("updates.maxArchiveBytes", 536870912, 1024, Integer.MAX_VALUE),
                Long.parseLong(settings.value("updates.quotaBytes", "4294967296")));
        protocols = new ProtocolPackages(db, artifacts, proxy, ProtocolPackages.readKeys(configuration(configPath, settings.value("protocols.trustedKeysFile", ""))));
        packs = new PackReleases(db, artifacts, scanner, proxy);
        updates = new UpdateCenter(db, artifacts, new ManagedServers(configuration(configPath, settings.value("updates.managedServersFile", "")), data), proxy, continuity);
        String authEnvironment = settings.value("pulse.otlp.authorizationEnvironment", "");
        if (!authEnvironment.isBlank() && !authEnvironment.matches("[A-Z_][A-Z0-9_]{0,127}")) throw new IllegalArgumentException("invalid OTLP authorization environment name");
        metrics = new OperationalMetrics(settings.value("pulse.otlp.metricsEndpoint", ""), authEnvironment.isBlank() ? "" : System.getenv(authEnvironment));
        cluster = new NetworkCoordinator(db, configuration(configPath, settings.value("cluster.configurationFile", "")));
        players = new PlayerNetwork(db, proxy); players.coordinator(cluster);
        commands = (scope, xuid, arguments) -> {
            List<String> response = new ArrayList<>();
            scoped.accept(scope, () -> { observe(scope); response.addAll(players.command(scope, xuid, arguments)); });
            return List.copyOf(response);
        };
        if (playerServices) NetworkCommandGateway.install(workers, commands);
        playerEvents = events.subscribe(dev.onistone.onilink.platform.events.OniEventType.PLAYER_AUTHENTICATED,
                event -> observe(PlatformDatabase.Scope.of(event.tenantId(), event.proxyId())));
        for (String kind : List.of("protocol-generation", "pack-current", "drain-reservation", "maintenance-job", "network-queue"))
            db.listAll(kind, 50000).forEach(r -> observe(r.scope()));
        observe(PlatformDatabase.Scope.of("provider", "main"));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread thread = new Thread(r, "onilink-maintenance-tick"); thread.setDaemon(true); return thread; });
        scheduler.scheduleWithFixedDelay(() -> {
            for (var scope : scopes) {
                if (!pending.add(scope)) continue;
                try { workers.execute(() -> {
                    try { scoped.accept(scope, () -> tick(scope)); }
                    catch (RuntimeException failure) { errors.put(scope, failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
                    finally { pending.remove(scope); }
                }); } catch (RejectedExecutionException busy) { pending.remove(scope); }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }
    void observe(PlatformDatabase.Scope scope) { if (scopes.size() < 2000 || scopes.contains(scope)) scopes.add(scope); }
    private synchronized void restore(PlatformDatabase.Scope scope) {
        if (restored.contains(scope)) return;
        try { protocols.restore(scope); packs.restore(scope); restored.add(scope); }
        catch (Exception failure) { throw new IllegalStateException("saved operational state could not be restored", failure); }
    }
    private void tick(PlatformDatabase.Scope scope) {
        restore(scope);
        if (continuityEnabled) continuity.reconcile(scope);
        var snapshot = cluster.heartbeat(scope, proxy.players());
        if (!snapshot.isEmpty()) applySharedState(scope, snapshot);
        if (playerServices) players.tick(scope);
        if (continuityEnabled) updates.tick(scope);
        errors.remove(scope);
        if (scope.equals(PlatformDatabase.Scope.of("provider", "main"))) metrics.export(metricsSnapshot(scope));
    }
    private void applySharedState(PlatformDatabase.Scope scope, Map<String, Object> snapshot) {
        // Nodes cache signed authority snapshots for visibility and enforcement; the authority remains the writer.
        if (!Boolean.TRUE.equals(cluster.status(scope).get("authority"))) for (String key : List.of("presence", "policies")) {
            if (!(snapshot.get(key) instanceof List<?> list)) continue;
            String kind = key.equals("presence") ? "cluster-presence" : "cluster-policy";
            Set<String> incoming = new HashSet<>();
            for (Object raw : list) {
                @SuppressWarnings("unchecked") var value = new LinkedHashMap<>((Map<String, Object>) raw);
                String id = String.valueOf(value.remove("id")); incoming.add(id);
                value.remove("revision"); value.remove("createdAt"); value.remove("updatedAt");
                database.put(scope, kind, id, null, value);
            }
            for (var record : database.list(scope, kind, 1000)) if (!incoming.contains(record.id())) database.delete(scope, kind, record.id(), record.revision());
        }
        for (var rule : database.list(scope, "cluster-policy", 1000)) {
            String type = String.valueOf(rule.value().get("type"));
            String target = String.valueOf(rule.value().get("target"));
            if (type.equals("moderation") && Boolean.TRUE.equals(rule.value().get("banned"))
                    && number(rule.value().getOrDefault("expiresAt", Long.MAX_VALUE)) > System.currentTimeMillis())
                proxy.disconnect(target, "Access restricted by network moderation.");
            if (type.equals("routing") && rule.value().get("enabled") instanceof Boolean enabled) {
                proxy.backends().stream().filter(b -> target.equals(b.get("name")) && !Objects.equals(b.get("enabled"), enabled)).findFirst()
                        .ifPresent(b -> proxy.setBackendEnabled(target, enabled, number(proxy.backendRegistry().get("revision"))));
            }
        }
    }
    Map<String, Object> status(PlatformDatabase.Scope scope) {
        observe(scope);
        return Map.of("updates", updates.status(scope), "protocols", protocols.status(scope), "packs", packs.status(scope),
                "players", players.status(scope), "cluster", cluster.status(scope), "metrics", metricsSnapshot(scope),
                "restoreError", errors.getOrDefault(scope, ""), "playerServicesEnabled", playerServices, "continuityEnabled", continuityEnabled);
    }
    Map<String, Object> metricsSnapshot(PlatformDatabase.Scope scope) { return metrics.snapshot(events.metrics(), workers.getQueue().size(), database.list(scope, "network-queue", 1000).size()); }
    Map<String, Object> action(PlatformDatabase.Scope scope, String operation, Map<String, Object> input, String actor) throws Exception {
        observe(scope);
        if (!Set.of("protocol.activate", "packs.activate").contains(operation)) restore(scope);
        return switch (operation) {
            case "update.preview" -> updates.preview(scope, text(input, "artifact"), text(input, "backend"));
            case "update.lab" -> updates.lab(scope, text(input, "artifact"), text(input, "fixtures"));
            case "update.accept" -> updates.acceptNative(scope, input, actor);
            case "update.deploy" -> {
                if (!continuityEnabled) throw new IllegalStateException("enable continuity before deploying");
                yield updates.deploy(scope, text(input, "artifact"), text(input, "backend"), text(input, "requestId"), actor);
            }
            case "update.rollback" -> {
                if (!continuityEnabled) throw new IllegalStateException("enable continuity before restoring a server");
                yield updates.rollback(scope, text(input, "job"), number(input.get("revision")));
            }
            case "update.recover" -> {
                if (!continuityEnabled) throw new IllegalStateException("enable continuity before recovering maintenance");
                yield updates.recover(scope, input, actor);
            }
            case "protocol.trust" -> protocols.trust(scope, text(input, "artifact"), text(input, "publisher"), text(input, "signature"));
            case "protocol.activate" -> protocols.activate(scope, ProtocolPackages.strings(input.get("artifacts")), number(input.get("revision")));
            case "packs.stage" -> packs.stage(scope, text(input, "name"), ProtocolPackages.strings(input.get("artifacts")));
            case "packs.activate" -> packs.activate(scope, text(input, "release"), number(input.get("revision")));
            case "players.admission" -> players.configure(scope, input);
            case "cluster.policy" -> {
                if (!(input.get("value") instanceof Map<?, ?> raw)) throw new IllegalArgumentException("policy value must be an object");
                Map<String, Object> value = new LinkedHashMap<>(); raw.forEach((k, v) -> value.put(String.valueOf(k), v));
                yield cluster.policy(scope, text(input, "kind"), text(input, "target"), value, number(input.get("revision")));
            }
            default -> throw new IllegalArgumentException("unknown operations action");
        };
    }
    private static Path configuration(Path config, String file) { return file.isBlank() ? null : config.toAbsolutePath().getParent().resolve(file).normalize(); }
    private static String text(Map<String, Object> input, String key) { if (!(input.get(key) instanceof String s) || s.isBlank()) throw new IllegalArgumentException(key + " is required"); return s; }
    private static long number(Object raw) { if (!(raw instanceof Number number)) throw new IllegalArgumentException("a numeric revision is required"); return number.longValue(); }
    @Override public void close() {
        scheduler.shutdownNow(); NetworkCommandGateway.uninstall(commands);
        try { playerEvents.close(); } catch (Exception ignored) { }
        cluster.close(); protocols.close();
    }
}
