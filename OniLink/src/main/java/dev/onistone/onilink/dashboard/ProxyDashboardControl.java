package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.OniLink;
import dev.onistone.onilink.backend.BackendDirectory;
import dev.onistone.onilink.backend.BackendSwitcher;
import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.config.BackendConfig;
import dev.onistone.onilink.config.ProxyConfig;
import dev.onistone.onilink.allowlist.ProxyAllowlist;
import dev.onistone.onilink.listener.BedrockProxyListener;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Real dashboard view and controls over the running OniLink proxy. */
final class ProxyDashboardControl implements DashboardControl {
    private final ProxyConfig config;
    private final BedrockProxyListener listener;
    private final BackendDirectory backendDirectory;
    private final BackendSwitcher switcher;
    private final BackendHealthMonitor healthMonitor;
    private final long startedAtMillis = System.currentTimeMillis();

    ProxyDashboardControl(ProxyConfig config, BedrockProxyListener listener) {
        this.config = config;
        this.listener = listener;
        this.backendDirectory = listener.backendDirectory();
        this.switcher = listener.backendSwitcher();
        this.healthMonitor = new BackendHealthMonitor(backendDirectory.backends());
    }

    @Override
    public Map<String, Object> state() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", "OniLink");
        state.put("version", version());
        state.put("timestamp", Instant.now().toString());
        state.put("startedAt", Instant.ofEpochMilli(startedAtMillis).toString());
        state.put("uptimeMillis", System.currentTimeMillis() - startedAtMillis);
        state.put("players", listener.connectedPlayers().size());
        state.put("maxPlayers", config.maxPlayers());
        state.put("backends", config.backends().size());
        state.put("allowlistEnabled", listener.allowlist().enabled());
        state.put("allowlistEntries", listener.allowlist().entries().size());
        state.put("listener", address(config.listenAddress()));
        state.put("memoryUsedBytes", usedMemory);
        state.put("memoryCommittedBytes", runtime.totalMemory());
        state.put("memoryMaxBytes", runtime.maxMemory());
        state.put("processors", runtime.availableProcessors());
        state.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        state.put("systemLoadAverage", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        return state;
    }

    @Override
    public List<Map<String, Object>> players(boolean includeAddresses) {
        List<Map<String, Object>> players = new ArrayList<>();
        for (ProxyConnection connection : listener.connectedPlayers().connections()) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("name", connection.clientLogin().authData().displayName());
            player.put("xuid", connection.clientLogin().authData().xuid());
            player.put("identity", connection.clientLogin().authData().identity().toString());
            player.put("backend", connection.backendName() == null ? "connecting" : connection.backendName());
            player.put("switching", connection.isSwitchingBackend());
            player.put("switchTarget", connection.backendSwitchTarget() == null ? "" : connection.backendSwitchTarget());
            player.put("connectedMillis", connection.elapsedMillis());
            player.put("joinedWorld", connection.hasClientJoinedWorld());
            player.put("protocol", connection.sessionProfile() == null || connection.sessionProfile().clientCodec() == null
                    ? "negotiating"
                    : connection.sessionProfile().clientCodec().getMinecraftVersion());
            player.put("address", includeAddresses ? String.valueOf(connection.clientAddress()) : "hidden");
            player.put("packetTraceActive", connection.isPacketTraceActive());
            players.add(player);
        }
        players.sort(java.util.Comparator.comparing((Map<String, Object> map) -> String.valueOf(map.get("name")),
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(players);
    }

    @Override
    public List<Map<String, Object>> backends(boolean includeAddresses) {
        Map<String, Integer> playerCounts = new java.util.HashMap<>();
        for (ProxyConnection connection : listener.connectedPlayers().connections()) {
            if (connection.backendName() != null) playerCounts.merge(connection.backendName(), 1, Integer::sum);
        }
        List<Map<String, Object>> backends = new ArrayList<>();
        for (BackendConfig backend : backendDirectory.backends()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", backend.name());
            item.put("host", includeAddresses ? backend.address().getHostString() : "hidden");
            item.put("port", includeAddresses ? backend.address().getPort() : 0);
            item.put("protocol", backend.protocol() == null ? "auto" : backend.protocol().minecraftVersion());
            item.put("players", playerCounts.getOrDefault(backend.name(), 0));
            item.put("default", backend.name().equalsIgnoreCase(config.backend().name()));
            item.put("hub", backend.name().equalsIgnoreCase(config.hubBackendName()));
            item.put("forwarding", backend.forwarding().enabled());
            item.put("health", healthMonitor.health(backend.name()).asMap());
            backends.add(item);
        }
        return List.copyOf(backends);
    }

    @Override
    public Map<String, Object> packetMonitor(Map<String, String> filters) {
        return listener.packetMonitor().snapshot(filters);
    }

    @Override
    public Map<String, Object> allowlist() {
        ProxyAllowlist allowlist = listener.allowlist();
        List<Map<String, Object>> entries = allowlist.entries().stream()
                .map(entry -> Map.<String, Object>of("xuid", entry.xuid(), "name", entry.name()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", allowlist.enabled());
        result.put("count", entries.size());
        result.put("file", allowlist.config().file().toString());
        result.put("disconnectOnRemoval", allowlist.config().disconnectOnRemoval());
        result.put("entries", entries);
        return result;
    }

    @Override
    public ActionResult allowlistAdd(String xuid, String name) {
        try {
            boolean changed = listener.allowlist().add(xuid, name);
            return new ActionResult(true, changed
                    ? "Allow-listed XUID " + xuid.trim()
                    : "XUID " + xuid.trim() + " is already allow-listed with that label");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save the allowlist: " + exception.getMessage(), exception);
        }
    }

    @Override
    public ActionResult allowlistRemove(String xuid) {
        try {
            if (!listener.allowlist().remove(xuid)) {
                return new ActionResult(false, "XUID is not allow-listed");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save the allowlist: " + exception.getMessage(), exception);
        }
        if (listener.allowlist().enabled() && listener.allowlist().config().disconnectOnRemoval()) {
            listener.connectedPlayers().connections().stream()
                    .filter(connection -> xuid.trim().equals(connection.clientLogin().authData().xuid()))
                    .findFirst()
                    .ifPresent(connection -> connection.client().disconnect(
                            listener.allowlist().config().kickMessage()));
        }
        return new ActionResult(true, "Removed XUID " + xuid.trim() + " from the allowlist");
    }

    @Override
    public ActionResult transfer(String player, String backendName) {
        Optional<ProxyConnection> connection = listener.connectedPlayers().findByName(player);
        if (connection.isEmpty()) return new ActionResult(false, "Player is not online");
        BackendConfig backend = backendDirectory.find(backendName).orElse(null);
        if (backend == null) return new ActionResult(false, "Unknown backend: " + backendName);
        if (!connection.get().hasClientJoinedWorld()) {
            return new ActionResult(false, "Player is still connecting");
        }
        boolean started = switcher.switchBackend(connection.get(), backend);
        return new ActionResult(started, started
                ? "Transfer to " + backend.name() + " started"
                : "Transfer could not be started");
    }

    @Override
    public ActionResult disconnect(String player, String reason) {
        Optional<ProxyConnection> connection = listener.connectedPlayers().findByName(player);
        if (connection.isEmpty()) return new ActionResult(false, "Player is not online");
        String message = reason == null || reason.isBlank() ? "Disconnected by an OniLink operator" : reason.trim();
        connection.get().client().disconnect(message);
        return new ActionResult(true, "Disconnected " + connection.get().clientLogin().authData().displayName());
    }

    @Override
    public ActionResult alert(String message) {
        if (message == null || message.isBlank()) return new ActionResult(false, "Alert message is required");
        int delivered = 0;
        for (ProxyConnection connection : listener.connectedPlayers().connections()) {
            if (connection.client().isConnected() && connection.hasClientJoinedWorld()) {
                BackendSwitcher.sendMessage(connection, "[Alert] " + message.trim());
                delivered++;
            }
        }
        return new ActionResult(true, "Alert delivered to " + delivered + " player(s)");
    }

    @Override
    public ActionResult trace(String player, long milliseconds) {
        Optional<ProxyConnection> connection = listener.connectedPlayers().findByName(player);
        if (connection.isEmpty()) return new ActionResult(false, "Player is not online");
        long bounded = Math.max(1_000, Math.min(milliseconds, 60_000));
        connection.get().tracePacketsForMillis(bounded);
        return new ActionResult(true, "Packet trace enabled for " + bounded + "ms");
    }

    @Override
    public void shutdown() {
        listener.stop();
    }

    @Override
    public void close() {
        healthMonitor.close();
    }

    private static Map<String, Object> address(InetSocketAddress address) {
        return Map.of("host", address.getHostString(), "port", address.getPort());
    }

    private static String version() {
        String version = OniLink.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }
}
