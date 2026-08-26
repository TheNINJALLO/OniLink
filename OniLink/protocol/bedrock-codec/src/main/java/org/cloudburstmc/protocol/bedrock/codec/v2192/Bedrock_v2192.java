package org.cloudburstmc.protocol.bedrock.codec.v2192;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.SetScoreSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2192.serializer.*;
import org.cloudburstmc.protocol.bedrock.packet.*;

/** Minecraft Bedrock 1.26.50.26, network protocol 2192. */
public final class Bedrock_v2192 extends Bedrock_v2168 {
    public static final BedrockCodec CODEC = Bedrock_v2168.CODEC.toBuilder()
            .protocolVersion(2192)
            .minecraftVersion("1.26.50")
            .helper(() -> new BedrockCodecHelper_v2192(
                    ENTITY_DATA,
                    GAME_RULE_TYPES,
                    ITEM_STACK_REQUEST_TYPES,
                    CONTAINER_SLOT_TYPES,
                    PLAYER_ABILITIES,
                    TEXT_PROCESSING_ORIGINS
            ))
            .updateSerializer(BossEventPacket.class, BossEventSerializer_v2192.INSTANCE)
            .updateSerializer(CameraPresetsPacket.class, CameraPresetsSerializer_v2192.INSTANCE)
            .updateSerializer(ClientboundAttributeLayerSyncPacket.class,
                    ClientboundAttributeLayerSyncSerializer_v2192.INSTANCE)
            .updateSerializer(DebugDrawerPacket.class, DebugDrawerSerializer_v2192.INSTANCE)
            .updateSerializer(DimensionDataPacket.class, DimensionDataSerializer_v2192.INSTANCE)
            .updateSerializer(InventoryTransactionPacket.class, InventoryTransactionSerializer_v2192.INSTANCE)
            .updateSerializer(ItemStackResponsePacket.class, ItemStackResponseSerializer_v2192.INSTANCE)
            .updateSerializer(MoveEntityDeltaPacket.class, MoveEntityDeltaSerializer_v2192.INSTANCE)
            .updateSerializer(PlayerAuthInputPacket.class, PlayerAuthInputSerializer_v2192.INSTANCE)
            .updateSerializer(PlaySoundPacket.class, PlaySoundSerializer_v2192.INSTANCE)
            .updateSerializer(ServerboundDiagnosticsPacket.class, ServerboundDiagnosticsSerializer_v2192.INSTANCE)
            .updateSerializer(ServerboundPackSettingChangePacket.class,
                    ServerboundPackSettingChangeSerializer_v2192.INSTANCE)
            // 2192 retires the unversioned 1.26.44 remove-score discriminator.
            .updateSerializer(SetScorePacket.class, SetScoreSerializer_v2168.INSTANCE)
            .updateSerializer(SubChunkPacket.class, SubChunkSerializer_v2192.INSTANCE)
            .build();

    private Bedrock_v2192() {
    }
}
