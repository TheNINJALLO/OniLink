package dev.onistone.onilink.network;

import dev.onistone.onilink.protocol.CanonicalProtocol;

import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import dev.onistone.onilink.protocol.ProtocolNegotiator;
import dev.onistone.onilink.protocol.ProtocolRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class NetworkSettingsNegotiatorTest {
    @Test
    void acceptedProtocolGetsClientCodecAndNetworkSettings() {
        NetworkSettingsNegotiator negotiator = new NetworkSettingsNegotiator(
                new ProtocolNegotiator(ProtocolRegistry.createDefault())
        );

        NetworkSettingsNegotiationResult.Accepted accepted = assertInstanceOf(
                NetworkSettingsNegotiationResult.Accepted.class,
                negotiator.handle(request(898))
        );

        assertEquals(898, accepted.clientCodec().getProtocolVersion());
        assertEquals(PacketCompressionAlgorithm.ZLIB, accepted.networkSettings().getCompressionAlgorithm());
        assertEquals(0, accepted.networkSettings().getCompressionThreshold());
    }

    @Test
    void unsupportedProtocolGetsPlayStatusRejection() {
        NetworkSettingsNegotiator negotiator = new NetworkSettingsNegotiator(
                new ProtocolNegotiator(ProtocolRegistry.createDefault())
        );

        NetworkSettingsNegotiationResult.Rejected rejected = assertInstanceOf(
                NetworkSettingsNegotiationResult.Rejected.class,
                negotiator.handle(request(CanonicalProtocol.newest().protocolVersion() + 1))
        );

        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD, rejected.playStatus().getStatus());
    }

    @Test
    void mixed2168And2192ClientsAreNegotiatedIndependentlyWithoutARuntimeSwitch() {
        NetworkSettingsNegotiator negotiator = new NetworkSettingsNegotiator(
                new ProtocolNegotiator(ProtocolRegistry.createDefault())
        );

        NetworkSettingsNegotiationResult.Accepted first2168 = accepted(negotiator, 2168);
        NetworkSettingsNegotiationResult.Accepted client2192 = accepted(negotiator, 2192);
        NetworkSettingsNegotiationResult.Accepted second2168 = accepted(negotiator, 2168);

        assertEquals(2168, first2168.clientCodec().getProtocolVersion());
        assertEquals(2192, client2192.clientCodec().getProtocolVersion());
        assertSame(first2168.clientCodec(), second2168.clientCodec());
        assertNotSame(first2168.clientCodec(), client2192.clientCodec());
    }

    private static NetworkSettingsNegotiationResult.Accepted accepted(
            NetworkSettingsNegotiator negotiator,
            int protocol
    ) {
        return assertInstanceOf(
                NetworkSettingsNegotiationResult.Accepted.class,
                negotiator.handle(request(protocol))
        );
    }

    private static RequestNetworkSettingsPacket request(int protocol) {
        RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
        packet.setProtocolVersion(protocol);
        return packet;
    }
}
