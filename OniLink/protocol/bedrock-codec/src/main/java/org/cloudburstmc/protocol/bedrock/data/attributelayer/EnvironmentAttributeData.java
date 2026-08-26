package org.cloudburstmc.protocol.bedrock.data.attributelayer;

import lombok.Value;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraEase;

@Value
public class EnvironmentAttributeData {

    String attributeName;
    @Nullable
    AttributeData from;
    AttributeData attribute;
    @Nullable
    AttributeData to;
    int currentTransitionTicks;
    int totalTransitionTicks;
    CameraEase easing;
    /**
     * @since v1001
     */
    int localTransitionTicks;
    /**
     * @since v1001
     */
    boolean noiseTransition;

    /**
     * @since v2192
     */
    @Nullable
    NoiseAlignment noiseAlignment;

    public EnvironmentAttributeData(
            String attributeName,
            @Nullable AttributeData from,
            AttributeData attribute,
            @Nullable AttributeData to,
            int currentTransitionTicks,
            int totalTransitionTicks,
            CameraEase easing,
            int localTransitionTicks,
            boolean noiseTransition,
            @Nullable NoiseAlignment noiseAlignment
    ) {
        this.attributeName = attributeName;
        this.from = from;
        this.attribute = attribute;
        this.to = to;
        this.currentTransitionTicks = currentTransitionTicks;
        this.totalTransitionTicks = totalTransitionTicks;
        this.easing = easing;
        this.localTransitionTicks = localTransitionTicks;
        this.noiseTransition = noiseTransition;
        this.noiseAlignment = noiseAlignment;
    }

    public EnvironmentAttributeData(
            String attributeName,
            @Nullable AttributeData from,
            AttributeData attribute,
            @Nullable AttributeData to,
            int currentTransitionTicks,
            int totalTransitionTicks,
            CameraEase easing,
            int localTransitionTicks,
            boolean noiseTransition
    ) {
        this(attributeName, from, attribute, to, currentTransitionTicks, totalTransitionTicks, easing,
                localTransitionTicks, noiseTransition, null);
    }
}
