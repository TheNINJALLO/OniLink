package dev.onistone.onilink.virtual;

import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.protocol.PacketMonitor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.List;

final class VirtualPacketEncoding {
    private static final int MAX_BYTES = 256 * 1024;

    private VirtualPacketEncoding() {
    }

    static String validate(ProxyConnection connection, List<? extends BedrockPacket> packets) {
        BedrockCodec codec = connection.sessionProfile().clientCodec();
        BedrockCodecHelper helper = codec.createHelper();
        helper.setItemDefinitions(connection.client().getPeer().getCodecHelper().getItemDefinitions());
        helper.setBlockDefinitions(connection.client().getPeer().getCodecHelper().getBlockDefinitions());
        int total = 0;
        for (BedrockPacket packet : packets) {
            if (codec.getPacketDefinition(packet.getClass()) == null) {
                return packet.getClass().getSimpleName() + " is unavailable for client protocol "
                        + codec.getProtocolVersion();
            }
            ByteBuf buffer = Unpooled.buffer();
            try {
                codec.tryEncode(helper, buffer, packet);
                total = Math.addExact(total, buffer.readableBytes());
                if (total > MAX_BYTES) return "virtual packet output exceeds the encoded size limit";
            } catch (Exception exception) {
                return packet.getClass().getSimpleName() + " cannot encode for client protocol "
                        + codec.getProtocolVersion();
            } finally {
                buffer.release();
            }
        }
        return "";
    }

    static void send(ProxyConnection connection, List<? extends BedrockPacket> packets) {
        for (BedrockPacket packet : packets) {
            connection.client().sendPacket(packet);
            connection.observePacket(PacketMonitor.Direction.CLIENTBOUND, packet, packet,
                    PacketMonitor.Action.ONIVIRTUAL_INJECTED);
        }
    }
}
