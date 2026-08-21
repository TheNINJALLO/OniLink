package dev.onistone.onilink.control;

public record ControlActionStep(String stepId, ControlActionRequest request, ControlActionRequest compensation) {
    public ControlActionStep {
        if (stepId == null || stepId.isBlank() || request == null) {
            throw new IllegalArgumentException("plan step ID and request are required");
        }
    }
}
