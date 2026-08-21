package dev.onistone.onilink.control;

import java.time.Instant;
import java.util.UUID;

public record ActionAuditRecord(
        UUID requestId,
        String actor,
        ControlRole role,
        String tenantId,
        String proxyId,
        String targetXuid,
        String displayLabel,
        String backend,
        ActionType action,
        ExecutionPlane executionPlane,
        ActionStatus status,
        Instant timestamp,
        long durationMillis,
        String payloadSummary,
        String resultSummary,
        String failureReason,
        boolean confirmed
) {
    public ActionAuditRecord {
        if (requestId == null || role == null || action == null || executionPlane == null || status == null
                || timestamp == null || durationMillis < 0) {
            throw new IllegalArgumentException("audit identity, type, state, time, and duration are required");
        }
        actor = clean(actor);
        tenantId = clean(tenantId);
        proxyId = clean(proxyId);
        targetXuid = clean(targetXuid);
        displayLabel = clean(displayLabel);
        backend = clean(backend);
        payloadSummary = clean(payloadSummary);
        resultSummary = clean(resultSummary);
        failureReason = clean(failureReason);
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
