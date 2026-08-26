package org.cloudburstmc.protocol.bedrock.codec.v2168;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.MemoryCategoryCounter;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDiagnosticsPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Bedrock_v2168Hotfix4Test {
    @Test
    void hotfixAddsTheRemoveScoreDiscriminatorWithoutChangingProtocolNumber() {
        SetScorePacket packet = new SetScorePacket();
        packet.getInfos().add(new ScoreInfo(7L, "objective", 0));

        assertEquals(2168, Bedrock_v2168_hotfix4.CODEC.getProtocolVersion());
        assertEquals("1.26.44", Bedrock_v2168_hotfix4.CODEC.getMinecraftVersion());
        assertEquals(encodedLength(Bedrock_v2168.CODEC, packet) + 1,
                encodedLength(Bedrock_v2168_hotfix4.CODEC, packet));
    }

    @Test
    void diagnosticsUsesThe2168MemoryCategoryNumbers() {
        ServerboundDiagnosticsPacket packet = new ServerboundDiagnosticsPacket();
        packet.getMemoryCategoryValues().add(new MemoryCategoryCounter(
                MemoryCategoryCounter.Category.BLOBS, 32L));

        ByteBuf encoded = encode(Bedrock_v2168.CODEC, packet);
        try {
            // Java retains the historical BALANCER enum at ordinal 5; BDS 2168 does not.
            assertEquals(7, encoded.getUnsignedByte(37));
        } finally {
            encoded.release();
        }
    }

    private static int encodedLength(BedrockCodec codec, BedrockPacket packet) {
        ByteBuf buffer = encode(codec, packet);
        try {
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    private static ByteBuf encode(BedrockCodec codec, BedrockPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        codec.tryEncode(codec.createHelper(), buffer, packet);
        return buffer;
    }
}
