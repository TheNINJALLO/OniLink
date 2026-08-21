package dev.onistone.onilink.control;

import java.util.Map;

public record ActionCapability(
        ActionType action,
        int payloadVersion,
        CapabilityStatus status,
        String reason,
        Map<String, Object> limits,
        boolean tested,
        boolean production
) {
    public enum CapabilityStatus {
        SUPPORTED,
        SUPPORTED_WITH_LIMITS,
        CANDIDATE,
        UNSUPPORTED,
        DISABLED
    }

    public ActionCapability {
        if (action == null || status == null || payloadVersion < 1) {
            throw new IllegalArgumentException("capability action, status, and payload version are required");
        }
        reason = reason == null ? "" : reason;
        limits = ControlValues.immutableObject(limits);
        if ((status == CapabilityStatus.UNSUPPORTED || status == CapabilityStatus.DISABLED) && reason.isBlank()) {
            throw new IllegalArgumentException("unsupported or disabled capability requires a reason");
        }
        if (production && !tested) throw new IllegalArgumentException("untested capability cannot be production");
    }
}
