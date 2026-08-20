package dev.onistone.onilink.config;

import dev.onistone.onilink.protocol.CanonicalProtocol;

import java.net.InetSocketAddress;

/**
 * @param protocol the Minecraft version this backend runs, or null to fall back to the global
 *                 {@code backend.protocol}. Set it per backend with
 *                 {@code backend.<name>.protocol=1.26.40} while upgrading a mixed-version fleet.
 *                 Without it the proxy speaks the global version to every backend, and an upgraded
 *                 backend can reject the login as {@code LOGIN_FAILED_CLIENT_OLD}.
 */
public record BackendConfig(
        String name,
        InetSocketAddress address,
        CanonicalProtocol protocol,
        BackendForwardingConfig forwarding
) {
    public BackendConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (address == null) {
            throw new IllegalArgumentException("address cannot be null");
        }
        if (forwarding == null) {
            throw new IllegalArgumentException("forwarding cannot be null");
        }
    }

    public BackendConfig(String name, InetSocketAddress address, CanonicalProtocol protocol) {
        this(name, address, protocol, BackendForwardingConfig.disabled());
    }

    public BackendConfig(String name, InetSocketAddress address) {
        this(name, address, null, BackendForwardingConfig.disabled());
    }
}
