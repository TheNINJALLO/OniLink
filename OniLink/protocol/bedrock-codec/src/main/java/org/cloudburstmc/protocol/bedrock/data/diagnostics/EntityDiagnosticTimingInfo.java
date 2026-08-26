package org.cloudburstmc.protocol.bedrock.data.diagnostics;

import lombok.Value;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.math.vector.Vector3f;

@Value
public class EntityDiagnosticTimingInfo {
    String displayName;
    String entity;
    long timeInNs;
    byte percentOfTotal;
    /** @since v2192 */
    @Nullable
    Vector3f position;
    /** @since v2192 */
    @Nullable
    String dimension;

    public EntityDiagnosticTimingInfo(
            String displayName,
            String entity,
            long timeInNs,
            byte percentOfTotal,
            @Nullable Vector3f position,
            @Nullable String dimension
    ) {
        this.displayName = displayName;
        this.entity = entity;
        this.timeInNs = timeInNs;
        this.percentOfTotal = percentOfTotal;
        this.position = position;
        this.dimension = dimension;
    }

    public EntityDiagnosticTimingInfo(String displayName, String entity, long timeInNs, byte percentOfTotal) {
        this(displayName, entity, timeInNs, percentOfTotal, null, null);
    }
}
