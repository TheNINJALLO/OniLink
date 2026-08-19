package dev.onistone.onilink.plugin;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;

import java.net.InetSocketAddress;

/**
 * Describes a loopback listener used by a local translator addon.
 *
 * @param address loopback bind address
 * @param advertisedCodec codec advertised by the listener
 * @param loginSecret secret required for self-signed logins; blank disables the check
 * @param namePrefix display-name prefix applied after identity is established
 */
public record TrustedListenerSpec(
        InetSocketAddress address,
        BedrockCodec advertisedCodec,
        String loginSecret,
        String namePrefix
) {
    public TrustedListenerSpec {
        if (address == null) {
            throw new IllegalArgumentException("address cannot be null");
        }
        if (address.getAddress() == null || !address.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException(
                    "a trusted listener must bind a loopback address, not " + address
                            + ". It accepts self-signed logins, so exposing it to the network would let "
                            + "anyone join as anyone.");
        }
        if (advertisedCodec == null) {
            throw new IllegalArgumentException("advertisedCodec cannot be null");
        }
        loginSecret = loginSecret == null ? "" : loginSecret;
        namePrefix = namePrefix == null ? "" : namePrefix;
    }
}
