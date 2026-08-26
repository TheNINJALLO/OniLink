package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.serializer.InventoryTransactionSerializer_v1001;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.LegacySetItemSlotData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

/**
 * Protocol 2192 removes two redundant constant-true fields and identifies which hand performed an
 * item-use action.
 */
public final class InventoryTransactionSerializer_v2192 extends InventoryTransactionSerializer_v1001 {
    public static final InventoryTransactionSerializer_v2192 INSTANCE = new InventoryTransactionSerializer_v2192();

    private InventoryTransactionSerializer_v2192() {
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, InventoryTransactionPacket packet) {
        int legacyRequestId = packet.getLegacyRequestId();
        VarInts.writeInt(buffer, legacyRequestId);
        if (legacyRequestId < -1 && (legacyRequestId & 1) == 0) {
            buffer.writeBoolean(true);
            helper.writeArray(buffer, packet.getLegacySlots(), (buf, h, data) -> {
                buf.writeByte(data.getContainerId());
                h.writeByteArray(buf, data.getSlots());
            });
        } else {
            buffer.writeBoolean(false);
        }

        InventoryTransactionType type = packet.getTransactionType();
        VarInts.writeUnsignedInt(buffer, type.ordinal());
        writeInventoryActions(buffer, helper, packet.getActions());
        switch (type) {
            case ITEM_USE -> writeItemUse(buffer, helper, packet);
            case ITEM_USE_ON_ENTITY -> writeItemUseOnEntity(buffer, helper, packet);
            case ITEM_RELEASE -> writeItemRelease(buffer, helper, packet);
            default -> {
            }
        }
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, InventoryTransactionPacket packet) {
        int legacyRequestId = VarInts.readInt(buffer);
        packet.setLegacyRequestId(legacyRequestId);
        if (buffer.readBoolean() && legacyRequestId < -1 && (legacyRequestId & 1) == 0) {
            helper.readArray(buffer, packet.getLegacySlots(), (buf, h) ->
                    new LegacySetItemSlotData(buf.readUnsignedByte(), h.readByteArray(buf, 89)));
        }

        InventoryTransactionType type = InventoryTransactionType.values()[VarInts.readUnsignedInt(buffer)];
        packet.setTransactionType(type);
        readInventoryActions(buffer, helper, packet.getActions());
        switch (type) {
            case ITEM_USE -> readItemUse(buffer, helper, packet);
            case ITEM_USE_ON_ENTITY -> readItemUseOnEntity(buffer, helper, packet);
            case ITEM_RELEASE -> readItemRelease(buffer, helper, packet);
            default -> {
            }
        }
    }

    @Override
    public void writeItemUse(ByteBuf buffer, BedrockCodecHelper helper, InventoryTransactionPacket packet) {
        VarInts.writeInt(buffer, packet.getActionType());
        buffer.writeByte(packet.getTriggerType().ordinal());
        helper.writeBlockPosition(buffer, packet.getBlockPosition());
        buffer.writeByte(packet.getBlockFace());
        VarInts.writeInt(buffer, packet.getHotbarSlot());
        buffer.writeByte(packet.getHandSlot().ordinal());
        helper.writeNetworkItemStackDescriptor(buffer, packet.getItemInHand());
        helper.writeVector3f(buffer, packet.getPlayerPosition());
        helper.writeVector3f(buffer, packet.getClickPosition());
        VarInts.writeUnsignedInt(buffer, packet.getBlockDefinition().getRuntimeId());
        buffer.writeByte(packet.getClientInteractPrediction().ordinal());
        buffer.writeByte(packet.getClientCooldownState());
    }

    @Override
    public void readItemUse(ByteBuf buffer, BedrockCodecHelper helper, InventoryTransactionPacket packet) {
        packet.setActionType(VarInts.readInt(buffer));
        packet.setTriggerType(ItemUseTransaction.TriggerType.values()[buffer.readUnsignedByte()]);
        packet.setBlockPosition(helper.readBlockPosition(buffer));
        packet.setBlockFace(buffer.readUnsignedByte());
        packet.setHotbarSlot(VarInts.readInt(buffer));
        int hand = buffer.readUnsignedByte();
        InventoryTransactionPacket.HandSlot[] hands = InventoryTransactionPacket.HandSlot.values();
        packet.setHandSlot(hand < hands.length ? hands[hand] : InventoryTransactionPacket.HandSlot.MAIN_HAND);
        packet.setItemInHand(helper.readNetworkItemStackDescriptor(buffer));
        packet.setPlayerPosition(helper.readVector3f(buffer));
        packet.setClickPosition(helper.readVector3f(buffer));
        packet.setBlockDefinition(helper.getBlockDefinitions().getDefinition(VarInts.readUnsignedInt(buffer)));
        packet.setClientInteractPrediction(ItemUseTransaction.PredictedResult.values()[buffer.readUnsignedByte()]);
        packet.setClientCooldownState(buffer.readUnsignedByte());
    }
}
