package dev.onistone.onilink.platform.modules;

import java.time.Instant;

/** Immutable health reported by an independently isolated OniLink module. */
public record ModuleHealth(State state, String message, Instant checkedAt) {
    public enum State { DISABLED, STARTING, HEALTHY, DEGRADED, FAILED, STOPPED }

    public ModuleHealth {
        if (state == null) throw new IllegalArgumentException("state is required");
        message = message == null ? "" : message;
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
    }

    public static ModuleHealth of(State state, String message) {
        return new ModuleHealth(state, message, Instant.now());
    }
}
