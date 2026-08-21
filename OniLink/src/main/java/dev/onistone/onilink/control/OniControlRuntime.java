package dev.onistone.onilink.control;

import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.config.OniControlConfig;
import dev.onistone.onilink.control.wire.ControlClientManager;
import dev.onistone.onilink.control.wire.ControlResponseEnvelope;
import dev.onistone.onilink.packet.OniPacketFactory;
import dev.onistone.onilink.packet.OniPacketRuleEngine;
import dev.onistone.onilink.packet.PacketBuildResult;
import dev.onistone.onilink.packet.PacketContext;
import dev.onistone.onilink.packet.PacketRule;
import dev.onistone.onilink.packet.PacketRuleDirection;
import dev.onistone.onilink.packet.PacketRuleStore;
import dev.onistone.onilink.protocol.PacketMonitor;
import dev.onistone.onilink.session.ConnectedPlayerRegistry;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.Set;
import dev.onistone.onilink.virtual.VirtualContainerDefinition;
import dev.onistone.onilink.virtual.VirtualInventoryAction;
import dev.onistone.onilink.virtual.VirtualInventoryResult;
import dev.onistone.onilink.virtual.VirtualInventoryService;
import dev.onistone.onilink.virtual.VirtualInventorySession;
import dev.onistone.onilink.virtual.VirtualItem;
import dev.onistone.onilink.virtual.VirtualSlot;
import dev.onistone.onilink.virtual.FakeBlockService;
import dev.onistone.onilink.virtual.PrivateEntity;
import dev.onistone.onilink.virtual.PrivateEntityService;
import org.cloudburstmc.math.vector.Vector3i;

/** Shared, optional runtime for OniControl, OniPacket rules, and per-player state. */
public final class OniControlRuntime implements AutoCloseable {
    private static final int MAX_AUDIT_RECORDS = 5_000;
    private final OniControlConfig config;
    private final ConnectedPlayerRegistry players;
    private final PacketMonitor packetMonitor;
    private final String tenantId;
    private final String proxyId;
    private final OniPacketFactory packetFactory = new OniPacketFactory();
    private final OniPacketRuleEngine packetRules;
    private final PacketRuleStore ruleStore;
    private final ControlClientManager bridges;
    private final VirtualInventoryService virtualInventories;
    private final PrivateEntityService privateEntities;
    private final FakeBlockService fakeBlocks;
    private final ProtocolLabService protocolLab;
    private final AtomicInteger nextFormId = new AtomicInteger(0x4f4e0000);
    private final Map<String, Long> bossIds = new ConcurrentHashMap<>();
    private final ArrayDeque<ActionAuditRecord> audit = new ArrayDeque<>();
    private volatile boolean started;

    public OniControlRuntime(
            OniControlConfig config,
            ConnectedPlayerRegistry players,
            PacketMonitor packetMonitor,
            String tenantId,
            String proxyId
    ) {
        if (config == null || players == null) throw new IllegalArgumentException("OniControl config and player registry are required");
        this.config = config;
        this.players = players;
        this.packetMonitor = packetMonitor;
        this.tenantId = safeScope(tenantId, "provider");
        this.proxyId = safeScope(proxyId, "main");
        this.packetRules = new OniPacketRuleEngine(
                config.packetRules().enabled(), config.packetRules().maxRules(),
                config.packetRules().maxInjectedPacketsPerDecision(), packetFactory);
        this.ruleStore = new PacketRuleStore(config.dataDirectory(), config.packetRules().maxRules());
        this.bridges = new ControlClientManager(config);
        this.virtualInventories = new VirtualInventoryService(
                config.virtualization().maxInventorySessions(), this::handleVirtualInteraction);
        this.privateEntities = new PrivateEntityService(
                config.virtualization().maxPrivateEntitiesPerPlayer(), this::handlePrivateEntityInteraction);
        this.fakeBlocks = new FakeBlockService(config.virtualization().maxFakeBlocksPerPlayer());
        this.protocolLab = new ProtocolLabService(config.protocolLab(), players, packetFactory, nextFormId);
    }

    public synchronized void start() throws IOException {
        if (started) return;
        List<PacketRule> persisted = ruleStore.load(tenantId, proxyId);
        packetRules.replaceRules(persisted);
        bridges.start();
        started = true;
        System.out.printf("OniControl: %s; OniPacket rules: %s (%d loaded); OniVirtual: %s; scope=%s/%s.%n",
                config.enabled() ? "enabled" : "disabled",
                packetRules.enabled() ? "enabled" : "disabled", persisted.size(),
                config.virtualization().enabled() ? "enabled" : "disabled", tenantId, proxyId);
    }

    public OniPacketRuleEngine packetRules() { return packetRules; }
    public OniPacketFactory packetFactory() { return packetFactory; }
    public String tenantId() { return tenantId; }
    public String proxyId() { return proxyId; }
    public boolean controlEnabled() { return config.enabled(); }

    public Map<String, Object> protocolLabStatus(String actor) {
        Map<String, Object> status = new LinkedHashMap<>(protocolLab.status(actor));
        status.put("enabled", config.enabled() && Boolean.TRUE.equals(status.get("enabled")));
        return Map.copyOf(status);
    }

    public Map<String, Object> protocolLabStart(String actor) {
        requireControlEnabled();
        return protocolLab.start(actor);
    }

    public Map<String, Object> protocolLabStop(String actor) {
        return protocolLab.stop(actor);
    }

    public Map<String, Object> protocolLabValidate(String actor, Map<String, String> values, boolean send) {
        requireControlEnabled();
        return protocolLab.validate(actor, values, send);
    }
    public boolean virtualizationEnabled() { return config.virtualization().enabled(); }

    public List<Map<String, Object>> capabilities() {
        return bridges.capabilities();
    }

    private void requireControlEnabled() {
        if (!config.enabled()) throw new IllegalStateException("OniControl is disabled");
    }

    public List<Map<String, Object>> actionCapabilities(ResolvedTarget target) {
        dev.onistone.onilink.control.wire.BridgeCapabilityDocument bridge =
                target == null ? null : bridges.capability(target.backend());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ActionType action : ActionType.values()) {
            boolean supported;
            String reason = "";
            if (!config.enabled()) {
                supported = false;
                reason = "OniControl is disabled";
            } else if (action.executionPlane() == ExecutionPlane.CLIENT_ONLY) {
                supported = packetFactory.hasReviewedClientImplementation(action);
                if (!supported) reason = "No reviewed client packet implementation is available";
            } else if (action.executionPlane() == ExecutionPlane.BACKEND_AUTHORITATIVE) {
                supported = bridge != null && bridge.supports(action, 1);
                if (!supported) reason = bridge == null
                        ? "The active backend has not supplied authenticated capabilities"
                        : "The active OniBridge does not advertise this action";
            } else {
                supported = config.virtualization().enabled()
                        && switch (action) {
                    case OPEN_VIRTUAL_INVENTORY, CLOSE_VIRTUAL_INVENTORY,
                            SPAWN_PRIVATE_ENTITY, UPDATE_PRIVATE_ENTITY, MOVE_PRIVATE_ENTITY,
                            SET_PRIVATE_ENTITY_METADATA, REMOVE_PRIVATE_ENTITY, CLEAR_PRIVATE_ENTITIES,
                            SPAWN_PRIVATE_NPC, SPAWN_PRIVATE_HOLOGRAM,
                            SET_FAKE_BLOCK, SET_FAKE_BLOCK_REGION, RESTORE_FAKE_BLOCK,
                            CLEAR_FAKE_BLOCKS -> true;
                    default -> false;
                };
                if (!supported) reason = config.virtualization().enabled()
                        ? "No reviewed virtualization implementation is available for this action"
                        : "OniVirtual is disabled";
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", action.name());
            item.put("executionPlane", action.executionPlane().name());
            item.put("minimumRole", action.minimumRole().name());
            item.put("destructive", action.destructive());
            item.put("supported", supported);
            item.put("reason", reason);
            item.put("payloadVersion", 1);
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    public String packetRuleDocument() {
        return ruleStore.encodeDocument(packetRules.rules(), tenantId, proxyId);
    }

    public List<Map<String, Object>> packetRuleStatistics() {
        return packetRules.ruleStatistics();
    }

    public synchronized void replaceRuleDocument(String json) throws IOException {
        replaceRules(ruleStore.parseDocument(json, tenantId, proxyId));
    }

    public synchronized void replaceRules(List<PacketRule> replacement) throws IOException {
        packetRules.replaceRules(replacement);
        try {
            ruleStore.save(tenantId, proxyId, packetRules.rules());
        } catch (IOException | RuntimeException exception) {
            packetRules.replaceRules(ruleStore.load(tenantId, proxyId));
            throw exception;
        }
    }

    public PacketContext context(ProxyConnection connection, PacketRuleDirection direction, PacketOrigin origin) {
        String player = connection.clientLogin().authData().displayName();
        String xuid = connection.clientLogin().authData().xuid();
        String backend = connection.backendName() == null ? "" : connection.backendName();
        int clientProtocol = connection.sessionProfile().clientCodec().getProtocolVersion();
        int backendProtocol = connection.sessionProfile().backendCodec().getProtocolVersion();
        String phase = !connection.hasClientJoinedWorld() ? "JOINING"
                : connection.isSwitchingBackend() ? "TRANSFERRING" : "PLAYING";
        return new PacketContext(player, xuid, connection.forwardingSessionId(), tenantId, proxyId,
                backend, clientProtocol, backendProtocol, direction, origin, phase,
                connection.playerDimensionId(), connection.clientPlayerRuntimeEntityId(),
                connection.backendPlayerRuntimeEntityId(), connection.hasClientJoinedWorld(),
                connection.isSwitchingBackend());
    }

    /** Resolves and freezes the authenticated identity before an action can be queued. */
    public ResolvedTarget resolve(TargetSelector selector) {
        if (selector == null) throw new IllegalArgumentException("target selector is required");
        List<ProxyConnection> matches = new ArrayList<>();
        if (!selector.xuid().isBlank()) players.findByXuid(selector.xuid()).ifPresent(matches::add);
        if (!selector.connectionId().isBlank()) players.findByConnectionId(selector.connectionId()).ifPresent(matches::add);
        if (!selector.displayName().isBlank()) matches.addAll(players.findAllByName(selector.displayName()));
        List<ProxyConnection> distinct = matches.stream().distinct().toList();
        if (distinct.size() != 1) {
            throw new IllegalArgumentException(distinct.isEmpty()
                    ? "target is not an active authenticated connection"
                    : "target selectors do not resolve to one unambiguous authenticated connection");
        }
        ProxyConnection connection = distinct.getFirst();
        String actualXuid = connection.clientLogin().authData().xuid();
        if (!selector.xuid().isBlank() && !selector.xuid().equalsIgnoreCase(actualXuid)) {
            throw new IllegalArgumentException("target XUID does not match the resolved connection");
        }
        if (!selector.connectionId().isBlank()
                && !selector.connectionId().equals(connection.forwardingSessionId())) {
            throw new IllegalArgumentException("target connection ID does not match the resolved connection");
        }
        if (!selector.tenantId().isBlank() && !selector.tenantId().equalsIgnoreCase(tenantId)
                || !selector.proxyId().isBlank() && !selector.proxyId().equalsIgnoreCase(proxyId)) {
            throw new IllegalArgumentException("target is outside the OniControl tenant/proxy scope");
        }
        String backend = connection.backendName() == null ? "" : connection.backendName();
        if (!selector.backend().isBlank() && !selector.backend().equalsIgnoreCase(backend)) {
            throw new IllegalArgumentException("target is not on the requested backend");
        }
        return new ResolvedTarget(actualXuid, connection.forwardingSessionId(),
                connection.clientLogin().authData().displayName(), tenantId, proxyId, backend,
                connection.sessionProfile().clientCodec().getProtocolVersion(),
                connection.sessionProfile().backendCodec().getProtocolVersion(),
                connection.hasClientJoinedWorld(), connection.isSwitchingBackend());
    }

    public CompletableFuture<ControlActionResult> execute(ControlActionRequest request) {
        Instant startedAt = Instant.now();
        try {
            validateRequest(request);
            ResolvedTarget target = resolve(request.target());
            ProxyConnection connection = players.findByConnectionId(target.connectionId())
                    .orElseThrow(() -> new IllegalArgumentException("target disconnected during validation"));
            return switch (request.executionPlane()) {
                case CLIENT_ONLY -> CompletableFuture.completedFuture(executeClient(request, connection, startedAt));
                case BACKEND_AUTHORITATIVE -> executeBackend(request, target, startedAt);
                case VIRTUALIZED -> CompletableFuture.completedFuture(executeVirtual(request, connection, startedAt));
            };
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(finish(request, ActionStatus.REJECTED,
                    exception.getMessage(), Map.of(), startedAt));
        } catch (UnsupportedOperationException exception) {
            return CompletableFuture.completedFuture(finish(request, ActionStatus.UNSUPPORTED,
                    exception.getMessage(), Map.of(), startedAt));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(finish(request, ActionStatus.FAILED,
                    "control action failed: " + exception.getClass().getSimpleName(), Map.of(), startedAt));
        }
    }

    private void validateRequest(ControlActionRequest request) {
        if (request == null) throw new IllegalArgumentException("control request is required");
        if (!started) throw new IllegalArgumentException("OniControl runtime has not started");
        if (!config.enabled()) throw new IllegalArgumentException("OniControl is disabled");
        if (!request.deadline().isAfter(Instant.now())) throw new IllegalArgumentException("control request deadline expired");
        if (!request.actorRole().allows(request.actionType().minimumRole())) {
            throw new IllegalArgumentException("actor role cannot execute " + request.actionType());
        }
        if (!request.tenantId().isBlank() && !request.tenantId().equalsIgnoreCase(tenantId)
                || !request.proxyId().isBlank() && !request.proxyId().equalsIgnoreCase(proxyId)) {
            throw new IllegalArgumentException("control request is outside the runtime tenant/proxy scope");
        }
        if (request.confirmationRequired()) {
            throw new IllegalArgumentException("a confirmed execution token is required for this action");
        }
    }

    private ControlActionResult executeClient(ControlActionRequest request, ProxyConnection connection, Instant startedAt) {
        ActionType action = request.actionType();
        if (action == ActionType.START_PACKET_TRACE) {
            long duration = longValue(request.payload().values(), "durationMillis", 30_000, 1_000, 300_000);
            connection.tracePacketsForMillis(duration);
            return finish(request, ActionStatus.CONFIRMED, "", Map.of("durationMillis", duration), startedAt);
        }
        if (action == ActionType.STOP_PACKET_TRACE) {
            connection.tracePacketsForMillis(0);
            return finish(request, ActionStatus.CONFIRMED, "", Map.of(), startedAt);
        }
        if (action == ActionType.KICK_PLAYER) {
            String reason = text(request.payload().values(), "reason", "Disconnected by an operator", 512);
            connection.client().disconnect(reason);
            return finish(request, ActionStatus.CONFIRMED, "", Map.of("disconnected", true), startedAt);
        }
        long syntheticId = bossId(connection, request);
        PacketBuildResult built = packetFactory.buildClientbound(
                connection.sessionProfile().clientCodec(), action, request.payload(),
                fallbackPosition(connection), syntheticId, nextFormId.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1));
        if (built.status() == PacketBuildResult.Status.UNSUPPORTED) return unsupported(request, startedAt, built.reason());
        if (built.status() != PacketBuildResult.Status.SUPPORTED) {
            return finish(request, ActionStatus.REJECTED, built.reason(), Map.of("factoryStatus", built.status().name()), startedAt);
        }
        for (BedrockPacket packet : built.packets()) {
            connection.client().sendPacket(packet);
            connection.observePacket(PacketMonitor.Direction.CLIENTBOUND, packet, packet,
                    PacketMonitor.Action.ONICONTROL_INJECTED);
        }
        if (action == ActionType.REMOVE_BOSSBAR) bossIds.remove(bossKey(connection, request));
        return finish(request, ActionStatus.CONFIRMED, "",
                Map.of("packetCount", built.packets().size(), "encodedBytes", built.encodedBytes(),
                        "clientProtocol", connection.sessionProfile().clientCodec().getProtocolVersion()), startedAt);
    }

    private ControlActionResult executeVirtual(
            ControlActionRequest request, ProxyConnection connection, Instant startedAt) {
        if (!config.virtualization().enabled()) return unsupported(request, startedAt, "OniVirtual is disabled");
        if (request.actionType() == ActionType.CLOSE_VIRTUAL_INVENTORY) {
            VirtualInventoryResult closed = virtualInventories.close(connection, "operator close", true);
            return finish(request, ActionStatus.CONFIRMED, closed.reason(), closed.details(), startedAt);
        }
        if (request.actionType() == ActionType.OPEN_VIRTUAL_INVENTORY) {
            VirtualInventoryResult result = virtualInventories.open(
                    connection, virtualDefinition(request.payload().values()), request);
            ActionStatus status = switch (result.status()) {
                case CONFIRMED -> ActionStatus.CONFIRMED;
                case UNSUPPORTED -> ActionStatus.UNSUPPORTED;
                case REJECTED -> ActionStatus.REJECTED;
                case CLOSED -> ActionStatus.CANCELLED;
            };
            return finish(request, status, result.reason(), result.details(), startedAt);
        }
        Map<String, Object> result = executePrivateVirtual(request, connection);
        return finish(request, ActionStatus.CONFIRMED, "", result, startedAt);
    }

    public boolean interceptVirtualClientPacket(ProxyConnection connection, BedrockPacket packet) {
        return config.virtualization().enabled()
                && (virtualInventories.interceptClient(connection, packet)
                || privateEntities.intercept(connection, packet));
    }

    public boolean interceptVirtualBackendPacket(ProxyConnection connection, BedrockPacket packet) {
        return config.virtualization().enabled() && virtualInventories.interceptBackend(connection, packet);
    }

    public void onWorldReset(ProxyConnection connection, String reason) {
        if (!config.virtualization().enabled()) return;
        virtualInventories.close(connection, reason, true);
        privateEntities.clear(connection);
        fakeBlocks.clear(connection, true);
    }

    private void handleVirtualInteraction(
            ProxyConnection connection, VirtualInventorySession session,
            VirtualSlot slot, VirtualInventoryAction interaction) {
        ActionType action = slot.item().action();
        if (action == null || !session.actorRole().allows(action.minimumRole())) return;
        Instant now = Instant.now();
        TargetSelector target = new TargetSelector(
                connection.clientLogin().authData().xuid(), connection.forwardingSessionId(),
                tenantId, proxyId, connection.backendName(), "");
        ControlActionRequest request = new ControlActionRequest(
                UUID.randomUUID(), session.sessionId() + '-' + interaction.requestId() + '-' + interaction.slot(),
                target, action, action.executionPlane(), slot.item().actionPayload(), session.actor(),
                session.actorRole(), tenantId, proxyId, now, now.plusSeconds(15), false,
                "virtual inventory interaction", session.sessionId(), "");
        execute(request);
    }

    private Map<String, Object> executePrivateVirtual(
            ControlActionRequest request, ProxyConnection connection) {
        Map<String, Object> values = request.payload().values();
        return switch (request.actionType()) {
            case SPAWN_PRIVATE_ENTITY, SPAWN_PRIVATE_NPC, SPAWN_PRIVATE_HOLOGRAM -> {
                exactKeys(values, Set.of("id", "identifier", "position", "rotation", "name", "scale",
                        "timeoutMillis", "interactionAction", "interactionPayload"), "private entity");
                String identifier = switch (request.actionType()) {
                    case SPAWN_PRIVATE_NPC -> "minecraft:npc";
                    case SPAWN_PRIVATE_HOLOGRAM -> "minecraft:armor_stand";
                    default -> stringValue(values.get("identifier"), "identifier", 128, "");
                };
                if (!identifier.matches("[a-z0-9_.:-]{3,128}")) {
                    throw new IllegalArgumentException("identifier must be a safe namespaced entity identifier");
                }
                String id = stringValue(values.get("id"), "id", 64, UUID.randomUUID().toString());
                if (!id.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("invalid private entity ID");
                Vector3f position = vector(values.get("position"), "position", fallbackPosition(connection));
                Vector3f rotation = vector(values.get("rotation"), "rotation", Vector3f.ZERO);
                String name = stringValue(values.get("name"), "name", 256, "");
                float scale = decimalValue(values.get("scale"), "scale",
                        request.actionType() == ActionType.SPAWN_PRIVATE_HOLOGRAM ? 0.01f : 1f,
                        0.01f, 64f);
                long timeout = longValue(values, "timeoutMillis", 300_000, 1_000, 3_600_000);
                ActionType hook = values.get("interactionAction") == null ? null
                        : ActionType.valueOf(stringValue(values.get("interactionAction"),
                        "interactionAction", 64, "").toUpperCase(Locale.ROOT));
                if (hook != null && !request.actorRole().allows(hook.minimumRole())) {
                    throw new IllegalArgumentException("actor cannot grant this private entity interaction");
                }
                ValidatedActionPayload hookPayload = hook == null ? null : new ValidatedActionPayload(1,
                        objectValue(values.getOrDefault("interactionPayload", Map.of()), "interactionPayload"));
                yield privateEntities.spawn(connection, id, identifier, position, rotation, name, scale,
                        Instant.now().plusMillis(timeout), request.actorAccountId(), request.actorRole(),
                        hook, hookPayload);
            }
            case UPDATE_PRIVATE_ENTITY, SET_PRIVATE_ENTITY_METADATA -> {
                exactKeys(values, Set.of("id", "name", "scale"), "private entity update");
                String id = text(values, "id", "", 64);
                String name = values.containsKey("name")
                        ? stringValue(values.get("name"), "name", 256, "") : null;
                Float scale = values.containsKey("scale")
                        ? decimalValue(values.get("scale"), "scale", 1f, 0.01f, 64f) : null;
                yield privateEntities.update(connection, id, name, scale);
            }
            case MOVE_PRIVATE_ENTITY -> {
                exactKeys(values, Set.of("id", "position", "rotation"), "private entity move");
                yield privateEntities.move(connection, text(values, "id", "", 64),
                        vector(values.get("position"), "position", null),
                        vector(values.get("rotation"), "rotation", Vector3f.ZERO));
            }
            case REMOVE_PRIVATE_ENTITY -> {
                exactKeys(values, Set.of("id"), "private entity removal");
                yield Map.of("removed", privateEntities.remove(connection, text(values, "id", "", 64)));
            }
            case CLEAR_PRIVATE_ENTITIES -> {
                exactKeys(values, Set.of(), "private entity clear");
                yield Map.of("removed", privateEntities.clear(connection));
            }
            case SET_FAKE_BLOCK -> {
                exactKeys(values, Set.of("position", "identifier"), "fake block");
                yield fakeBlocks.set(connection, blockPosition(values.get("position"), "position"),
                        stringValue(values.get("identifier"), "identifier", 128, ""));
            }
            case SET_FAKE_BLOCK_REGION -> {
                exactKeys(values, Set.of("first", "second", "identifier"), "fake block region");
                int count = fakeBlocks.setRegion(connection,
                        blockPosition(values.get("first"), "first"),
                        blockPosition(values.get("second"), "second"),
                        stringValue(values.get("identifier"), "identifier", 128, ""));
                yield Map.of("set", count);
            }
            case RESTORE_FAKE_BLOCK -> {
                exactKeys(values, Set.of("position"), "fake block restoration");
                yield Map.of("restored", fakeBlocks.restore(
                        connection, blockPosition(values.get("position"), "position")));
            }
            case CLEAR_FAKE_BLOCKS -> {
                exactKeys(values, Set.of(), "fake block clear");
                yield Map.of("restored", fakeBlocks.clear(connection, true));
            }
            default -> throw new UnsupportedOperationException(
                    "No reviewed virtualization implementation is available for this action");
        };
    }

    private void handlePrivateEntityInteraction(
            ProxyConnection connection, PrivateEntity entity,
            org.cloudburstmc.protocol.bedrock.packet.InteractPacket.Action interaction) {
        ActionType action = entity.interactionAction();
        if (action == null) return;
        Instant now = Instant.now();
        TargetSelector target = new TargetSelector(
                connection.clientLogin().authData().xuid(), connection.forwardingSessionId(),
                tenantId, proxyId, connection.backendName(), "");
        ControlActionRequest request = new ControlActionRequest(
                UUID.randomUUID(), entity.id() + '-' + now.toEpochMilli(), target, action,
                action.executionPlane(), entity.interactionPayload(), entity.actor(),
                entity.actorRole(), tenantId, proxyId, now, now.plusSeconds(15), false,
                "private entity " + interaction.name().toLowerCase(Locale.ROOT), entity.id(), "");
        execute(request);
    }

    /** Reapplies player-scoped visual overrides immediately after authoritative block/chunk updates. */
    public void afterAuthoritativeClientbound(ProxyConnection connection, BedrockPacket translated) {
        if (!config.virtualization().enabled()) return;
        List<BedrockPacket> packets = fakeBlocks.afterAuthoritative(connection, translated);
        for (BedrockPacket packet : packets) {
            connection.client().sendPacket(packet);
            connection.observePacket(PacketMonitor.Direction.CLIENTBOUND, packet, packet,
                    PacketMonitor.Action.ONIVIRTUAL_INJECTED);
        }
    }

    private static VirtualContainerDefinition virtualDefinition(Map<String, Object> values) {
        exactKeys(values, Set.of("title", "size", "page", "pageCount", "timeoutMillis", "slots"), "virtual menu");
        String title = stringValue(values.get("title"), "title", 128, "OniLink menu");
        int size = intValue(values.get("size"), "size", 27, 9, 54);
        if (size != 9 && size != 27 && size != 54) throw new IllegalArgumentException("size must be 9, 27, or 54");
        int page = intValue(values.get("page"), "page", 1, 1, 10_000);
        int pageCount = intValue(values.get("pageCount"), "pageCount", 1, 1, 10_000);
        long timeout = intValue(values.get("timeoutMillis"), "timeoutMillis", 120_000, 1, 1_800_000);
        Object rawSlots = values.get("slots");
        if (!(rawSlots instanceof List<?> list) || list.size() > size) {
            throw new IllegalArgumentException("slots must be an array no larger than the menu");
        }
        List<VirtualSlot> slots = new ArrayList<>();
        for (Object raw : list) {
            Map<String, Object> slot = objectValue(raw, "slot");
            exactKeys(slot, Set.of("index", "identifier", "count", "damage", "name", "lore",
                    "disabled", "action", "actionPayload"), "virtual slot");
            int index = intValue(slot.get("index"), "slot.index", -1, 0, size - 1);
            String identifier = stringValue(slot.get("identifier"), "slot.identifier", 128, "");
            int count = intValue(slot.get("count"), "slot.count", 1, 1, 64);
            int damage = intValue(slot.get("damage"), "slot.damage", 0, 0, 65_535);
            String name = stringValue(slot.get("name"), "slot.name", 256, "");
            List<String> lore = stringList(slot.get("lore"), "slot.lore", 16, 256);
            boolean disabled = boolValue(slot.get("disabled"), false);
            ActionType hook = slot.get("action") == null ? null
                    : ActionType.valueOf(stringValue(slot.get("action"), "slot.action", 64, "").toUpperCase(Locale.ROOT));
            ValidatedActionPayload hookPayload = hook == null ? null
                    : new ValidatedActionPayload(1, objectValue(slot.get("actionPayload"), "slot.actionPayload"));
            slots.add(new VirtualSlot(index,
                    new VirtualItem(identifier, count, damage, name, lore, hook, hookPayload), disabled));
        }
        return new VirtualContainerDefinition(title, size, page, pageCount, slots, Duration.ofMillis(timeout));
    }

    private CompletableFuture<ControlActionResult> executeBackend(
            ControlActionRequest request, ResolvedTarget target, Instant startedAt
    ) {
        Map<String, Object> payload = Map.of(
                "payloadVersion", request.payload().version(),
                "values", request.payload().values(),
                "actor", request.actorAccountId(),
                "actorRole", request.actorRole().name(),
                "reason", request.reason(),
                "correlationId", request.correlationId(),
                "planId", request.planId());
        return bridges.request(target.backend(), request.actionType(), target.xuid(), payload,
                        request.idempotencyKey(), request.deadline())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                                ? failure.getCause() : failure;
                        ActionStatus status = cause instanceof java.util.concurrent.TimeoutException
                                ? ActionStatus.TIMED_OUT : cause instanceof UnsupportedOperationException
                                ? ActionStatus.UNSUPPORTED : ActionStatus.FAILED;
                        return finish(request, status, safeFailure(cause), Map.of(), startedAt);
                    }
                    return bridgeResult(request, response, startedAt);
                });
    }

    private ControlActionResult bridgeResult(ControlActionRequest request, ControlResponseEnvelope response, Instant startedAt) {
        ActionStatus status;
        try { status = ActionStatus.valueOf(response.status()); }
        catch (IllegalArgumentException exception) { status = ActionStatus.FAILED; }
        Map<String, Object> payload;
        try { payload = response.decodedPayload(config.backend(request.target().backend()).maxFrameBytes()); }
        catch (RuntimeException exception) { payload = Map.of("responseDecode", "failed"); status = ActionStatus.FAILED; }
        return finish(request, status, status == ActionStatus.FAILED ? "bridge returned an invalid result" : "", payload, startedAt);
    }

    private ControlActionResult unsupported(ControlActionRequest request, Instant startedAt, String reason) {
        return finish(request, ActionStatus.UNSUPPORTED, reason, Map.of(), startedAt);
    }

    private ControlActionResult finish(
            ControlActionRequest request, ActionStatus status, String reason, Map<String, Object> result, Instant startedAt
    ) {
        Instant completedAt = Instant.now();
        UUID requestId = request == null ? UUID.randomUUID() : request.requestId();
        String auditReference = "control-" + requestId;
        ControlActionResult actionResult = new ControlActionResult(requestId, status, reason, result,
                startedAt, completedAt, auditReference);
        if (request != null) {
            ActionAuditRecord record = new ActionAuditRecord(requestId, request.actorAccountId(), request.actorRole(),
                    request.tenantId(), request.proxyId(), request.target().xuid(), request.target().displayName(),
                    request.target().backend(), request.actionType(), request.executionPlane(), status, completedAt,
                    actionResult.durationMillis(), "payloadVersion=" + request.payload().version()
                            + ", fields=" + String.join(",", request.payload().values().keySet()),
                    "resultFields=" + String.join(",", result.keySet()), reason,
                    status == ActionStatus.CONFIRMED);
            synchronized (audit) {
                while (audit.size() >= MAX_AUDIT_RECORDS) audit.removeFirst();
                audit.addLast(record);
            }
        }
        return actionResult;
    }

    public List<ActionAuditRecord> auditSnapshot() {
        synchronized (audit) { return List.copyOf(audit); }
    }

    public Map<String, Object> status() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("started", started);
        values.put("tenantId", tenantId);
        values.put("proxyId", proxyId);
        values.put("controlEnabled", config.enabled());
        values.put("packetRulesEnabled", packetRules.enabled());
        values.put("virtualizationEnabled", config.virtualization().enabled());
        values.put("protocolLabEnabled", config.protocolLab().enabled());
        values.put("ruleCount", packetRules.rules().size());
        values.put("bridges", bridges.statuses());
        values.put("packetRuleMetrics", packetRules.metrics().snapshot());
        values.put("packetFactoryMetrics", packetFactory.metrics().snapshot());
        values.put("virtualInventorySessions", virtualInventories.snapshot());
        values.put("privateEntities", privateEntities.snapshot());
        values.put("fakeBlocks", fakeBlocks.snapshot());
        return Map.copyOf(values);
    }

    public void onConnectionClosed(ProxyConnection connection) {
        if (connection == null) return;
        virtualInventories.close(connection, "disconnect", false);
        privateEntities.clear(connection);
        fakeBlocks.clear(connection, false);
        String prefix = connection.forwardingSessionId() + ':';
        bossIds.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private long bossId(ProxyConnection connection, ControlActionRequest request) {
        if (request.actionType() != ActionType.CREATE_BOSSBAR
                && request.actionType() != ActionType.UPDATE_BOSSBAR
                && request.actionType() != ActionType.REMOVE_BOSSBAR) return 0;
        String key = bossKey(connection, request);
        if (request.actionType() == ActionType.CREATE_BOSSBAR) {
            if (bossIds.containsKey(key)) throw new IllegalArgumentException("boss bar already exists");
            long id = connection.allocateSyntheticClientEntityId();
            bossIds.put(key, id);
            return id;
        }
        Long id = bossIds.get(key);
        if (id == null) throw new IllegalArgumentException("boss bar does not exist");
        return id;
    }

    private static String bossKey(ProxyConnection connection, ControlActionRequest request) {
        return connection.forwardingSessionId() + ':' + text(request.payload().values(), "bossId", "default", 64);
    }

    private static Vector3f fallbackPosition(ProxyConnection connection) {
        Vector3f position = connection.saneJoinPosition();
        return position == null ? Vector3f.ZERO : position;
    }

    private static Vector3f vector(Object value, String label, Vector3f fallback) {
        if (value == null) {
            if (fallback == null) throw new IllegalArgumentException(label + " is required");
            return fallback;
        }
        Map<String, Object> object = objectValue(value, label);
        exactKeys(object, Set.of("x", "y", "z"), label);
        return Vector3f.from(
                decimalValue(object.get("x"), label + ".x", 0, -30_000_000, 30_000_000),
                decimalValue(object.get("y"), label + ".y", 0, -2_048, 2_048),
                decimalValue(object.get("z"), label + ".z", 0, -30_000_000, 30_000_000));
    }

    private static Vector3i blockPosition(Object value, String label) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(label + " is required");
        Map<String, Object> object = objectValue(value, label);
        exactKeys(object, Set.of("x", "y", "z"), label);
        return Vector3i.from(
                intValue(object.get("x"), label + ".x", Integer.MIN_VALUE, -30_000_000, 30_000_000),
                intValue(object.get("y"), label + ".y", Integer.MIN_VALUE, -2_048, 2_048),
                intValue(object.get("z"), label + ".z", Integer.MIN_VALUE, -30_000_000, 30_000_000));
    }

    private static float decimalValue(
            Object value, String label, float fallback, float minimum, float maximum) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())
                || number.floatValue() < minimum || number.floatValue() > maximum) {
            throw new IllegalArgumentException(label + " must be a finite number in " + minimum + ".." + maximum);
        }
        return number.floatValue();
    }

    private static long longValue(Map<String, Object> values, String key, long fallback, long minimum, long maximum) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
                || number.longValue() < minimum || number.longValue() > maximum) {
            throw new IllegalArgumentException(key + " must be an integer in " + minimum + ".." + maximum);
        }
        return number.longValue();
    }

    private static int intValue(Object value, String label, int fallback, int minimum, int maximum) {
        if (value == null) {
            if (fallback < minimum || fallback > maximum) throw new IllegalArgumentException(label + " is required");
            return fallback;
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue()
                || number.intValue() < minimum || number.intValue() > maximum) {
            throw new IllegalArgumentException(label + " must be an integer in " + minimum + ".." + maximum);
        }
        return number.intValue();
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException("value must be boolean");
        return bool;
    }

    private static String stringValue(Object value, String label, int maximum, String fallback) {
        if (value == null) return fallback;
        if (!(value instanceof String text) || text.length() > maximum || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must be text no longer than " + maximum + " characters");
        }
        return text.strip();
    }

    private static Map<String, Object> objectValue(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException(label + " keys must be text");
            result.put(key, entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static List<String> stringList(Object value, String label, int maximumItems, int maximumLength) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > maximumItems) {
            throw new IllegalArgumentException(label + " must be an array with at most " + maximumItems + " entries");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(stringValue(item, label + " item", maximumLength, ""));
        return List.copyOf(result);
    }

    private static void exactKeys(Map<String, Object> values, Set<String> allowed, String label) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException(label + " contains unknown field " + key);
        }
    }

    private static String text(Map<String, Object> values, String key, String fallback, int maximum) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum) {
            throw new IllegalArgumentException(key + " must be 1.." + maximum + " characters");
        }
        return text.trim();
    }

    private static String safeFailure(Throwable failure) {
        String message = failure == null ? "unknown control failure" : failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.replaceAll("(?i)(secret|token|authorization|jwt)[=: ][^ ,}]+", "$1=<redacted>");
    }

    private static String safeScope(String value, String fallback) {
        String clean = value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (!clean.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IllegalArgumentException("invalid control scope");
        return clean;
    }

    @Override
    public synchronized void close() {
        if (!started) return;
        started = false;
        bridges.close();
        virtualInventories.close();
        privateEntities.close();
        fakeBlocks.close();
        bossIds.clear();
    }
}
