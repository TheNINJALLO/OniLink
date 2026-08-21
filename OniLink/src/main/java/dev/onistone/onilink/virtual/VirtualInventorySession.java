package dev.onistone.onilink.virtual;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import dev.onistone.onilink.control.ControlRole;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class VirtualInventorySession {
    private final String sessionId;
    private final String connectionId;
    private final byte containerId;
    private final VirtualContainerDefinition definition;
    private final List<ItemData> contents;
    private final Map<Integer, Integer> stackIdsBySlot;
    private final Instant expiresAt;
    private final String actor;
    private final ControlRole actorRole;
    private final LinkedHashSet<Integer> requestIds = new LinkedHashSet<>();
    private final LinkedHashMap<String, BedrockPacket> deferredAuthoritativePackets = new LinkedHashMap<>();

    VirtualInventorySession(String sessionId, String connectionId, byte containerId,
                            VirtualContainerDefinition definition, List<ItemData> contents,
                            Map<Integer, Integer> stackIdsBySlot, Instant expiresAt,
                            String actor, ControlRole actorRole) {
        this.sessionId = sessionId;
        this.connectionId = connectionId;
        this.containerId = containerId;
        this.definition = definition;
        this.contents = List.copyOf(contents);
        this.stackIdsBySlot = Map.copyOf(stackIdsBySlot);
        this.expiresAt = expiresAt;
        this.actor = actor;
        this.actorRole = actorRole;
    }

    public String sessionId() { return sessionId; }
    public String connectionId() { return connectionId; }
    public byte containerId() { return containerId; }
    public VirtualContainerDefinition definition() { return definition; }
    public List<ItemData> contents() { return contents; }
    public Instant expiresAt() { return expiresAt; }
    public String actor() { return actor; }
    public ControlRole actorRole() { return actorRole; }
    public boolean expired() { return !expiresAt.isAfter(Instant.now()); }
    public Integer stackId(int slot) { return stackIdsBySlot.get(slot); }

    synchronized boolean acceptRequestId(int requestId) {
        if (requestId <= 0 || !requestIds.add(requestId)) return false;
        while (requestIds.size() > 256) requestIds.remove(requestIds.getFirst());
        return true;
    }

    synchronized void defer(BedrockPacket packet) {
        String key = packet.getClass().getName() + ':' + deferredAuthoritativePackets.size();
        if (deferredAuthoritativePackets.size() >= 64) deferredAuthoritativePackets.remove(deferredAuthoritativePackets.firstEntry().getKey());
        deferredAuthoritativePackets.put(key, packet);
    }

    synchronized List<BedrockPacket> drainDeferred() {
        List<BedrockPacket> packets = List.copyOf(deferredAuthoritativePackets.values());
        deferredAuthoritativePackets.clear();
        return packets;
    }
}
