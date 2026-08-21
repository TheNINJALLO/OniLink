package dev.onistone.onilink.control;

/** Unresolved selector accepted at the typed API boundary. */
public record TargetSelector(
        String xuid,
        String connectionId,
        String tenantId,
        String proxyId,
        String backend,
        String displayName
) {
    public TargetSelector {
        xuid = clean(xuid);
        connectionId = clean(connectionId);
        tenantId = clean(tenantId);
        proxyId = clean(proxyId);
        backend = clean(backend);
        displayName = clean(displayName);
        if (xuid.isBlank() && connectionId.isBlank() && displayName.isBlank()) {
            throw new IllegalArgumentException("target requires XUID, connection ID, or display name");
        }
    }

    public static TargetSelector xuid(String xuid) {
        return new TargetSelector(xuid, "", "", "", "", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
