package dev.onistone.onilink.control.wire;

import dev.onistone.onilink.config.OniControlConfig;
import dev.onistone.onilink.config.OniControlConfig.ControlBackendConfig;
import dev.onistone.onilink.control.ActionType;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ControlClientManager implements AutoCloseable {
    private final OniControlConfig config;
    private final Map<String, ControlBridgeClient> clients = new LinkedHashMap<>();

    public ControlClientManager(OniControlConfig config) {
        this.config = config;
    }

    public synchronized void start() throws IOException {
        if (!clients.isEmpty()) return;
        try {
            for (Map.Entry<String, ControlBackendConfig> entry : config.backends().entrySet()) {
                if (entry.getValue().enabled()) clients.put(entry.getKey(), new ControlBridgeClient(entry.getValue()));
            }
        } catch (IOException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public CompletableFuture<ControlResponseEnvelope> request(
            String backend,
            ActionType action,
            String targetXuid,
            Map<String, Object> payload,
            String idempotencyKey,
            Instant deadline
    ) {
        ControlBridgeClient client;
        synchronized (this) {
            client = clients.get(key(backend));
        }
        if (client == null) return CompletableFuture.failedFuture(
                new UnsupportedOperationException("OniControl is disabled for backend " + backend));
        return client.request(action, targetXuid, payload, idempotencyKey, deadline);
    }

    public synchronized BridgeCapabilityDocument capability(String backend) {
        ControlBridgeClient client = clients.get(key(backend));
        return client == null ? null : client.capabilities();
    }

    public synchronized List<Map<String, Object>> statuses() {
        return clients.values().stream().map(client -> client.status().asMap()).toList();
    }

    public synchronized List<Map<String, Object>> capabilities() {
        return clients.values().stream()
                .map(ControlBridgeClient::capabilities)
                .filter(java.util.Objects::nonNull)
                .map(BridgeCapabilityDocument::asMap)
                .toList();
    }

    @Override
    public synchronized void close() {
        for (ControlBridgeClient client : clients.values()) client.close();
        clients.clear();
    }

    private static String key(String backend) {
        return backend == null ? "" : backend.toLowerCase(Locale.ROOT);
    }
}
