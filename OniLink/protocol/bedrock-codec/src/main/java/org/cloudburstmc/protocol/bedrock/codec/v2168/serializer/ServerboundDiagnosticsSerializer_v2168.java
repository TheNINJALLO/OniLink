package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v975.serializer.ServerboundDiagnosticsSerializer_v975;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.EntityDiagnosticTimingInfo;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.MemoryCategoryCounter;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.SystemCategory;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.SystemDiagnosticTimingInfo;
import org.cloudburstmc.protocol.bedrock.data.diagnostics.WhiskerScopeDataSummary;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDiagnosticsPacket;

public class ServerboundDiagnosticsSerializer_v2168 extends ServerboundDiagnosticsSerializer_v975 { // 975 intentional, system before whisker

    public static final ServerboundDiagnosticsSerializer_v2168 INSTANCE = new ServerboundDiagnosticsSerializer_v2168();

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
        });
        helper.writeArray(buffer, packet.getSystemDiagnostics(), (buf, info) -> {
            helper.writeString(buf, info.getDisplayName());
            buf.writeLongLE(info.getSystemIndex());
            buf.writeLongLE(info.getTimeInNs());
            buf.writeByte(info.getPercentOfTotal());
        });

        helper.writeOptional(buffer, categories -> !categories.isEmpty(), packet.getSystemCategories(),
                (out, categories) -> helper.writeArray(out, categories, (buf, info) -> {
                    helper.writeString(buf, info.getCategoryName());
                    buf.writeLongLE(info.getSystemIndex());
                }));

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
                helper.readString(buf), helper.readString(buf), buf.readLongLE(), (byte) buf.readUnsignedByte()));
        helper.readArray(buffer, packet.getSystemDiagnostics(), buf -> new SystemDiagnosticTimingInfo(
                helper.readString(buf), buf.readLongLE(), buf.readLongLE(), (byte) buf.readUnsignedByte()));

        if (buffer.readBoolean()) {
            helper.readArray(buffer, packet.getSystemCategories(), buf ->
                    new SystemCategory(helper.readString(buf), buf.readLongLE()));
        }

        helper.readArray(buffer, packet.getWhiskerScopes(), buf ->
                new WhiskerScopeDataSummary(
                        helper.readString(buf),
                        helper.readString(buf),
                        buf.readLongLE(),
                        buf.readLongLE(),
                        buf.readLongLE()));
    }

    private static int toWireCategory(MemoryCategoryCounter.Category category) {
        int ordinal = category.ordinal();
        if (ordinal <= 4) {
            return ordinal;
        }
        if (category == MemoryCategoryCounter.Category.BALANCER) {
            return MemoryCategoryCounter.Category.UNKNOWN.ordinal();
        }
        return ordinal <= 43 ? ordinal - 1 : ordinal;
    }

    private static MemoryCategoryCounter.Category fromWireCategory(int wire) {
        if (wire == 43) {
            return MemoryCategoryCounter.Category.UNKNOWN; // LIGHT_VOLUME_MANAGER has no Java model.
        }
        int ordinal = wire <= 42 && wire >= 5 ? wire + 1 : wire;
        MemoryCategoryCounter.Category[] values = MemoryCategoryCounter.Category.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MemoryCategoryCounter.Category.UNKNOWN;
    }
}
