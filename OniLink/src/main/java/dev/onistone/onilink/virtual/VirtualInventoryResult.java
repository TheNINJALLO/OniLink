package dev.onistone.onilink.virtual;

import java.util.Map;

public record VirtualInventoryResult(Status status, String reason, Map<String, Object> details) {
    public enum Status { CONFIRMED, REJECTED, UNSUPPORTED, CLOSED }
    public VirtualInventoryResult {
        if (status == null) throw new IllegalArgumentException("virtual inventory result status is required");
        reason = reason == null ? "" : reason;
        details = Map.copyOf(details == null ? Map.of() : details);
    }
}
