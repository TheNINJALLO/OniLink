package dev.onistone.onilink.platform.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OniEvent(
        OniEventType type,
        String tenantId,
        String proxyId,
        String correlationId,
        Instant occurredAt,
        Map<String, Object> data
) {
    public OniEvent {
        if (type == null) throw new IllegalArgumentException("event type is required");
        tenantId = scope(tenantId);
        proxyId = scope(proxyId);
        correlationId = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString() : correlationId;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        data = Map.copyOf(data == null ? Map.of() : data);
    }

    public static OniEvent of(OniEventType type, String tenantId, String proxyId, Map<String, Object> data) {
        return new OniEvent(type, tenantId, proxyId, null, null, data);
    }

    private static String scope(String value) {
        return value == null || value.isBlank() ? "default" : value.trim().toLowerCase();
    }
}
