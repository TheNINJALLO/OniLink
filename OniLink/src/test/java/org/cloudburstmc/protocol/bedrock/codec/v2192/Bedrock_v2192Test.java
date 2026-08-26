package org.cloudburstmc.protocol.bedrock.codec.v2192;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.bedrock.data.DisconnectFailReason;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.MemoryCategoryCounter;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDiagnosticsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundPackSettingChangePacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Bedrock_v2192Test {
    @Test
    void identifiesThe1_26_50PreviewProtocol() {
        assertEquals(2192, Bedrock_v2192.CODEC.getProtocolVersion());
        assertEquals("1.26.50", Bedrock_v2192.CODEC.getMinecraftVersion());
        assertEquals(148, DisconnectFailReason.MISSING_STRUCTURE_DATA.ordinal());
        assertEquals(149, DisconnectFailReason.UNSUPPORTED_TRANSPORT.ordinal());
    }

    @Test
    void bossEventDropsTheLegacyPlayerId() {
        BossEventPacket packet = new BossEventPacket();
        packet.setBossUniqueEntityId(42L);
        packet.setPlayerUniqueEntityId(99L);
        packet.setAction(BossEventPacket.Action.CREATE);
        packet.setTitle("Guardian");
        packet.setFilteredTitle("");
        packet.setHealthPercentage(0.75f);
        packet.setColor(2);
        packet.setOverlay(1);

        BossEventPacket decoded = roundTrip(packet, BossEventPacket.class);

        assertEquals(42L, decoded.getBossUniqueEntityId());
        assertEquals(0L, decoded.getPlayerUniqueEntityId());
        // Signed VarLong(99) occupies two bytes; 2192 removes that complete field.
        assertEquals(encodedLength(Bedrock_v2168.CODEC, packet) - 2,
                encodedLength(Bedrock_v2192.CODEC, packet));
    }

    @Test
    void movementCarriesTheNewServerTick() {
        MoveEntityDeltaPacket packet = new MoveEntityDeltaPacket();
        packet.setRuntimeEntityId(7L);
        packet.setTick(123456L);

        assertEquals(123456L, roundTrip(packet, MoveEntityDeltaPacket.class).getTick());
    }

    @Test
    void playSoundCarriesTheRangeFlagAndPlaybackOffset() {
        PlaySoundPacket packet = new PlaySoundPacket();
        packet.setSound("note.harp");
        packet.setPosition(Vector3f.from(1, 2, 3));
        packet.setVolume(0.5f);
        packet.setPitch(1.25f);
        packet.setLoopCount(2);
        packet.setBypassListenerRangeCheck(true);
        packet.setServerSoundHandle(19L);
        packet.setPlaybackPositionSeconds(4.5f);

        PlaySoundPacket decoded = roundTrip(packet, PlaySoundPacket.class);

        assertEquals(true, decoded.isBypassListenerRangeCheck());
        assertEquals(19L, decoded.getServerSoundHandle());
        assertEquals(4.5f, decoded.getPlaybackPositionSeconds(), 0.0001f);
    }

    @Test
    void dimensionCarriesMinimumRangeAndDefaultBiome() {
        UUID packId = UUID.fromString("62e2a9f4-c913-4dd7-8c0f-fd55e0b7bb37");
        DimensionDataPacket packet = new DimensionDataPacket();
        packet.getDefinitions().add(new DimensionDefinition(
                "example:sky", 384, -64, 1, 2, packId, "minecraft:plains"));

        DimensionDefinition decoded = roundTrip(packet, DimensionDataPacket.class).getDefinitions().get(0);

        assertEquals(-64, decoded.getMinimumHeight());
        assertEquals(384, decoded.getMaximumHeight());
        assertEquals("minecraft:plains", decoded.getDefaultBiome());
    }

    @Test
    void packSettingsAcceptTheNewStringListArm() {
        ServerboundPackSettingChangePacket packet = new ServerboundPackSettingChangePacket();
        packet.setPackId(UUID.fromString("b75a0b1e-7208-49d5-870a-d215727b43df"));
        packet.setPackSettingName("enabled_worlds");
        packet.setPackSettingValue(List.of("lobby", "survival"));

        ServerboundPackSettingChangePacket decoded = roundTrip(
                packet, ServerboundPackSettingChangePacket.class);

        assertEquals(List.of("lobby", "survival"), decoded.getPackSettingValue());
    }

    @Test
    void memoryCategoryRenumberingMatchesThe2192WireEnum() {
        ServerboundDiagnosticsPacket packet = new ServerboundDiagnosticsPacket();
        packet.getMemoryCategoryValues().add(new MemoryCategoryCounter(
                MemoryCategoryCounter.Category.PERSONA_CHARACTERS, 64L));

        ByteBuf encoded = encode(Bedrock_v2192.CODEC, packet);
        try {
            // Nine floats (36 bytes), then the one-byte list length, then the category.
            assertEquals(58, encoded.getUnsignedByte(37));
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

    @SuppressWarnings("unchecked")
    private static <T extends BedrockPacket> T roundTrip(T packet, Class<T> type) {
        BedrockCodecHelper helper = Bedrock_v2192.CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            Bedrock_v2192.CODEC.tryEncode(helper, buffer, packet);
            return (T) Bedrock_v2192.CODEC.tryDecode(
                    Bedrock_v2192.CODEC.createHelper(),
                    buffer,
                    Bedrock_v2192.CODEC.getPacketDefinition(type).getId());
        } finally {
            buffer.release();
        }
    }
}
