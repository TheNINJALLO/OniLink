package dev.onistone.onilink.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ControlActionResult(
        UUID requestId,
        ActionStatus status,
        String reason,
        Map<String, Object> result,
        Instant startedAt,
        Instant completedAt,
        String auditReference
) {
    public ControlActionResult {
        if (requestId == null || status == null || startedAt == null || completedAt == null) {
            throw new IllegalArgumentException("action result identity, status, and timestamps are required");
        }
        reason = reason == null ? "" : reason;
        result = ControlValues.immutableObject(result);
        auditReference = auditReference == null ? "" : auditReference;
    }

    public long durationMillis() {
        return Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }

    public boolean successful() {
        return status == ActionStatus.CONFIRMED;
    }
}
