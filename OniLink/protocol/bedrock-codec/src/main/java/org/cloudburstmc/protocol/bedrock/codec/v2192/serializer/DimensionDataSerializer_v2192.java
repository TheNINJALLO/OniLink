package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.DimensionDataSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.UUID;

/** Protocol 2192 changed maximum/minimum into minimum/range and appended a default biome. */
public final class DimensionDataSerializer_v2192 extends DimensionDataSerializer_v2168 {
    public static final DimensionDataSerializer_v2192 INSTANCE = new DimensionDataSerializer_v2192();

    private DimensionDataSerializer_v2192() {
    }

    @Override
    protected void writeDefinition(ByteBuf buffer, BedrockCodecHelper helper, DimensionDefinition definition) {
        helper.writeString(buffer, definition.getId());
        VarInts.writeInt(buffer, definition.getMinimumHeight());
        VarInts.writeInt(buffer, definition.getMaximumHeight() - definition.getMinimumHeight());
        VarInts.writeInt(buffer, definition.getGeneratorType());
        VarInts.writeInt(buffer, definition.getDimensionType());
        helper.writeUuid(buffer, definition.getPackId() == null ? new UUID(0L, 0L) : definition.getPackId());
        helper.writeString(buffer, definition.getDefaultBiome() == null ? "" : definition.getDefaultBiome());
    }

    @Override
    protected DimensionDefinition readDefinition(ByteBuf buffer, BedrockCodecHelper helper) {
        String id = helper.readString(buffer);
        int minimumHeight = VarInts.readInt(buffer);
        int heightRange = VarInts.readInt(buffer);
        int generatorType = VarInts.readInt(buffer);
        int dimensionType = VarInts.readInt(buffer);
        UUID packId = helper.readUuid(buffer);
        String defaultBiome = helper.readString(buffer);
        return new DimensionDefinition(id, minimumHeight + heightRange, minimumHeight, generatorType,
                dimensionType, packId, defaultBiome);
    }
}
