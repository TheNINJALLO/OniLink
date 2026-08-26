package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.serializer.ClientboundAttributeLayerSyncSerializer_v1001;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.EnvironmentAttributeData;
import org.cloudburstmc.protocol.bedrock.data.attributelayer.NoiseAlignment;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraEase;
import org.cloudburstmc.protocol.common.util.VarInts;

/** Environment-attribute noise alignment introduced in protocol 2192. */
public final class ClientboundAttributeLayerSyncSerializer_v2192
        extends ClientboundAttributeLayerSyncSerializer_v1001 {
    public static final ClientboundAttributeLayerSyncSerializer_v2192 INSTANCE =
            new ClientboundAttributeLayerSyncSerializer_v2192();

    private ClientboundAttributeLayerSyncSerializer_v2192() {
    }

    @Override
    protected void writeEnvironmentAttribute(ByteBuf buffer, BedrockCodecHelper helper, EnvironmentAttributeData data) {
        super.writeEnvironmentAttribute(buffer, helper, data);
        NoiseAlignment alignment = data.getNoiseAlignment();
        buffer.writeByte(alignment == null ? 0 : alignment.getType().ordinal());
        VarInts.writeUnsignedInt(buffer, alignment == null ? 0 : alignment.getValue());
    }

    @Override
    protected EnvironmentAttributeData readEnvironmentAttribute(ByteBuf buffer, BedrockCodecHelper helper) {
        String name = helper.readStringMaxLen(buffer, 128);
        AttributeData from = helper.readOptional(buffer, null, buf -> readAttributeData(buf, helper));
        AttributeData attribute = readAttributeData(buffer, helper);
        AttributeData to = helper.readOptional(buffer, null, buf -> readAttributeData(buf, helper));
        int currentTicks = (int) buffer.readUnsignedIntLE();
        int totalTicks = (int) buffer.readUnsignedIntLE();
        CameraEase easing = CameraEase.fromName(helper.readString(buffer));
        int localTransitionTicks = (int) buffer.readUnsignedIntLE();
        boolean noiseTransition = buffer.readBoolean();
        int alignmentOrdinal = buffer.readUnsignedByte();
        NoiseAlignment.Type[] types = NoiseAlignment.Type.values();
        NoiseAlignment.Type alignmentType = alignmentOrdinal < types.length
                ? types[alignmentOrdinal]
                : NoiseAlignment.Type.MIN_LOCAL_TRANSITION_END;
        NoiseAlignment alignment = new NoiseAlignment(alignmentType, VarInts.readUnsignedInt(buffer));
        return new EnvironmentAttributeData(name, from, attribute, to, currentTicks, totalTicks, easing,
                localTransitionTicks, noiseTransition, alignment);
    }
}
