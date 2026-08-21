package dev.onistone.onilink.modules.pulse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-connection monotonic stage timeline; never contains tokens, addresses, or packet bodies. */
public final class JourneyTrace {
    public enum Stage {
        CONNECTION_RECEIVED, RAKNET_ESTABLISHED, AUTH_STARTED, AUTH_COMPLETED, PROTOCOL_NEGOTIATED,
        INITIAL_BACKEND_SELECTED, BACKEND_CONNECT_STARTED, BACKEND_CONNECTED, ONIFORWARD_ACCEPTED,
        RESOURCE_PACK_NEGOTIATION_STARTED, RESOURCE_PACK_NEGOTIATION_COMPLETED, START_GAME_RECEIVED,
        WORLD_JOIN_COMPLETED, TRANSFER_STARTED, TRANSFER_BACKEND_CONNECTED, TRANSFER_COMPLETED, DISCONNECTED
    }

    private final String journeyId = UUID.randomUUID().toString();
    private final String connectionId;
    private final Map<Stage, Instant> stages = new LinkedHashMap<>();
    private final List<Map<String, Object>> transfers = new ArrayList<>();
    private String tenantId = "provider";
    private String proxyId = "main";
    private String xuid = "";
    private String displayLabel = "";
    private String clientProtocol = "negotiating";
    private String backendProtocol = "unknown";
    private String backend = "";
    private String failure = "";

    public JourneyTrace(String connectionId) {
        this.connectionId = connectionId;
        mark(Stage.CONNECTION_RECEIVED);
        mark(Stage.RAKNET_ESTABLISHED);
    }

    public synchronized void scope(String tenantId, String proxyId) {
        this.tenantId = safeScope(tenantId, "provider");
        this.proxyId = safeScope(proxyId, "main");
    }

    public synchronized void identity(String xuid, String displayLabel) {
        this.xuid = xuid == null ? "" : xuid;
        this.displayLabel = displayLabel == null ? "" : displayLabel;
    }

    public synchronized void protocols(String clientProtocol, String backendProtocol) {
        this.clientProtocol = clientProtocol == null ? "unknown" : clientProtocol;
        this.backendProtocol = backendProtocol == null ? "unknown" : backendProtocol;
    }

    public synchronized void backend(String backend) {
        this.backend = backend == null ? "" : backend;
    }

    public synchronized void mark(Stage stage) {
        stages.putIfAbsent(stage, Instant.now());
    }

    public synchronized void transfer(String from, String to, String state) {
        if (transfers.size() >= 64) transfers.removeFirst();
        transfers.add(Map.of("from", from == null ? "" : from, "to", to == null ? "" : to,
                "state", state, "timestamp", Instant.now().toString()));
    }

    public synchronized void failure(String reason) {
        failure = reason == null ? "unknown" : reason.substring(0, Math.min(reason.length(), 240));
    }

    public synchronized Map<String, Object> snapshot(boolean revealIdentity) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        Instant previous = null;
        for (Map.Entry<Stage, Instant> entry : stages.entrySet()) {
            long duration = previous == null ? 0 : Math.max(0, Duration.between(previous, entry.getValue()).toMillis());
            timeline.add(Map.of("stage", entry.getKey().name(), "timestamp", entry.getValue().toString(),
                    "durationFromPreviousMillis", duration));
            previous = entry.getValue();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", journeyId);
        result.put("connectionId", connectionId);
        result.put("tenantId", tenantId);
        result.put("proxyId", proxyId);
        result.put("player", displayLabel);
        result.put("xuid", revealIdentity ? xuid : "hidden");
        result.put("clientProtocol", clientProtocol);
        result.put("backendProtocol", backendProtocol);
        result.put("backend", backend);
        result.put("timeline", List.copyOf(timeline));
        result.put("transfers", List.copyOf(transfers));
        result.put("failure", failure);
        result.put("completed", stages.containsKey(Stage.DISCONNECTED));
        return Map.copyOf(result);
    }

    public synchronized String tenantId() { return tenantId; }
    public synchronized String proxyId() { return proxyId; }
    public String journeyId() { return journeyId; }

    private static String safeScope(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
    }
}
