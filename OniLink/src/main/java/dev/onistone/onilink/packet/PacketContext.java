package dev.onistone.onilink.packet;

import dev.onistone.onilink.control.PacketOrigin;

/** Immutable identity and world context captured before rule evaluation. */
public record PacketContext(
        String authenticatedPlayerName,
        String xuid,
        String connectionId,
        String tenantId,
        String proxyId,
        String backendName,
        int clientProtocol,
        int backendProtocol,
        PacketRuleDirection direction,
        PacketOrigin origin,
        String sessionPhase,
        int dimension,
        long playerRuntimeEntityId,
        long backendRuntimeEntityId,
        boolean joinedWorld,
        boolean transferring
) {
    public PacketContext {
        authenticatedPlayerName = clean(authenticatedPlayerName);
        xuid = clean(xuid);
        connectionId = clean(connectionId);
        tenantId = clean(tenantId);
        proxyId = clean(proxyId);
        backendName = clean(backendName);
        sessionPhase = clean(sessionPhase);
        if (direction == null || origin == null) throw new IllegalArgumentException("packet direction and origin are required");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
