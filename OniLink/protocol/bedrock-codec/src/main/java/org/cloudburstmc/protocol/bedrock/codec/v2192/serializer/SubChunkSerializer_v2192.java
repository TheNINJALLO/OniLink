package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.SubChunkSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.data.HeightMapDataType;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.common.util.Preconditions;
import org.cloudburstmc.protocol.common.util.VarInts;

/** Protocol 2192 length-prefixes each of the sixteen height-map rows. */
public final class SubChunkSerializer_v2192 extends SubChunkSerializer_v2168 {
    public static final SubChunkSerializer_v2192 INSTANCE = new SubChunkSerializer_v2192();

    private static final int ROWS = 16;
    private static final int ROW_WIDTH = 16;
    private static final int MAP_BYTES = ROWS * ROW_WIDTH;

    private SubChunkSerializer_v2192() {
    }

    @Override
    protected void serializeSubChunk(ByteBuf buffer, BedrockCodecHelper helper,
                                     org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket packet,
                                     SubChunkData subChunk) {
        writeSubChunkOffset(buffer, subChunk.getPosition());
        buffer.writeByte(subChunk.getResult().ordinal());
        helper.writeOptionalNull(buffer, subChunk.getData(), helper::writeByteBuf);
        writeHeightMap(buffer, helper, subChunk.getHeightMapType(), subChunk.getHeightMapData());
        writeHeightMap(buffer, helper, subChunk.getRenderHeightMapType(), subChunk.getRenderHeightMapData());
        helper.writeOptionalNull(buffer, subChunk.getBlobId(), ByteBuf::writeLongLE);
    }

    @Override
    protected SubChunkData deserializeSubChunk(ByteBuf buffer, BedrockCodecHelper helper,
                                               org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket packet) {
        SubChunkData subChunk = new SubChunkData();
        subChunk.setPosition(readSubChunkOffset(buffer));
        subChunk.setResult(org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult.values()[
                buffer.readUnsignedByte()]);
        subChunk.setData(helper.readOptional(buffer, null, helper::readByteBuf));
        subChunk.setHeightMapType(HeightMapDataType.values()[buffer.readUnsignedByte()]);
        subChunk.setHeightMapData(readRowsOptional(buffer, helper));
        subChunk.setRenderHeightMapType(HeightMapDataType.values()[buffer.readUnsignedByte()]);
        subChunk.setRenderHeightMapData(readRowsOptional(buffer, helper));
        subChunk.setBlobId(helper.readOptional(buffer, null, ByteBuf::readLongLE));
        return subChunk;
    }

    private static void writeHeightMap(ByteBuf buffer, BedrockCodecHelper helper,
                                       HeightMapDataType type, ByteBuf data) {
        buffer.writeByte(type.ordinal());
        helper.writeOptionalNull(buffer, data, (buf, map) -> {
            int start = map.readerIndex();
            for (int row = 0; row < ROWS; row++) {
                VarInts.writeUnsignedInt(buf, ROW_WIDTH);
                int rowStart = start + row * ROW_WIDTH;
                for (int column = 0; column < ROW_WIDTH; column++) {
                    int index = rowStart + column;
                    buf.writeByte(index < start + map.readableBytes() ? map.getByte(index) : 0);
                }
            }
        });
    }

    private static ByteBuf readRowsOptional(ByteBuf buffer, BedrockCodecHelper helper) {
        if (!buffer.readBoolean()) {
            return null;
        }
        ByteBuf fixed = buffer.alloc().buffer(MAP_BYTES, MAP_BYTES);
        fixed.writeZero(MAP_BYTES);
        for (int row = 0; row < ROWS; row++) {
            int length = VarInts.readUnsignedInt(buffer);
            Preconditions.checkArgument(length <= buffer.readableBytes(),
                    "Height-map row is longer than the packet: %s", length);
            int copied = Math.min(length, ROW_WIDTH);
            buffer.getBytes(buffer.readerIndex(), fixed, row * ROW_WIDTH, copied);
            buffer.skipBytes(length);
        }
        fixed.readerIndex(0);
        return fixed;
    }
}
