package dev.onistone.onilink.virtual;

import dev.onistone.onilink.backend.ProxyConnection;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player-scoped visual block overrides. A position is eligible only after its authoritative block
 * definition has been observed from BDS, which makes restoration deterministic rather than guessed.
 */
public final class FakeBlockService implements AutoCloseable {
    private final int maximumPerPlayer;
    private final int maximumObserved;
    private final Map<String, PlayerBlocks> players = new LinkedHashMap<>();

    public FakeBlockService(int maximumPerPlayer) {
        if (maximumPerPlayer < 1) throw new IllegalArgumentException("fake block limit must be positive");
        this.maximumPerPlayer = maximumPerPlayer;
        this.maximumObserved = Math.max(1_024, Math.min(100_000, maximumPerPlayer * 4));
    }

    public synchronized Map<String, Object> set(
            ProxyConnection connection, Vector3i position, String identifier) {
        PlayerBlocks state = state(connection);
        BlockKey key = new BlockKey(connection.playerDimensionId(), position);
        BlockDefinition authoritative = state.observed.get(key);
        if (authoritative == null) {
            throw new UnsupportedOperationException(
                    "the authoritative block at this position has not been observed; refusing an unrestorable fake block");
        }
        BlockDefinition fake = definition(connection, identifier);
        if (!state.fakes.containsKey(key) && state.fakes.size() >= maximumPerPlayer) {
            throw new IllegalArgumentException("fake block limit reached");
        }
        FakeBlock value = new FakeBlock(key, fake, authoritative, identifier);
        UpdateBlockPacket packet = packet(value.key.position, fake);
        String encoding = VirtualPacketEncoding.validate(connection, List.of(packet));
        if (!encoding.isBlank()) throw new UnsupportedOperationException(encoding);
        state.fakes.put(key, value);
        VirtualPacketEncoding.send(connection, List.of(packet));
        return view(value);
    }

    public synchronized int setRegion(
            ProxyConnection connection, Vector3i first, Vector3i second, String identifier) {
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX());
        int maxY = Math.max(first.getY(), second.getY());
        int maxZ = Math.max(first.getZ(), second.getZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume < 1 || volume > maximumPerPlayer) {
            throw new IllegalArgumentException("fake block region exceeds the per-player limit");
        }
        PlayerBlocks state = state(connection);
        List<Vector3i> positions = new ArrayList<>((int) volume);
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            Vector3i position = Vector3i.from(x, y, z);
            if (!state.observed.containsKey(new BlockKey(connection.playerDimensionId(), position))) {
                throw new UnsupportedOperationException(
                        "every position in a fake region must have an observed authoritative block");
            }
            positions.add(position);
        }
        if (state.fakes.size() + positions.stream()
                .filter(position -> !state.fakes.containsKey(new BlockKey(connection.playerDimensionId(), position)))
                .count() > maximumPerPlayer) {
            throw new IllegalArgumentException("fake block region would exceed the per-player limit");
        }
        for (Vector3i position : positions) set(connection, position, identifier);
        return positions.size();
    }

    public synchronized boolean restore(ProxyConnection connection, Vector3i position) {
        PlayerBlocks state = players.get(connection.forwardingSessionId());
        if (state == null) return false;
        FakeBlock removed = state.fakes.remove(new BlockKey(connection.playerDimensionId(), position));
        if (removed == null) return false;
        UpdateBlockPacket packet = packet(position, removed.authoritative);
        if (!VirtualPacketEncoding.validate(connection, List.of(packet)).isBlank()) {
            state.fakes.put(removed.key, removed);
            throw new UnsupportedOperationException("authoritative restoration packet cannot encode for this client");
        }
        VirtualPacketEncoding.send(connection, List.of(packet));
        return true;
    }

    public synchronized int clear(ProxyConnection connection, boolean sendRestoration) {
        PlayerBlocks state = players.remove(connection.forwardingSessionId());
        if (state == null) return 0;
        if (sendRestoration && connection.client().isConnected()) {
            List<BedrockPacket> packets = state.fakes.values().stream()
                    .map(fake -> (BedrockPacket) packet(fake.key.position, fake.authoritative)).toList();
            for (int offset = 0; offset < packets.size(); offset += 256) {
                List<BedrockPacket> batch = packets.subList(offset, Math.min(offset + 256, packets.size()));
                if (VirtualPacketEncoding.validate(connection, batch).isBlank()) {
                    VirtualPacketEncoding.send(connection, batch);
                }
            }
        }
        return state.fakes.size();
    }

    /** Called after an authoritative packet was sent; returns overrides that must immediately follow it. */
    public synchronized List<BedrockPacket> afterAuthoritative(
            ProxyConnection connection, BedrockPacket translated) {
        PlayerBlocks state = state(connection);
        if (translated instanceof UpdateBlockPacket update) {
            BlockKey key = new BlockKey(connection.playerDimensionId(), update.getBlockPosition());
            state.observed.put(key, update.getDefinition());
            trimObserved(state);
            FakeBlock fake = state.fakes.get(key);
            if (fake == null) return List.of();
            FakeBlock refreshed = new FakeBlock(key, fake.visual, update.getDefinition(), fake.identifier);
            state.fakes.put(key, refreshed);
            return List.of(packet(key.position, fake.visual));
        }
        if (translated instanceof LevelChunkPacket chunk) {
            int dimension = chunk.getDimension();
            if (dimension == 0 && connection.playerDimensionId() != 0) dimension = connection.playerDimensionId();
            List<BedrockPacket> result = new ArrayList<>();
            for (FakeBlock fake : state.fakes.values()) {
                if (fake.key.dimension != dimension
                        || Math.floorDiv(fake.key.position.getX(), 16) != chunk.getChunkX()
                        || Math.floorDiv(fake.key.position.getZ(), 16) != chunk.getChunkZ()) continue;
                result.add(packet(fake.key.position, fake.visual));
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    public synchronized List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, PlayerBlocks> player : players.entrySet()) {
            for (FakeBlock block : player.getValue().fakes.values()) {
                Map<String, Object> item = new LinkedHashMap<>(view(block));
                item.put("connectionId", player.getKey());
                result.add(Map.copyOf(item));
            }
        }
        return List.copyOf(result);
    }

    private PlayerBlocks state(ProxyConnection connection) {
        return players.computeIfAbsent(connection.forwardingSessionId(), ignored -> new PlayerBlocks());
    }

    private void trimObserved(PlayerBlocks state) {
        Iterator<BlockKey> iterator = state.observed.keySet().iterator();
        while (state.observed.size() > maximumObserved && iterator.hasNext()) {
            BlockKey key = iterator.next();
            if (!state.fakes.containsKey(key)) iterator.remove();
        }
    }

    private static BlockDefinition definition(ProxyConnection connection, String identifier) {
        if (identifier == null || !identifier.matches("[a-z0-9_.:-]{3,128}")) {
            throw new IllegalArgumentException("block identifier must be a safe namespaced identifier");
        }
        BlockDefinition definition;
        try {
            definition = connection.client().getPeer().getCodecHelper().getBlockDefinitions()
                    .getDefinition(identifier);
        } catch (UnsupportedOperationException exception) {
            throw new UnsupportedOperationException("active client block registry cannot resolve identifiers");
        }
        if (definition == null) throw new UnsupportedOperationException(
                "client block registry does not contain " + identifier);
        return definition;
    }

    private static UpdateBlockPacket packet(Vector3i position, BlockDefinition definition) {
        UpdateBlockPacket packet = new UpdateBlockPacket();
        packet.setBlockPosition(position);
        packet.setDefinition(definition);
        packet.setDataLayer(0);
        packet.getFlags().addAll(UpdateBlockPacket.FLAG_ALL_PRIORITY);
        return packet;
    }

    private static Map<String, Object> view(FakeBlock block) {
        return Map.of("dimension", block.key.dimension, "x", block.key.position.getX(),
                "y", block.key.position.getY(), "z", block.key.position.getZ(),
                "identifier", block.identifier);
    }

    @Override
    public synchronized void close() {
        players.clear();
    }

    private static final class PlayerBlocks {
        private final LinkedHashMap<BlockKey, BlockDefinition> observed = new LinkedHashMap<>();
        private final LinkedHashMap<BlockKey, FakeBlock> fakes = new LinkedHashMap<>();
    }

    private record BlockKey(int dimension, Vector3i position) {
    }

    private record FakeBlock(BlockKey key, BlockDefinition visual, BlockDefinition authoritative,
                             String identifier) {
    }
}
