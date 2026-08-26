package org.cloudburstmc.protocol.bedrock.codec.v2168;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.SetScoreSerializer_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;

/**
 * The 1.26.44 dialect of protocol 2168.
 *
 * <p>Mojang changed {@link SetScorePacket}'s remove-entry shape without changing the advertised
 * protocol number. Keeping this as a distinct codec lets a proxy choose the layout from the
 * backend's version string while still placing 2168 on the wire.</p>
 */
public final class Bedrock_v2168_hotfix4 extends Bedrock_v2168 {
    public static final BedrockCodec CODEC = Bedrock_v2168.CODEC.toBuilder()
            .protocolVersion(2168)
            .minecraftVersion("1.26.44")
            .helper(() -> new BedrockCodecHelper_v2168(
                    ENTITY_DATA,
                    GAME_RULE_TYPES,
                    ITEM_STACK_REQUEST_TYPES,
                    CONTAINER_SLOT_TYPES,
                    PLAYER_ABILITIES,
                    TEXT_PROCESSING_ORIGINS
            ))
            .updateSerializer(SetScorePacket.class, SetScoreSerializer_v2168_hotfix4.INSTANCE)
            .build();

    private Bedrock_v2168_hotfix4() {
    }
}
