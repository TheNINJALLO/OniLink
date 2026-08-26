package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v818.serializer.CameraPresetsSerializer_v818;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraPreset;

/** The starting-rotation tail added to every camera preset in protocol 2192. */
public final class CameraPresetsSerializer_v2192 extends CameraPresetsSerializer_v818 {
    public static final CameraPresetsSerializer_v2192 INSTANCE = new CameraPresetsSerializer_v2192();

    private CameraPresetsSerializer_v2192() {
    }

    @Override
    public void writePreset(ByteBuf buffer, BedrockCodecHelper helper, CameraPreset preset) {
        super.writePreset(buffer, helper, preset);
        buffer.writeBoolean(preset.isApplyInheritedStartingRotation());
        helper.writeOptionalNull(buffer, preset.getStartingRotation(), helper::writeVector2f);
    }

    @Override
    public CameraPreset readPreset(ByteBuf buffer, BedrockCodecHelper helper) {
        CameraPreset preset = super.readPreset(buffer, helper);
        preset.setApplyInheritedStartingRotation(buffer.readBoolean());
        preset.setStartingRotation(helper.readOptional(buffer, null, helper::readVector2f));
        return preset;
    }
}
