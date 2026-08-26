package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.PlayerAuthInputSerializer_v2168;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.LegacySetItemSlotData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

/** Protocol 2192's PlayerAuthInput layout without redundant constant-true fields. */
public final class PlayerAuthInputSerializer_v2192 extends PlayerAuthInputSerializer_v2168 {
    public static final PlayerAuthInputSerializer_v2192 INSTANCE = new PlayerAuthInputSerializer_v2192();

    private PlayerAuthInputSerializer_v2192() {
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerAuthInputPacket packet) {
        Vector3f rotation = packet.getRotation();
        buffer.writeFloatLE(rotation.getX());
        buffer.writeFloatLE(rotation.getY());
        helper.writeVector3f(buffer, packet.getPosition());
        buffer.writeFloatLE(packet.getMotion().getX());
        buffer.writeFloatLE(packet.getMotion().getY());
        buffer.writeFloatLE(rotation.getZ());

        VarInts.writeUnsignedInt(buffer, packet.getInputData().size());
        for (PlayerAuthInputData flag : packet.getInputData()) {
            VarInts.writeInt(buffer, flag.ordinal());
        }
        VarInts.writeUnsignedInt(buffer, packet.getInputMode().ordinal());
        VarInts.writeUnsignedInt(buffer, packet.getPlayMode().ordinal());
        VarInts.writeInt(buffer, packet.getInputInteractionModel().ordinal());
        helper.writeVector2f(buffer, packet.getInteractRotation());
        VarInts.writeUnsignedLong(buffer, packet.getTick());
        helper.writeVector3f(buffer, packet.getDelta());

        helper.writeOptionalNull(buffer, packet.getItemUseTransaction(), this::writeItemUseTransaction);
        helper.writeOptionalNull(buffer, packet.getItemStackRequest(), helper::writeItemStackRequest);
        helper.writeOptional(buffer, actions -> !actions.isEmpty(), packet.getPlayerActions(),
                (buf, actions) -> helper.writeArray(buf, actions, this::writePlayerBlockActionData));
        helper.writeOptionalNull(buffer, packet.getVehicleRotation(), helper::writeVector2f);
        boolean hasPredictedVehicle = packet.getInputData().contains(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
        helper.writeOptional(buffer, ignored -> hasPredictedVehicle, packet.getPredictedVehicle(), VarInts::writeLong);
        helper.writeVector2f(buffer, packet.getAnalogMoveVector());
        helper.writeVector3f(buffer, packet.getCameraOrientation());
        helper.writeVector2f(buffer, packet.getRawMoveVector());
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, PlayerAuthInputPacket packet) {
        float x = buffer.readFloatLE();
        float y = buffer.readFloatLE();
        packet.setPosition(helper.readVector3f(buffer));
        packet.setMotion(Vector2f.from(buffer.readFloatLE(), buffer.readFloatLE()));
        float z = buffer.readFloatLE();
        packet.setRotation(Vector3f.from(x, y, z));

        int count = VarInts.readUnsignedInt(buffer);
        PlayerAuthInputData[] flags = PlayerAuthInputData.values();
        for (int i = 0; i < count; i++) {
            int index = VarInts.readInt(buffer);
            if (index >= 0 && index < flags.length) {
                packet.getInputData().add(flags[index]);
            }
        }
        packet.setInputMode(INPUT_MODES[VarInts.readUnsignedInt(buffer)]);
        packet.setPlayMode(CLIENT_PLAY_MODES[VarInts.readUnsignedInt(buffer)]);
        packet.setInputInteractionModel(VALUES[VarInts.readInt(buffer)]);
        packet.setInteractRotation(helper.readVector2f(buffer));
        packet.setTick(VarInts.readUnsignedLong(buffer));
        packet.setDelta(helper.readVector3f(buffer));

        packet.setItemUseTransaction(helper.readOptional(buffer, null,
                buf -> readItemUseTransaction(buf, helper)));
        packet.setItemStackRequest(helper.readOptional(buffer, null, helper::readItemStackRequest));
        if (buffer.readBoolean()) {
            helper.readArray(buffer, packet.getPlayerActions(), this::readPlayerBlockActionData, 100);
        }
        packet.setVehicleRotation(helper.readOptional(buffer, null, helper::readVector2f));
        if (buffer.readBoolean()) {
            packet.setPredictedVehicle(VarInts.readLong(buffer));
        }
        packet.setAnalogMoveVector(helper.readVector2f(buffer));
        packet.setCameraOrientation(helper.readVector3f(buffer));
        packet.setRawMoveVector(helper.readVector2f(buffer));
    }

    @Override
    protected void writeItemUseTransaction(ByteBuf buffer, BedrockCodecHelper helper, ItemUseTransaction transaction) {
        int legacyRequestId = transaction.getLegacyRequestId();
        VarInts.writeInt(buffer, legacyRequestId);
        if (legacyRequestId < -1 && (legacyRequestId & 1) == 0) {
            buffer.writeBoolean(true);
            helper.writeArray(buffer, transaction.getLegacySlots(), (buf, h, data) -> {
                buf.writeByte(data.getContainerId());
                h.writeByteArray(buf, data.getSlots());
            });
        } else {
            buffer.writeBoolean(false);
        }
        helper.writeInventoryActions(buffer, transaction.getActions(), transaction.isUsingNetIds());
        VarInts.writeInt(buffer, transaction.getActionType());
        buffer.writeByte(transaction.getTriggerType().ordinal());
        helper.writeBlockPosition(buffer, transaction.getBlockPosition());
        buffer.writeByte(transaction.getBlockFace());
        VarInts.writeInt(buffer, transaction.getHotbarSlot());
        helper.writeItem(buffer, transaction.getItemInHand());
        helper.writeVector3f(buffer, transaction.getPlayerPosition());
        helper.writeVector3f(buffer, transaction.getClickPosition());
        VarInts.writeUnsignedInt(buffer, transaction.getBlockDefinition().getRuntimeId());
        buffer.writeByte(transaction.getClientInteractPrediction().ordinal());
        buffer.writeByte(transaction.getClientCooldownState());
    }

    @Override
    protected ItemUseTransaction readItemUseTransaction(ByteBuf buffer, BedrockCodecHelper helper) {
        ItemUseTransaction transaction = new ItemUseTransaction();
        int legacyRequestId = VarInts.readInt(buffer);
        transaction.setLegacyRequestId(legacyRequestId);
        if (buffer.readBoolean() && legacyRequestId < -1 && (legacyRequestId & 1) == 0) {
            helper.readArray(buffer, transaction.getLegacySlots(), (buf, h) ->
                    new LegacySetItemSlotData(buf.readUnsignedByte(), h.readByteArray(buf, 89)));
        }
        helper.readInventoryActions(buffer, transaction.getActions());
        transaction.setActionType(VarInts.readInt(buffer));
        transaction.setTriggerType(ItemUseTransaction.TriggerType.values()[buffer.readUnsignedByte()]);
        transaction.setBlockPosition(helper.readBlockPosition(buffer));
        transaction.setBlockFace(buffer.readUnsignedByte());
        transaction.setHotbarSlot(VarInts.readInt(buffer));
        transaction.setItemInHand(helper.readItem(buffer));
        transaction.setPlayerPosition(helper.readVector3f(buffer));
        transaction.setClickPosition(helper.readVector3f(buffer));
        transaction.setBlockDefinition(helper.getBlockDefinitions().getDefinition(VarInts.readUnsignedInt(buffer)));
        transaction.setClientInteractPrediction(ItemUseTransaction.PredictedResult.values()[buffer.readUnsignedByte()]);
        transaction.setClientCooldownState(buffer.readUnsignedByte());
        return transaction;
    }
}
