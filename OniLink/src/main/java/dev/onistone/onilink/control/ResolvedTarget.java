package dev.onistone.onilink.control;

/** Immutable identity snapshot frozen immediately before validation/execution. */
public record ResolvedTarget(
        String xuid,
        String connectionId,
        String displayName,
        String tenantId,
        String proxyId,
        String backend,
        int clientProtocol,
        int backendProtocol,
        boolean joinedWorld,
        boolean transferInProgress
) {
    public ResolvedTarget {
        xuid = required(xuid, "target XUID");
        connectionId = required(connectionId, "target connection ID");
        displayName = displayName == null ? "" : displayName;
        tenantId = tenantId == null ? "" : tenantId;
        proxyId = proxyId == null ? "" : proxyId;
        backend = backend == null ? "" : backend;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }
}
