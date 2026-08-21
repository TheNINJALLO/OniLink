package dev.onistone.onilink.control;

import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.config.OniControlConfig;
import dev.onistone.onilink.packet.OniPacketFactory;
import dev.onistone.onilink.packet.PacketBuildResult;
import dev.onistone.onilink.protocol.PacketMonitor;
import dev.onistone.onilink.session.ConnectedPlayerRegistry;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Owner-only, allowlisted, semantic packet laboratory. Raw packet IDs and bytes are not accepted. */
final class ProtocolLabService {
    private static final int MAX_SESSIONS = 128;
    private static final Map<String, Model> MODELS = models();

    private record Model(ActionType action, Map<String, String> fields) {}
    private record Session(Instant expiresAt, ArrayDeque<Instant> sends) {}

    private final OniControlConfig.ProtocolLabConfig config;
    private final ConnectedPlayerRegistry players;
    private final OniPacketFactory packets;
    private final AtomicInteger nextFormId;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();

    ProtocolLabService(
            OniControlConfig.ProtocolLabConfig config,
            ConnectedPlayerRegistry players,
            OniPacketFactory packets,
            AtomicInteger nextFormId
    ) {
        this.config = config;
        this.players = players;
        this.packets = packets;
        this.nextFormId = nextFormId;
    }

    synchronized Map<String, Object> status(String actor) {
        Session session = sessions.get(actor);
        if (session != null && !session.expiresAt().isAfter(Instant.now())) {
            sessions.remove(actor);
            session = null;
        }
        return Map.of(
                "enabled", config.enabled(),
                "backendBoundEnabled", config.allowBackendBound(),
                "sessionActive", session != null,
                "sessionExpiresAt", session == null ? "" : session.expiresAt().toString(),
                "maximumPacketsPerMinute", config.maxPacketsPerMinute(),
                "models", MODELS.entrySet().stream().map(entry -> Map.of(
                        "model", entry.getKey(),
                        "direction", "CLIENTBOUND",
                        "fields", entry.getValue().fields())).toList());
    }

    synchronized Map<String, Object> start(String actor) {
        requireEnabled();
        prune();
        if (sessions.size() >= MAX_SESSIONS && !sessions.containsKey(actor)) {
            sessions.remove(sessions.keySet().iterator().next());
        }
        Instant expiresAt = Instant.now().plus(config.maxSessionSeconds(), ChronoUnit.SECONDS);
        sessions.put(actor, new Session(expiresAt, new ArrayDeque<>()));
        return Map.of("started", true, "expiresAt", expiresAt.toString());
    }

    synchronized Map<String, Object> stop(String actor) {
        return Map.of("stopped", sessions.remove(actor) != null);
    }

    Map<String, Object> validate(String actor, Map<String, String> values, boolean send) {
        requireEnabled();
        Session session = activeSession(actor);
        String direction = required(values, "direction").toUpperCase(Locale.ROOT);
        if (!Set.of("CLIENTBOUND", "BACKEND_BOUND").contains(direction)) {
            throw new IllegalArgumentException("direction must be CLIENTBOUND or BACKEND_BOUND");
        }
        if ("BACKEND_BOUND".equals(direction)) {
            if (!config.allowBackendBound()) throw new IllegalArgumentException("backend-bound Protocol Lab is disabled");
            throw new UnsupportedOperationException("no backend-bound packet model has completed semantic review");
        }

        String xuid = required(values, "xuid");
        String backend = required(values, "backend").toLowerCase(Locale.ROOT);
        if (!config.allowedXuids().contains(xuid)) throw new SecurityException("test XUID is not allowlisted");
        if (config.allowedBackends().stream().map(value -> value.toLowerCase(Locale.ROOT))
                .noneMatch(backend::equals)) {
            throw new SecurityException("backend is not allowlisted for Protocol Lab");
        }
        ProxyConnection connection = players.findByXuid(xuid)
                .orElseThrow(() -> new IllegalArgumentException("allowlisted test player is not connected"));
        if (!backend.equalsIgnoreCase(connection.backendName())) {
            throw new IllegalArgumentException("test player is not connected to the selected backend");
        }

        String modelName = required(values, "model").toUpperCase(Locale.ROOT);
        Model model = MODELS.get(modelName);
        if (model == null) throw new SecurityException("packet model is not in the reviewed Protocol Lab allowlist");
        Map<String, Object> payload = ControlJson.parseObject(values.getOrDefault("payload", "{}"), 64 * 1024);
        rejectForbiddenFields(payload);
        PacketBuildResult built = packets.buildClientbound(
                connection.sessionProfile().clientCodec(), model.action(), new ValidatedActionPayload(1, payload),
                Vector3f.ZERO, -1, nextFormId.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1));
        if (built.status() != PacketBuildResult.Status.SUPPORTED) {
            throw new IllegalArgumentException("packet did not pass dry encoding: " + built.reason());
        }
        if (send) {
            reserveSend(session);
            for (BedrockPacket packet : built.packets()) {
                connection.client().sendPacket(packet);
                connection.observePacket(PacketMonitor.Direction.CLIENTBOUND, packet, packet,
                        PacketMonitor.Action.PROTOCOL_LAB_INJECTED);
            }
        }
        return Map.of(
                "valid", true,
                "sent", send,
                "model", modelName,
                "direction", direction,
                "packetCount", built.packets().size(),
                "encodedBytes", built.encodedBytes(),
                "clientProtocol", connection.sessionProfile().clientCodec().getProtocolVersion(),
                "sessionExpiresAt", session.expiresAt().toString());
    }

    private synchronized Session activeSession(String actor) {
        prune();
        Session session = sessions.get(actor);
        if (session == null) throw new IllegalStateException("start a Protocol Lab session first");
        return session;
    }

    private synchronized void reserveSend(Session session) {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.MINUTES);
        while (!session.sends().isEmpty() && session.sends().getFirst().isBefore(cutoff)) {
            session.sends().removeFirst();
        }
        if (session.sends().size() >= config.maxPacketsPerMinute()) {
            throw new IllegalStateException("Protocol Lab packet rate limit exceeded");
        }
        session.sends().addLast(Instant.now());
    }

    private synchronized void prune() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void requireEnabled() {
        if (!config.enabled()) throw new IllegalStateException("Protocol Lab is disabled");
    }

    private static String required(Map<String, String> values, String field) {
        String value = values.get(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static void rejectForbiddenFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (key.matches(".*(raw|bytes|packetid|login|jwt|token|secret|chain|handshake|oniforward).*$")) {
                    throw new SecurityException("Protocol Lab payload contains a forbidden field");
                }
                rejectForbiddenFields(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            list.forEach(ProtocolLabService::rejectForbiddenFields);
        }
    }

    private static Map<String, Model> models() {
        Map<String, Model> models = new LinkedHashMap<>();
        models.put("SYSTEM_MESSAGE", new Model(ActionType.SEND_MESSAGE, Map.of("message", "required string")));
        models.put("TITLE", new Model(ActionType.SEND_TITLE, Map.of("title", "required string")));
        models.put("SUBTITLE", new Model(ActionType.SEND_SUBTITLE, Map.of("subtitle", "required string")));
        models.put("ACTIONBAR", new Model(ActionType.SEND_ACTIONBAR, Map.of("message", "required string")));
        models.put("TOAST", new Model(ActionType.SEND_TOAST,
                Map.of("title", "required string", "content", "required string")));
        models.put("PLAY_SOUND", new Model(ActionType.PLAY_SOUND,
                Map.of("sound", "required namespaced string", "volume", "number", "pitch", "number")));
        models.put("STOP_SOUND", new Model(ActionType.STOP_SOUND,
                Map.of("sound", "namespaced string", "all", "boolean")));
        models.put("PARTICLE", new Model(ActionType.SPAWN_PARTICLE,
                Map.of("particle", "reviewed ParticleType name")));
        return Map.copyOf(models);
    }
}
