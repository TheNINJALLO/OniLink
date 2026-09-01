package org.cloudburstmc.protocol.bedrock.codec.v2169;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Bedrock_v2169Test {
    @Test
    void releaseAdvancesProtocolAndRestoresTheOriginalSetScoreLayout() {
        SetScorePacket packet = new SetScorePacket();
        packet.getInfos().add(new ScoreInfo(7L, "objective", 0));

        assertEquals(2169, Bedrock_v2169.CODEC.getProtocolVersion());
        assertEquals("1.26.45", Bedrock_v2169.CODEC.getMinecraftVersion());
        assertEquals(encodedLength(Bedrock_v2168.CODEC, packet),
                encodedLength(Bedrock_v2169.CODEC, packet));
        assertEquals(encodedLength(Bedrock_v2168_hotfix4.CODEC, packet) - 1,
                encodedLength(Bedrock_v2169.CODEC, packet));
    }

    private static int encodedLength(BedrockCodec codec, BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(codec.createHelper(), buffer, packet);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }
}
