package dev.onistone.onilink.config;

import java.util.Locale;

/** Policy for handling a backend kick. */
public enum BackendKickAction {

    /** Fails over host-level disconnects and preserves player-specific kicks. */
    AUTO,
    /** Passes every kick through to the player. */
    DISCONNECT,
    /** Sends every kicked player to a fallback backend. */
    FAILOVER;

    /** Returns whether this kick should start failover. */
    public boolean failsOver(boolean backendSuppliedMessage) {
        return switch (this) {
            case AUTO -> !backendSuppliedMessage;
            case DISCONNECT -> false;
            case FAILOVER -> true;
        };
    }

    /** Uses {@link #AUTO} for blank or unrecognized values. */
    public static BackendKickAction parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (BackendKickAction candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        System.err.printf(
                "Unknown failover.onBackendKick '%s'; using auto. Valid values: auto, disconnect, failover.%n",
                value);
        return AUTO;
    }
}
