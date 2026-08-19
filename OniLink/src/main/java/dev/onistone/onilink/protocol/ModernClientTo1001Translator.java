package dev.onistone.onilink.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/**
 * Adjacent-version translator for the 1.26.40 (protocol 2168) &harr; 1.26.30 (protocol 1001) step.
 *
 * <p>The versioned codecs handle field layouts and type-map changes. The sound-data packet is
 * dropped because its two schemas have no lossless shared representation. Player-list action
 * normalization lives in {@code PlayerListSerializer_v2168}, where it also covers packets created
 * by the proxy.</p>
 */
public final class ModernClientTo1001Translator implements PacketTranslator {
    public static final ModernClientTo1001Translator INSTANCE = new ModernClientTo1001Translator();

    private ModernClientTo1001Translator() {
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        return packet;
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket) {
            return null;
        }
        return packet;
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }
}
