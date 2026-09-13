package org.cloudburstmc.protocol.bedrock.codec.v2192;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.EntityDataTypeMap;
import org.cloudburstmc.protocol.bedrock.codec.v2168.BedrockCodecHelper_v2168;
import org.cloudburstmc.protocol.bedrock.data.Ability;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.TextProcessingEventOrigin;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestActionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource;
import org.cloudburstmc.protocol.common.util.TypeMap;
import org.cloudburstmc.protocol.common.util.VarInts;

import static java.util.Objects.requireNonNull;

/** Protocol-2192 removals of redundant constant-true discriminators. */
public final class BedrockCodecHelper_v2192 extends BedrockCodecHelper_v2168 {
    public BedrockCodecHelper_v2192(
            EntityDataTypeMap entityData,
            TypeMap<Class<?>> gameRulesTypes,
            TypeMap<ItemStackRequestActionType> stackRequestActionTypes,
            TypeMap<ContainerSlotType> containerSlotTypes,
            TypeMap<Ability> abilities,
            TypeMap<TextProcessingEventOrigin> textProcessingEventOrigins
    ) {
        super(entityData, gameRulesTypes, stackRequestActionTypes, containerSlotTypes, abilities,
                textProcessingEventOrigins);
    }

    @Override
    protected ItemStackResponseSlot readItemEntry(ByteBuf buffer) {
        int slot = buffer.readUnsignedByte();
        int hotbarSlot = buffer.readUnsignedByte();
        int count = buffer.readUnsignedByte();
        int stackNetworkId = buffer.readBoolean() ? VarInts.readInt(buffer) : 0;
        String customName = readString(buffer);
        String filteredCustomName = readOptional(buffer, null, this::readString);
        int durabilityCorrection = VarInts.readInt(buffer);
        return new ItemStackResponseSlot(slot, hotbarSlot, count, stackNetworkId,
                customName, durabilityCorrection, filteredCustomName);
    }

    @Override
    protected void writeItemEntry(ByteBuf buffer, ItemStackResponseSlot itemEntry) {
        buffer.writeByte(itemEntry.getSlot());
        buffer.writeByte(itemEntry.getHotbarSlot());
        buffer.writeByte(itemEntry.getCount());
        writeOptional(buffer, id -> id > 0, itemEntry.getStackNetworkId(), VarInts::writeInt);
        writeString(buffer, itemEntry.getCustomName());
        writeOptionalNull(buffer, itemEntry.getFilteredCustomName(), this::writeString);
        VarInts.writeInt(buffer, itemEntry.getDurabilityCorrection());
    }

    @Override
    public InventorySource readSource(ByteBuf buffer) {
        InventorySource.Type type = InventorySource.Type.byId(VarInts.readUnsignedInt(buffer));
        Integer containerId = readOptional(buffer, null, buf -> (int) buf.readByte());
        InventorySource.Flag flag = readOptional(buffer, null,
                buf -> InventorySource.Flag.values()[VarInts.readUnsignedInt(buf)]);

        return switch (type) {
            case CONTAINER -> InventorySource.fromContainerWindowId(containerId == null ? 0 : containerId);
            case GLOBAL -> InventorySource.fromGlobalInventory();
            case WORLD_INTERACTION -> InventorySource.fromWorldInteraction(
                    flag == null ? InventorySource.Flag.NONE : flag);
            case CREATIVE -> InventorySource.fromCreativeInventory();
            case NON_IMPLEMENTED_TODO -> InventorySource.fromNonImplementedTodo(containerId == null ? 0 : containerId);
            case UNTRACKED_INTERACTION_UI -> InventorySource.fromUntrackedInteractionUI(containerId == null ? 0 : containerId);
            default -> InventorySource.fromInvalid();
        };
    }

    @Override
    public void writeSource(ByteBuf buffer, InventorySource source) {
        requireNonNull(source, "InventorySource was null");
        VarInts.writeUnsignedInt(buffer, source.getType().id());

        boolean carriesContainer = source.getType() == InventorySource.Type.CONTAINER
                || source.getType() == InventorySource.Type.NON_IMPLEMENTED_TODO
                || source.getType() == InventorySource.Type.UNTRACKED_INTERACTION_UI;
        writeOptional(buffer, ignored -> carriesContainer, source, (buf, value) ->
                buf.writeByte(value.getContainerId()));

        boolean carriesFlag = source.getType() == InventorySource.Type.WORLD_INTERACTION;
        writeOptional(buffer, ignored -> carriesFlag, source, (buf, value) ->
                VarInts.writeUnsignedInt(buf, value.getFlag().ordinal()));
    }
}
