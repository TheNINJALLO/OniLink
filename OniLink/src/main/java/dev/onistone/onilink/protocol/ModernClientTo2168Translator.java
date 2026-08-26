package dev.onistone.onilink.protocol;

import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundPackSettingChangePacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;

import java.util.List;

/** Adjacent translator for Minecraft 1.26.50 (2192) to the 1.26.4x (2168) family. */
public final class ModernClientTo2168Translator implements PacketTranslator {
    public static final ModernClientTo2168Translator INSTANCE = new ModernClientTo2168Translator();

    private static final int SET_PLAYER_FURNACE_OPTIONS = 351;
    private static final int RECORD_STARTED = 352;

    private ModernClientTo2168Translator() {
    }

    @Override
    public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) {
        if (is2192OnlyPacket(packet)) {
            return null;
        }
        if (packet instanceof ServerboundPackSettingChangePacket setting
                && setting.getPackSettingValue() instanceof List<?>) {
            // 2168 has no string-list pack-setting arm. Reinterpreting it as another arm would
            // silently change the setting, so match Endweave and drop the whole update.
            return null;
        }
        return packet;
    }

    @Override
    public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) {
        return is2192OnlyPacket(packet) ? null : packet;
    }

    @Override
    public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) {
        return packet;
    }

    private static boolean is2192OnlyPacket(BedrockPacket packet) {
        if (!(packet instanceof UnknownPacket unknown)) {
            return false;
        }
        return unknown.getPacketId() == SET_PLAYER_FURNACE_OPTIONS
                || unknown.getPacketId() == RECORD_STARTED;
    }
}
