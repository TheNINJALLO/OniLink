package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.ItemStackResponseSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Protocol 2192 removes the constant-true wrapper around the optional container list. */
public final class ItemStackResponseSerializer_v2192 extends ItemStackResponseSerializer_v2168 {
    public static final ItemStackResponseSerializer_v2192 INSTANCE = new ItemStackResponseSerializer_v2192();

    private ItemStackResponseSerializer_v2192() {
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, ItemStackResponsePacket packet) {
        helper.writeArray(buffer, packet.getEntries(), (buf, response) -> {
            buf.writeByte(response.getResult().ordinal());
            VarInts.writeInt(buf, response.getRequestId());
            helper.writeOptional(buf, containers -> !containers.isEmpty(), response.getContainers(),
                    (out, containers) -> helper.writeArray(out, containers, helper::writeItemStackResponseContainer));
        });
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, ItemStackResponsePacket packet) {
        helper.readArray(buffer, packet.getEntries(), buf -> {
            ItemStackResponseStatus result = ItemStackResponseStatus.values()[buf.readUnsignedByte()];
            int requestId = VarInts.readInt(buf);
            if (!buf.readBoolean()) {
                return new ItemStackResponse(result, requestId, Collections.emptyList());
            }
            List<ItemStackResponseContainer> containers = new ArrayList<>();
            helper.readArray(buf, containers, helper::readItemStackResponseContainer);
            return new ItemStackResponse(result, requestId, containers);
        });
    }
}
