package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.ServerboundDiagnosticsSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.EntityDiagnosticTimingInfo;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.MemoryCategoryCounter;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.SystemCategory;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.SystemDiagnosticTimingInfo;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.WhiskerScopeDataSummary;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDiagnosticsPacket;

/** Protocol 2192's diagnostic position fields and memory-category renumbering. */
public final class ServerboundDiagnosticsSerializer_v2192 extends ServerboundDiagnosticsSerializer_v2168 {
    public static final ServerboundDiagnosticsSerializer_v2192 INSTANCE = new ServerboundDiagnosticsSerializer_v2192();

    private ServerboundDiagnosticsSerializer_v2192() {
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, ServerboundDiagnosticsPacket packet) {
        buffer.writeFloatLE(packet.getAvgFps());
        buffer.writeFloatLE(packet.getAvgServerSimTickTimeMS());
        buffer.writeFloatLE(packet.getAvgClientSimTickTimeMS());
        buffer.writeFloatLE(packet.getAvgBeginFrameTimeMS());
        buffer.writeFloatLE(packet.getAvgInputTimeMS());
        buffer.writeFloatLE(packet.getAvgRenderTimeMS());
        buffer.writeFloatLE(packet.getAvgEndFrameTimeMS());
        buffer.writeFloatLE(packet.getAvgRemainderTimePercent());
        buffer.writeFloatLE(packet.getAvgUnaccountedTimePercent());

        helper.writeArray(buffer, packet.getMemoryCategoryValues(), (buf, counter) -> {
            buf.writeByte(toWireCategory(counter.getCategory()));
            buf.writeLongLE(counter.getCurrentBytes());
        });
        helper.writeArray(buffer, packet.getEntityDiagnostics(), (buf, info) -> {
            helper.writeString(buf, info.getDisplayName());
            helper.writeString(buf, info.getEntity());
            buf.writeLongLE(info.getTimeInNs());
            buf.writeByte(info.getPercentOfTotal());
            helper.writeOptionalNull(buf, info.getPosition(), helper::writeVector3f);
            helper.writeOptionalNull(buf, info.getDimension(), helper::writeString);
        });
        helper.writeArray(buffer, packet.getSystemDiagnostics(), (buf, info) -> {
            helper.writeString(buf, info.getDisplayName());
            buf.writeLongLE(info.getSystemIndex());
            buf.writeLongLE(info.getTimeInNs());
            buf.writeByte(info.getPercentOfTotal());
        });
        helper.writeArray(buffer, packet.getSystemCategories(), (buf, info) -> {
            helper.writeString(buf, info.getCategoryName());
            buf.writeLongLE(info.getSystemIndex());
        });
        helper.writeArray(buffer, packet.getWhiskerScopes(), (buf, info) -> {
            helper.writeString(buf, info.getLabel());
            helper.writeString(buf, info.getIndentation());
            buf.writeLongLE(info.getTotalHighCostNS());
            buf.writeLongLE(info.getTotalMidCostNS());
            buf.writeLongLE(info.getTotalLowCostNS());
        });
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, ServerboundDiagnosticsPacket packet) {
        packet.setAvgFps(buffer.readFloatLE());
        packet.setAvgServerSimTickTimeMS(buffer.readFloatLE());
        packet.setAvgClientSimTickTimeMS(buffer.readFloatLE());
        packet.setAvgBeginFrameTimeMS(buffer.readFloatLE());
        packet.setAvgInputTimeMS(buffer.readFloatLE());
        packet.setAvgRenderTimeMS(buffer.readFloatLE());
        packet.setAvgEndFrameTimeMS(buffer.readFloatLE());
        packet.setAvgRemainderTimePercent(buffer.readFloatLE());
        packet.setAvgUnaccountedTimePercent(buffer.readFloatLE());

        helper.readArray(buffer, packet.getMemoryCategoryValues(), buf ->
                new MemoryCategoryCounter(fromWireCategory(buf.readUnsignedByte()), buf.readLongLE()));
        helper.readArray(buffer, packet.getEntityDiagnostics(), buf -> new EntityDiagnosticTimingInfo(
                helper.readString(buf),
                helper.readString(buf),
                buf.readLongLE(),
                (byte) buf.readUnsignedByte(),
                helper.readOptional(buf, null, helper::readVector3f),
                helper.readOptional(buf, null, helper::readString)
        ));
        helper.readArray(buffer, packet.getSystemDiagnostics(), buf -> new SystemDiagnosticTimingInfo(
                helper.readString(buf), buf.readLongLE(), buf.readLongLE(), (byte) buf.readUnsignedByte()));
        helper.readArray(buffer, packet.getSystemCategories(), buf ->
                new SystemCategory(helper.readString(buf), buf.readLongLE()));
        helper.readArray(buffer, packet.getWhiskerScopes(), buf -> new WhiskerScopeDataSummary(
                helper.readString(buf), helper.readString(buf), buf.readLongLE(), buf.readLongLE(), buf.readLongLE()));
    }

    private static int toWireCategory(MemoryCategoryCounter.Category category) {
        int ordinal = category.ordinal();
        if (ordinal <= 4) {
            return ordinal;
        }
        if (category == MemoryCategoryCounter.Category.BALANCER
                || category == MemoryCategoryCounter.Category.PERSONA_TEXTURES) {
            return 0;
        }
        if (ordinal <= 43) {
            return ordinal - 1;
        }
        if (ordinal <= 57) {
            return ordinal;
        }
        return ordinal - 1;
    }

    private static MemoryCategoryCounter.Category fromWireCategory(int wire) {
        int ordinal;
        if (wire <= 4) {
            ordinal = wire;
        } else if (wire <= 42) {
            ordinal = wire + 1;
        } else if (wire == 43) {
            return MemoryCategoryCounter.Category.UNKNOWN; // LIGHT_VOLUME_MANAGER has no Java model.
        } else if (wire <= 57) {
            ordinal = wire;
        } else {
            ordinal = wire + 1; // 2192 removed PERSONA_TEXTURES.
        }
        MemoryCategoryCounter.Category[] values = MemoryCategoryCounter.Category.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MemoryCategoryCounter.Category.UNKNOWN;
    }
}
