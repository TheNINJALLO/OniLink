package dev.onistone.onilink.virtual;

import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.control.ControlActionRequest;
import dev.onistone.onilink.protocol.PacketMonitor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ConsumeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DestroyAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TransferItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Player-scoped virtual container boundary. All client mutations are rejected and consumed, so no
 * virtual stack can enter an authoritative inventory and no real stack can be lost into a menu.
 */
public final class VirtualInventoryService implements AutoCloseable {
    @FunctionalInterface
    public interface InteractionHandler {
        void handle(ProxyConnection connection, VirtualInventorySession session,
                    VirtualSlot slot, VirtualInventoryAction interaction);
    }

    private final int maximumSessions;
    private final VirtualStackIdAllocator stackIds = new VirtualStackIdAllocator();
    private final AtomicInteger containerIds = new AtomicInteger(1);
    private final Map<String, VirtualInventorySession> sessions = new ConcurrentHashMap<>();
    private final InteractionHandler interactions;

    public VirtualInventoryService(int maximumSessions, InteractionHandler interactions) {
        if (maximumSessions < 1) throw new IllegalArgumentException("virtual inventory limit must be positive");
        this.maximumSessions = maximumSessions;
        this.interactions = interactions == null ? (connection, session, slot, interaction) -> { } : interactions;
    }

    public VirtualInventoryResult open(
            ProxyConnection connection, VirtualContainerDefinition definition, ControlActionRequest creator) {
        if (connection == null || definition == null || creator == null) {
            throw new IllegalArgumentException("connection, menu, and creator request are required");
        }
        if (!connection.hasClientJoinedWorld() || connection.isSwitchingBackend()) {
            return new VirtualInventoryResult(VirtualInventoryResult.Status.REJECTED,
                    "Player must be in a stable world before a virtual inventory opens", Map.of());
        }
        if (!sessions.containsKey(connection.forwardingSessionId()) && sessions.size() >= maximumSessions) {
            return new VirtualInventoryResult(VirtualInventoryResult.Status.REJECTED,
                    "Virtual inventory session limit reached", Map.of());
        }
        close(connection, "replaced", true);
        byte containerId = nextContainerId();
        List<ItemData> contents = new ArrayList<>(java.util.Collections.nCopies(definition.size(), ItemData.AIR));
        Map<Integer, Integer> networkIds = new LinkedHashMap<>();
        try {
            for (VirtualSlot slot : definition.slots()) {
                int networkId = stackIds.next();
                contents.set(slot.index(), item(connection, slot.item(), networkId));
                networkIds.put(slot.index(), networkId);
            }
            VirtualInventorySession session = new VirtualInventorySession(
                    UUID.randomUUID().toString(), connection.forwardingSessionId(), containerId,
                    definition, contents, networkIds, Instant.now().plus(definition.timeout()),
                    creator.actorAccountId(), creator.actorRole());
            List<BedrockPacket> packets = openPackets(connection, session);
            String unsupported = validateEncoding(connection, packets);
            if (!unsupported.isBlank()) {
                return new VirtualInventoryResult(VirtualInventoryResult.Status.UNSUPPORTED, unsupported, Map.of());
            }
            sessions.put(connection.forwardingSessionId(), session);
            send(connection, packets);
            return new VirtualInventoryResult(VirtualInventoryResult.Status.CONFIRMED, "", Map.of(
                    "sessionId", session.sessionId(), "containerId", Byte.toUnsignedInt(containerId),
                    "size", definition.size(), "expiresAt", session.expiresAt().toString(),
                    "interactionPolicy", "client mutations are rejected and consumed"));
        } catch (IllegalArgumentException exception) {
            return new VirtualInventoryResult(VirtualInventoryResult.Status.REJECTED, exception.getMessage(), Map.of());
        }
    }

    public boolean interceptClient(ProxyConnection connection, BedrockPacket packet) {
        VirtualInventorySession session = active(connection);
        if (session == null) return false;
        if (session.expired()) {
            close(connection, "expired", true);
            return false;
        }
        if (packet instanceof ContainerClosePacket close
                && close.getId() == session.containerId()) {
            close(connection, "client close", false);
            return true;
        }
        if (packet instanceof ItemStackRequestPacket requests) {
            ItemStackResponsePacket response = new ItemStackResponsePacket();
            for (ItemStackRequest request : requests.getRequests()) {
                boolean fresh = session.acceptRequestId(request.getRequestId());
                if (fresh) invokeFirstValidInteraction(connection, session, request);
                response.getEntries().add(new ItemStackResponse(
                        fresh ? ItemStackResponseStatus.ACTION_REQUEST_NOT_ALLOWED
                                : ItemStackResponseStatus.REQUEST_ALREADY_IN_PROGRESS,
                        request.getRequestId(), List.of()));
            }
            if (!response.getEntries().isEmpty()) send(connection, List.of(response, content(session)));
            return true;
        }
        if (packet instanceof InventoryTransactionPacket || packet instanceof MobEquipmentPacket
                || packet instanceof MobArmorEquipmentPacket) {
            send(connection, List.of(content(session)));
            return true;
        }
        return false;
    }

    public boolean interceptBackend(ProxyConnection connection, BedrockPacket packet) {
        VirtualInventorySession session = active(connection);
        if (session == null) return false;
        if (packet instanceof InventoryContentPacket || packet instanceof InventorySlotPacket
                || packet instanceof ItemStackResponsePacket || packet instanceof ContainerClosePacket) {
            session.defer(packet.clone());
            return true;
        }
        return false;
    }

    public VirtualInventoryResult close(ProxyConnection connection, String reason, boolean notifyClient) {
        if (connection == null) return new VirtualInventoryResult(VirtualInventoryResult.Status.CLOSED, "", Map.of());
        VirtualInventorySession session = sessions.remove(connection.forwardingSessionId());
        if (session == null) return new VirtualInventoryResult(VirtualInventoryResult.Status.CLOSED, "", Map.of());
        List<BedrockPacket> packets = new ArrayList<>();
        if (notifyClient && connection.client().isConnected()) {
            ContainerClosePacket close = new ContainerClosePacket();
            close.setId(session.containerId());
            close.setServerInitiated(true);
            close.setType(ContainerType.CONTAINER);
            packets.add(close);
        }
        packets.addAll(session.drainDeferred());
        if (!packets.isEmpty() && connection.client().isConnected()) send(connection, packets);
        return new VirtualInventoryResult(VirtualInventoryResult.Status.CLOSED,
                reason == null ? "" : reason, Map.of("sessionId", session.sessionId()));
    }

    public List<Map<String, Object>> snapshot() {
        return sessions.values().stream().map(session -> Map.<String, Object>of(
                "sessionId", session.sessionId(), "connectionId", session.connectionId(),
                "title", session.definition().title(), "size", session.definition().size(),
                "page", session.definition().page(), "pageCount", session.definition().pageCount(),
                "expiresAt", session.expiresAt().toString())).toList();
    }

    public int size() { return sessions.size(); }

    private VirtualInventorySession active(ProxyConnection connection) {
        return connection == null ? null : sessions.get(connection.forwardingSessionId());
    }

    private void invokeFirstValidInteraction(
            ProxyConnection connection, VirtualInventorySession session, ItemStackRequest request) {
        for (ItemStackRequestAction action : request.getActions()) {
            ItemStackRequestSlotData source = source(action);
            if (source == null || !virtualContainer(source, session)) continue;
            int slotIndex = source.getSlot();
            Integer expectedStackId = session.stackId(slotIndex);
            if (expectedStackId == null || expectedStackId != source.getStackNetworkId()) return;
            VirtualSlot slot = session.definition().slots().stream()
                    .filter(candidate -> candidate.index() == slotIndex).findFirst().orElse(null);
            if (slot == null || slot.disabled() || slot.item().action() == null) return;
            interactions.handle(connection, session, slot,
                    new VirtualInventoryAction(session.sessionId(), slotIndex, request.getRequestId(), Instant.now()));
            return;
        }
    }

    private static ItemStackRequestSlotData source(ItemStackRequestAction action) {
        if (action instanceof TransferItemStackRequestAction transfer) return transfer.getSource();
        if (action instanceof SwapAction swap) return swap.getSource();
        if (action instanceof DropAction drop) return drop.getSource();
        if (action instanceof DestroyAction destroy) return destroy.getSource();
        if (action instanceof ConsumeAction consume) return consume.getSource();
        return null;
    }

    private static boolean virtualContainer(ItemStackRequestSlotData slot, VirtualInventorySession session) {
        if (slot.getContainer() == ContainerSlotType.LEVEL_ENTITY) return true;
        FullContainerName full = slot.getContainerName();
        return full != null && full.getContainer() == ContainerSlotType.DYNAMIC_CONTAINER
                && (full.getDynamicId() == null || full.getDynamicId() == Byte.toUnsignedInt(session.containerId()));
    }

    private static List<BedrockPacket> openPackets(
            ProxyConnection connection, VirtualInventorySession session) {
        Vector3f position = connection.saneJoinPosition();
        Vector3i block = position == null ? Vector3i.ZERO : Vector3i.from(
                (int) Math.floor(position.getX()), (int) Math.floor(position.getY()), (int) Math.floor(position.getZ()));
        ContainerOpenPacket open = new ContainerOpenPacket();
        open.setId(session.containerId());
        open.setType(ContainerType.CONTAINER);
        open.setBlockPosition(block);
        open.setUniqueEntityId(-1);
        return List.of(open, content(session));
    }

    private static InventoryContentPacket content(VirtualInventorySession session) {
        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(Byte.toUnsignedInt(session.containerId()));
        content.setContents(session.contents());
        content.setContainerNameData(new FullContainerName(
                ContainerSlotType.DYNAMIC_CONTAINER, Byte.toUnsignedInt(session.containerId())));
        content.setDynamicContainerSize(session.definition().size());
        content.setStorageItem(ItemData.AIR);
        return content;
    }

    private ItemData item(ProxyConnection connection, VirtualItem item, int networkId) {
        ItemDefinition definition = connection.client().getPeer().getCodecHelper()
                .getItemDefinitions().getDefinition(item.identifier());
        if (definition == null || definition == ItemDefinition.AIR && !"minecraft:air".equals(item.identifier())) {
            throw new IllegalArgumentException("client item palette does not contain " + item.identifier());
        }
        NbtMap tag = null;
        if (!item.displayName().isBlank() || !item.lore().isEmpty()) {
            var display = NbtMap.builder();
            if (!item.displayName().isBlank()) display.putString("Name", item.displayName());
            if (!item.lore().isEmpty()) display.putList("Lore", NbtType.STRING, item.lore());
            tag = NbtMap.builder().putCompound("display", display.build()).build();
        }
        return ItemData.builder().definition(definition).count(item.count()).damage(item.damage())
                .tag(tag).usingNetId(true).netId(networkId).build();
    }

    private static String validateEncoding(ProxyConnection connection, List<BedrockPacket> packets) {
        BedrockCodec codec = connection.sessionProfile().clientCodec();
        BedrockCodecHelper helper = codec.createHelper();
        helper.setItemDefinitions(connection.client().getPeer().getCodecHelper().getItemDefinitions());
        helper.setBlockDefinitions(connection.client().getPeer().getCodecHelper().getBlockDefinitions());
        for (BedrockPacket packet : packets) {
            if (codec.getPacketDefinition(packet.getClass()) == null) {
                return packet.getClass().getSimpleName() + " is unavailable for client protocol " + codec.getProtocolVersion();
            }
            ByteBuf buffer = Unpooled.buffer();
            try {
                codec.tryEncode(helper, buffer, packet);
                if (buffer.readableBytes() > 256 * 1024) return "virtual inventory packet exceeds the encoded size limit";
            } catch (Exception exception) {
                return packet.getClass().getSimpleName() + " cannot encode for client protocol " + codec.getProtocolVersion();
            } finally {
                buffer.release();
            }
        }
        return "";
    }

    private static void send(ProxyConnection connection, List<? extends BedrockPacket> packets) {
        for (BedrockPacket packet : packets) {
            connection.client().sendPacket(packet);
            connection.observePacket(PacketMonitor.Direction.CLIENTBOUND, packet, packet,
                    PacketMonitor.Action.ONIVIRTUAL_INJECTED);
        }
    }

    private byte nextContainerId() {
        int value = containerIds.updateAndGet(current -> current >= 100 ? 1 : current + 1);
        return (byte) value;
    }

    @Override
    public void close() {
        sessions.clear();
    }
}
