package dev.onistone.onilink.control.wire;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record ControlBridgeStatus(
        boolean enabled,
        boolean connected,
        boolean tls,
        String backend,
        String bridgeId,
        long capabilityRevision,
        long latencyMillis,
        int queueSize,
        int supportedActionCount,
        String lastError,
        Instant updatedAt
) {
    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("connected", connected);
        values.put("tls", tls);
        values.put("backend", backend);
        values.put("bridgeId", bridgeId);
        values.put("capabilityRevision", capabilityRevision);
        values.put("latencyMillis", latencyMillis);
        values.put("queueSize", queueSize);
        values.put("supportedActionCount", supportedActionCount);
        values.put("lastError", lastError == null ? "" : lastError);
        values.put("updatedAt", updatedAt == null ? "" : updatedAt.toString());
        return values;
    }
}
