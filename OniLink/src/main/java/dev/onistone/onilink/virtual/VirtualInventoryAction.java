package dev.onistone.onilink.virtual;

import java.time.Instant;

public record VirtualInventoryAction(String sessionId, int slot, int requestId, Instant timestamp) {
    public VirtualInventoryAction {
        if (sessionId == null || sessionId.isBlank() || slot < 0 || requestId <= 0) {
            throw new IllegalArgumentException("virtual inventory interaction identity is invalid");
        }
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
