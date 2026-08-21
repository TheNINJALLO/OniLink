package dev.onistone.onilink.virtual;

import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ControlRole;
import dev.onistone.onilink.control.ValidatedActionPayload;
import dev.onistone.onilink.registry.EntityPalettes;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-only entity namespace. Synthetic IDs are never registered with or forwarded to BDS. */
public final class PrivateEntityService implements AutoCloseable {
    @FunctionalInterface
    public interface InteractionHandler {
        void handle(ProxyConnection connection, PrivateEntity entity, InteractPacket.Action interaction);
    }

    private final int maximumPerPlayer;
    private final InteractionHandler interactions;
    private final Map<String, Map<String, PrivateEntity>> entities = new ConcurrentHashMap<>();

    public PrivateEntityService(int maximumPerPlayer, InteractionHandler interactions) {
        if (maximumPerPlayer < 1) throw new IllegalArgumentException("private entity limit must be positive");
        this.maximumPerPlayer = maximumPerPlayer;
        this.interactions = interactions == null ? (connection, entity, interaction) -> { } : interactions;
    }

    public synchronized Map<String, Object> spawn(
            ProxyConnection connection, String id, String identifier, Vector3f position,
            Vector3f rotation, String name, float scale, Instant expiresAt,
            String actor, ControlRole actorRole,
            ActionType interactionAction, ValidatedActionPayload interactionPayload) {
        cleanupExpired(connection);
        Map<String, PrivateEntity> scoped = entities.computeIfAbsent(
                connection.forwardingSessionId(), ignored -> new LinkedHashMap<>());
        if (scoped.containsKey(id)) throw new IllegalArgumentException("private entity ID already exists");
        if (scoped.size() >= maximumPerPlayer) throw new IllegalArgumentException("private entity limit reached");
        int entityType = entityType(connection, identifier);
        if (entityType < 1) throw new UnsupportedOperationException(
                "client entity registry does not contain " + identifier);
        long entityId = connection.allocateSyntheticClientEntityId();
        PrivateEntity entity = new PrivateEntity(id, entityId, identifier, position, rotation, name,
                scale, expiresAt, actor, actorRole, interactionAction, interactionPayload);
        AddEntityPacket packet = addPacket(entity, entityType);
        String encoding = VirtualPacketEncoding.validate(connection, List.of(packet));
        if (!encoding.isBlank()) throw new UnsupportedOperationException(encoding);
        VirtualPacketEncoding.send(connection, List.of(packet));
        scoped.put(id, entity);
        return view(entity);
    }

    public synchronized Map<String, Object> update(
            ProxyConnection connection, String id, String name, Float scale) {
        PrivateEntity current = required(connection, id);
        PrivateEntity updated = new PrivateEntity(current.id(), current.runtimeEntityId(), current.identifier(),
                current.position(), current.rotation(), name == null ? current.name() : name,
                scale == null ? current.scale() : scale, current.expiresAt(), current.actor(), current.actorRole(),
                current.interactionAction(),
                current.interactionPayload());
        SetEntityDataPacket packet = metadataPacket(updated);
        String encoding = VirtualPacketEncoding.validate(connection, List.of(packet));
        if (!encoding.isBlank()) throw new UnsupportedOperationException(encoding);
        VirtualPacketEncoding.send(connection, List.of(packet));
        entities.get(connection.forwardingSessionId()).put(id, updated);
        return view(updated);
    }

    public synchronized Map<String, Object> move(
            ProxyConnection connection, String id, Vector3f position, Vector3f rotation) {
        PrivateEntity current = required(connection, id);
        PrivateEntity updated = new PrivateEntity(current.id(), current.runtimeEntityId(), current.identifier(),
                position, rotation, current.name(), current.scale(), current.expiresAt(),
                current.actor(), current.actorRole(),
                current.interactionAction(), current.interactionPayload());
        MoveEntityAbsolutePacket packet = new MoveEntityAbsolutePacket();
        packet.setRuntimeEntityId(updated.runtimeEntityId());
        packet.setPosition(position);
        packet.setRotation(rotation);
        packet.setTeleported(true);
        packet.setForceMove(true);
        packet.setOnGround(true);
        String encoding = VirtualPacketEncoding.validate(connection, List.of(packet));
        if (!encoding.isBlank()) throw new UnsupportedOperationException(encoding);
        VirtualPacketEncoding.send(connection, List.of(packet));
        entities.get(connection.forwardingSessionId()).put(id, updated);
        return view(updated);
    }

    public synchronized boolean remove(ProxyConnection connection, String id) {
        Map<String, PrivateEntity> scoped = entities.get(connection.forwardingSessionId());
        PrivateEntity removed = scoped == null ? null : scoped.remove(id);
        if (removed == null) return false;
        sendRemove(connection, removed);
        if (scoped.isEmpty()) entities.remove(connection.forwardingSessionId());
        return true;
    }

    public synchronized int clear(ProxyConnection connection) {
        Map<String, PrivateEntity> scoped = entities.remove(connection.forwardingSessionId());
        if (scoped == null) return 0;
        for (PrivateEntity entity : scoped.values()) sendRemove(connection, entity);
        return scoped.size();
    }

    public synchronized boolean intercept(ProxyConnection connection, BedrockPacket packet) {
        cleanupExpired(connection);
        if (!(packet instanceof InteractPacket interact)) return false;
        Map<String, PrivateEntity> scoped = entities.get(connection.forwardingSessionId());
        if (scoped == null) return false;
        for (PrivateEntity entity : scoped.values()) {
            if (entity.runtimeEntityId() != interact.getRuntimeEntityId()) continue;
            if (entity.interactionAction() != null) interactions.handle(connection, entity, interact.getAction());
            return true;
        }
        return false;
    }

    public synchronized List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, PrivateEntity>> session : entities.entrySet()) {
            for (PrivateEntity entity : session.getValue().values()) {
                Map<String, Object> view = new LinkedHashMap<>(view(entity));
                view.put("connectionId", session.getKey());
                result.add(Map.copyOf(view));
            }
        }
        return List.copyOf(result);
    }

    private void cleanupExpired(ProxyConnection connection) {
        Map<String, PrivateEntity> scoped = entities.get(connection.forwardingSessionId());
        if (scoped == null) return;
        List<String> expired = scoped.values().stream().filter(entity -> !entity.expiresAt().isAfter(Instant.now()))
                .map(PrivateEntity::id).toList();
        for (String id : expired) remove(connection, id);
    }

    private PrivateEntity required(ProxyConnection connection, String id) {
        cleanupExpired(connection);
        Map<String, PrivateEntity> scoped = entities.get(connection.forwardingSessionId());
        PrivateEntity entity = scoped == null ? null : scoped.get(id);
        if (entity == null) throw new IllegalArgumentException("private entity does not exist");
        return entity;
    }

    private static int entityType(ProxyConnection connection, String identifier) {
        NbtMap identifiers = connection.crossBackendPalette().clientEntityIdentifiers();
        for (NbtMap entry : EntityPalettes.idList(identifiers)) {
            if (identifier.equals(EntityPalettes.entityId(entry))) return entry.getInt("rid", 0);
        }
        return 0;
    }

    private static AddEntityPacket addPacket(PrivateEntity entity, int entityType) {
        AddEntityPacket packet = new AddEntityPacket();
        packet.setUniqueEntityId(entity.runtimeEntityId());
        packet.setRuntimeEntityId(entity.runtimeEntityId());
        packet.setIdentifier(entity.identifier());
        packet.setEntityType(entityType);
        packet.setPosition(entity.position());
        packet.setMotion(Vector3f.ZERO);
        packet.setRotation(Vector2f.from(entity.rotation().getX(), entity.rotation().getY()));
        packet.setHeadRotation(entity.rotation().getY());
        packet.setBodyRotation(entity.rotation().getY());
        applyMetadata(packet.getMetadata(), entity);
        return packet;
    }

    private static SetEntityDataPacket metadataPacket(PrivateEntity entity) {
        SetEntityDataPacket packet = new SetEntityDataPacket();
        packet.setRuntimeEntityId(entity.runtimeEntityId());
        applyMetadata(packet.getMetadata(), entity);
        return packet;
    }

    private static void applyMetadata(
            org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap metadata, PrivateEntity entity) {
        EnumMap<EntityFlag, Boolean> flags = new EnumMap<>(EntityFlag.class);
        flags.put(EntityFlag.NO_AI, true);
        flags.put(EntityFlag.SILENT, true);
        flags.put(EntityFlag.HAS_GRAVITY, false);
        flags.put(EntityFlag.INVISIBLE,
                "minecraft:armor_stand".equals(entity.identifier()) && entity.scale() <= 0.01f);
        flags.put(EntityFlag.CAN_SHOW_NAME, !entity.name().isBlank());
        flags.put(EntityFlag.ALWAYS_SHOW_NAME, !entity.name().isBlank());
        metadata.putType(EntityDataTypes.FLAGS, flags);
        metadata.putType(EntityDataTypes.NAME, entity.name());
        metadata.putType(EntityDataTypes.NAMETAG_ALWAYS_SHOW, (byte) (entity.name().isBlank() ? 0 : 1));
        metadata.putType(EntityDataTypes.SCALE, entity.scale());
    }

    private static void sendRemove(ProxyConnection connection, PrivateEntity entity) {
        RemoveEntityPacket packet = new RemoveEntityPacket();
        packet.setUniqueEntityId(entity.runtimeEntityId());
        if (VirtualPacketEncoding.validate(connection, List.of(packet)).isBlank()) {
            VirtualPacketEncoding.send(connection, List.of(packet));
        }
    }

    private static Map<String, Object> view(PrivateEntity entity) {
        return Map.of(
                "id", entity.id(),
                "identifier", entity.identifier(),
                "runtimeEntityId", entity.runtimeEntityId(),
                "position", Map.of("x", entity.position().getX(), "y", entity.position().getY(),
                        "z", entity.position().getZ()),
                "expiresAt", entity.expiresAt().toString(),
                "interactive", entity.interactionAction() != null);
    }

    @Override
    public synchronized void close() {
        entities.clear();
    }
}
