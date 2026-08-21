package dev.onistone.onilink.control;

import java.time.Instant;
import java.util.List;

public record ControlActionPlan(
        String planId,
        List<ControlActionStep> steps,
        FailurePolicy failurePolicy,
        String reason,
        List<String> evidenceReferences,
        String expectedResult,
        double confidence,
        ControlRole requiredRole,
        boolean confirmationRequired,
        long revision,
        Instant createdAt
) {
    public ControlActionPlan {
        if (planId == null || planId.isBlank() || steps == null || steps.isEmpty() || failurePolicy == null
                || requiredRole == null) {
            throw new IllegalArgumentException("plan identity, steps, policy, and role are required");
        }
        steps = List.copyOf(steps);
        evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        reason = reason == null ? "" : reason;
        expectedResult = expectedResult == null ? "" : expectedResult;
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("plan confidence must be 0..1");
        }
        if (revision < 1) throw new IllegalArgumentException("plan revision must be positive");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
