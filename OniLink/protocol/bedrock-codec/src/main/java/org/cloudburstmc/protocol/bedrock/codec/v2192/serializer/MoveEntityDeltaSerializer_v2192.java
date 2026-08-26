package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.MoveEntityDeltaSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

/** Protocol 2192 appends the server tick to delta movement. */
public final class MoveEntityDeltaSerializer_v2192 extends MoveEntityDeltaSerializer_v2168 {
    public static final MoveEntityDeltaSerializer_v2192 INSTANCE = new MoveEntityDeltaSerializer_v2192();

    private MoveEntityDeltaSerializer_v2192() {
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, MoveEntityDeltaPacket packet) {
        super.serialize(buffer, helper, packet);
        VarInts.writeUnsignedLong(buffer, packet.getTick());
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, MoveEntityDeltaPacket packet) {
        super.deserialize(buffer, helper, packet);
        packet.setTick(VarInts.readUnsignedLong(buffer));
    }
}
