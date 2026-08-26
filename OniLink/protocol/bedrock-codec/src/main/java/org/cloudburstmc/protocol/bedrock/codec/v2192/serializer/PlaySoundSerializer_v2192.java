package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.PlaySoundSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

/** Protocol 2192's listener-range flag and optional playback offset. */
public final class PlaySoundSerializer_v2192 extends PlaySoundSerializer_v2168 {
    public static final PlaySoundSerializer_v2192 INSTANCE = new PlaySoundSerializer_v2192();

    private PlaySoundSerializer_v2192() {
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, PlaySoundPacket packet) {
        helper.writeString(buffer, packet.getSound());
        helper.writeBlockPosition(buffer, packet.getPosition().mul(8).toInt());
        buffer.writeFloatLE(packet.getVolume());
        buffer.writeFloatLE(packet.getPitch());
        VarInts.writeUnsignedInt(buffer, packet.getLoopCount());
        buffer.writeBoolean(packet.isBypassListenerRangeCheck());
        helper.writeOptionalNull(buffer, packet.getServerSoundHandle(), ByteBuf::writeLongLE);
        helper.writeOptionalNull(buffer, packet.getPlaybackPositionSeconds(), ByteBuf::writeFloatLE);
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, PlaySoundPacket packet) {
        packet.setSound(helper.readString(buffer));
        packet.setPosition(helper.readBlockPosition(buffer).toFloat().div(8));
        packet.setVolume(buffer.readFloatLE());
        packet.setPitch(buffer.readFloatLE());
        packet.setLoopCount(VarInts.readUnsignedInt(buffer));
        packet.setBypassListenerRangeCheck(buffer.readBoolean());
        packet.setServerSoundHandle(helper.readOptional(buffer, null, ByteBuf::readLongLE));
        packet.setPlaybackPositionSeconds(helper.readOptional(buffer, null, ByteBuf::readFloatLE));
    }
}
