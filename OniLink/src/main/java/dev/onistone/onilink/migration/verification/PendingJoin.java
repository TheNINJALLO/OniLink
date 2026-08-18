package dev.onistone.onilink.migration.verification;

import dev.onistone.onilink.auth.AuthData;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public record PendingJoin(
        String xuid,
        String uuid,
        String name,
        String backendName,
        String clientIp,
        int clientPort,
        long expiresAtMillis
) {
    public static PendingJoin from(
            AuthData authData,
            String backendName,
            SocketAddress clientAddress,
            long expiresAtMillis
    ) {
        String clientIp = "";
        int clientPort = 0;
        if (clientAddress instanceof InetSocketAddress inetAddress) {
            clientIp = inetAddress.getAddress() != null
                    ? inetAddress.getAddress().getHostAddress()
                    : inetAddress.getHostString();
            clientPort = Math.max(0, inetAddress.getPort());
        }
        return new PendingJoin(
                authData.xuid(),
                authData.identity().toString(),
                authData.displayName(),
                backendName,
                clientIp,
                clientPort,
                expiresAtMillis
        );
    }
}
