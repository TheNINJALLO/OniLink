package org.cloudburstmc.protocol.bedrock.data.definitions;

import lombok.Value;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.UUID;

@Value
public class DimensionDefinition {
    String id;
    int maximumHeight;
    int minimumHeight;
    int generatorType;
    int dimensionType;
    /**
     * @since v2168
     */
    UUID packId;

    /**
     * @since v2192
     */
    @Nullable
    String defaultBiome;

    public DimensionDefinition(
            String id,
            int maximumHeight,
            int minimumHeight,
            int generatorType,
            int dimensionType,
            UUID packId,
            @Nullable String defaultBiome
    ) {
        this.id = id;
        this.maximumHeight = maximumHeight;
        this.minimumHeight = minimumHeight;
        this.generatorType = generatorType;
        this.dimensionType = dimensionType;
        this.packId = packId;
        this.defaultBiome = defaultBiome;
    }

    public DimensionDefinition(
            String id,
            int maximumHeight,
            int minimumHeight,
            int generatorType,
            int dimensionType,
            UUID packId
    ) {
        this(id, maximumHeight, minimumHeight, generatorType, dimensionType, packId, null);
    }
}
