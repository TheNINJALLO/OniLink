package org.cloudburstmc.protocol.bedrock.data.attributelayer;

import lombok.Value;

/** The environment-noise alignment introduced by protocol 2192. */
@Value
public class NoiseAlignment {
    Type type;
    int value;

    public enum Type {
        MIN_LOCAL_TRANSITION_END
    }
}
