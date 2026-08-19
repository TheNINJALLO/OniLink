package dev.onistone.onilink.diagnostics;

/** A backend protocol error that should be logged instead of routed through failover. */
public record ProtocolFault(String backendName, String playerName, String detail) {

    /** Creates a fault from a backend packet-violation report. */
    public static ProtocolFault fromViolation(String backendName, String playerName, PacketViolation violation) {
        return new ProtocolFault(backendName, playerName, violation.toString());
    }

    /** Returns a single-line summary for the protocol fault log. */
    public String describe() {
        return "backend=" + this.backendName + " player=" + this.playerName + " " + this.detail;
    }
}
