package dev.onistone.onilink.control;

import java.time.Instant;
import java.util.UUID;

public record ControlActionRequest(
        UUID requestId,
        String idempotencyKey,
        TargetSelector target,
        ActionType actionType,
        ExecutionPlane executionPlane,
        ValidatedActionPayload payload,
        String actorAccountId,
        ControlRole actorRole,
        String tenantId,
        String proxyId,
        Instant createdAt,
        Instant deadline,
        boolean confirmationRequired,
        String reason,
        String correlationId,
        String planId
) {
    public ControlActionRequest {
        if (requestId == null) requestId = UUID.randomUUID();
        idempotencyKey = required(idempotencyKey, "idempotency key", 128);
        if (target == null || actionType == null || executionPlane == null || payload == null || actorRole == null) {
            throw new IllegalArgumentException("request target, action, plane, payload, and actor role are required");
        }
        if (executionPlane != actionType.executionPlane()) {
            throw new IllegalArgumentException("action execution plane does not match the action registry");
        }
        actorAccountId = required(actorAccountId, "actor account ID", 128);
        tenantId = clean(tenantId, 64);
        proxyId = clean(proxyId, 64);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        if (deadline == null || !deadline.isAfter(createdAt)) {
            throw new IllegalArgumentException("request deadline must follow creation time");
        }
        reason = clean(reason, 512);
        correlationId = clean(correlationId, 128);
        planId = clean(planId, 128);
    }

    private static String required(String value, String label, int maximum) {
        String clean = clean(value, maximum);
        if (clean.isBlank()) throw new IllegalArgumentException(label + " is required");
        return clean;
    }

    private static String clean(String value, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > maximum) throw new IllegalArgumentException("value exceeds " + maximum + " characters");
        return clean;
    }
}
