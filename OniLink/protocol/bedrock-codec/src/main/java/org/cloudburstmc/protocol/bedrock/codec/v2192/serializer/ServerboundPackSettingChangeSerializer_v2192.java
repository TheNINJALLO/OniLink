package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v844.serializer.ServerboundPackSettingChangeSerializer_v844;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundPackSettingChangePacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.ArrayList;
import java.util.List;

/** Protocol 2192's fourth resource-pack setting arm: a string list. */
public final class ServerboundPackSettingChangeSerializer_v2192
        extends ServerboundPackSettingChangeSerializer_v844 {
    public static final ServerboundPackSettingChangeSerializer_v2192 INSTANCE =
            new ServerboundPackSettingChangeSerializer_v2192();

    private ServerboundPackSettingChangeSerializer_v2192() {
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, ServerboundPackSettingChangePacket packet) {
        helper.writeUuid(buffer, packet.getPackId());
        helper.writeString(buffer, packet.getPackSettingName());
        Object value = packet.getPackSettingValue();
        int type = value instanceof Float ? 0
                : value instanceof Boolean ? 1
                : value instanceof String ? 2
                : value instanceof List<?> ? 3
                : -1;
        if (type < 0) {
            throw new IllegalStateException("Invalid pack setting type");
        }
        VarInts.writeUnsignedInt(buffer, type);
        switch (type) {
            case 0 -> buffer.writeFloatLE((Float) value);
            case 1 -> buffer.writeBoolean((Boolean) value);
            case 2 -> helper.writeString(buffer, (String) value);
            case 3 -> helper.writeArray(buffer, (List<?>) value,
                    (buf, entry) -> helper.writeString(buf, String.valueOf(entry)));
            default -> throw new IllegalStateException("Invalid pack setting type");
        }
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, ServerboundPackSettingChangePacket packet) {
        packet.setPackId(helper.readUuid(buffer));
        packet.setPackSettingName(helper.readString(buffer));
        int type = VarInts.readUnsignedInt(buffer);
        switch (type) {
            case 0 -> packet.setPackSettingValue(buffer.readFloatLE());
            case 1 -> packet.setPackSettingValue(buffer.readBoolean());
            case 2 -> packet.setPackSettingValue(helper.readString(buffer));
            case 3 -> {
                List<String> values = new ArrayList<>();
                helper.readArray(buffer, values, helper::readString);
                packet.setPackSettingValue(List.copyOf(values));
            }
            default -> throw new IllegalStateException("Invalid pack setting type " + type);
        }
    }
}
