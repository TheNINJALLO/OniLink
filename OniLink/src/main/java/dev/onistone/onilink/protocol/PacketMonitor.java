package dev.onistone.onilink.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.SubClientLoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Bounded packet observation and cross-version matching service.
 *
 * <p>The monitor retains decoded packet details, authenticated identity, endpoints, and the exact
 * uncompressed inbound packet bytes in a bounded in-memory window. Authentication packets and
 * token-shaped values are redacted before storage. It matches the shared packet model received
 * from one codec to the definition in the target codec and records the result of the real
 * translator. A same-class match is safe to hand to the target codec; an ID-only candidate is
 * research evidence and is never applied.</p>
 */
public final class PacketMonitor {
    public static final int DEFAULT_CAPACITY = 5_000;
    public static final int DEFAULT_MOVEMENT_SAMPLE_RATE = 20;
    public static final long DEFAULT_CAPTURE_BUDGET_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_DECODED_PAYLOAD_CHARS = 1_048_576;
    private static final int MAX_PACKET_ID = 0x3FF;
    private static final int MAX_AGGREGATES = 8_192;
    private static final int MAX_MATCH_RESULTS = 500;
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern LABELED_TOKEN_PATTERN = Pattern.compile(
            "(?i)((?:token|jwt|authorization)\\s*[:=]\\s*)[^,}\\]\\s]+(?:\\s+[^,}\\]]+)?");
    private static final Set<String> HIGH_VOLUME_PACKETS = Set.of(
            "PlayerAuthInputPacket",
            "MoveEntityDeltaPacket",
            "MovePlayerPacket",
            "SetEntityMotionPacket"
    );

    public enum Direction {
        SERVERBOUND("serverbound", "Player to server"),
        CLIENTBOUND("clientbound", "Server to player");

        private final String value;
        private final String label;

        Direction(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        public String label() {
            return label;
        }
    }

    public enum Action {
        FORWARDED("forwarded"),
        DROPPED("dropped"),
        HANDLED("handled by proxy"),
        WITHHELD("withheld");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final ProtocolRegistry registry;
    private final int capacity;
    private final int movementSampleRate;
    private final long captureBudgetBytes;
    private final Clock clock;
    private final Map<Integer, List<BedrockPacketDefinition<? extends BedrockPacket>>> definitionsByProtocol;
    private final Deque<Observation> observations = new ArrayDeque<>();
    private final ReentrantLock observationLock = new ReentrantLock();
    private final Map<AggregateKey, Aggregate> aggregates = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sampleSequences = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final LongAdder observed = new LongAdder();
    private final LongAdder nativeMatches = new LongAdder();
    private final LongAdder automaticMatches = new LongAdder();
    private final LongAdder explicitTranslations = new LongAdder();
    private final LongAdder reviewRequired = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder sampledOut = new LongAdder();
    private final LongAdder evicted = new LongAdder();
    private final LongAdder tokenRedactions = new LongAdder();
    private long retainedCaptureBytes;
    private volatile ProtocolPair lastPair;

    public PacketMonitor(ProtocolRegistry registry) {
        this(registry, DEFAULT_CAPACITY, DEFAULT_MOVEMENT_SAMPLE_RATE,
                Long.getLong("onilink.packetMonitor.maxStoredBytes", DEFAULT_CAPTURE_BUDGET_BYTES),
                Clock.systemUTC());
    }

    PacketMonitor(ProtocolRegistry registry, int capacity, int movementSampleRate, Clock clock) {
        this(registry, capacity, movementSampleRate, DEFAULT_CAPTURE_BUDGET_BYTES, clock);
    }

    PacketMonitor(
            ProtocolRegistry registry,
            int capacity,
            int movementSampleRate,
            long captureBudgetBytes,
            Clock clock
    ) {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.registry = registry;
        this.capacity = capacity;
        this.movementSampleRate = Math.max(1, movementSampleRate);
        this.captureBudgetBytes = Math.max(1, captureBudgetBytes);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        Map<Integer, List<BedrockPacketDefinition<? extends BedrockPacket>>> definitions = new HashMap<>();
        for (BedrockCodec codec : registry.supportedCodecs()) {
            definitions.put(codec.getProtocolVersion(), packetDefinitions(codec));
        }
        this.definitionsByProtocol = Map.copyOf(definitions);
    }

    public void observe(
            Direction direction,
            BedrockPacket original,
            BedrockPacket translated,
            Action action,
            TranslationContext context,
            String player,
            String backend
    ) {
        observe(direction, original, translated, action, context,
                new CaptureContext(player, "", "", backend, "", null, 0));
    }

    public void observe(
            Direction direction,
            BedrockPacket original,
            BedrockPacket translated,
            Action action,
            TranslationContext context,
            CaptureContext capture
    ) {
        if (direction == null || original == null || action == null || context == null) {
            return;
        }
        BedrockCodec sourceCodec = direction == Direction.SERVERBOUND
                ? context.clientCodec()
                : context.backendCodec();
        BedrockCodec targetCodec = direction == Direction.SERVERBOUND
                ? context.backendCodec()
                : context.clientCodec();
        lastPair = new ProtocolPair(context.clientCodec().getProtocolVersion(),
                context.backendCodec().getProtocolVersion());

        Definition source = definition(sourceCodec, original);
        BedrockPacket targetPacket = translated == null ? original : translated;
        Definition target = definition(targetCodec, targetPacket);
        Definition candidate = target.present() || source.id() < 0
                ? Definition.missing()
                : definition(targetCodec, source.id());
        String status = matchStatus(sourceCodec, targetCodec, original, translated, target);
        String suggestion = suggestion(status, candidate);
        long timestamp = clock.millis();
        long nextSequence = sequence.incrementAndGet();
        observed.increment();
        incrementStatus(status);
        if (action == Action.DROPPED || action == Action.WITHHELD) {
            dropped.increment();
        }

        AggregateKey aggregateKey = new AggregateKey(
                direction.value(),
                sourceCodec.getProtocolVersion(),
                targetCodec.getProtocolVersion(),
                source.name(),
                source.id(),
                target.id(),
                status,
                action.value(),
                suggestion
        );
        if (aggregates.size() < MAX_AGGREGATES || aggregates.containsKey(aggregateKey)) {
            aggregates.computeIfAbsent(aggregateKey, ignored -> new Aggregate()).mark(timestamp);
        }

        if (sampled(direction, source.name())) {
            sampledOut.increment();
            return;
        }
        CaptureContext safeCapture = capture == null ? CaptureContext.empty() : capture;
        CapturedPayload capturedPayload = capturePayload(original, translated, safeCapture);
        if (capturedPayload.tokenRedacted()) {
            tokenRedactions.increment();
        }
        Observation observation = new Observation(
                nextSequence,
                timestamp,
                direction.value(),
                direction.label(),
                source.name(),
                source.id(),
                target.id(),
                sourceCodec.getProtocolVersion(),
                sourceCodec.getMinecraftVersion(),
                targetCodec.getProtocolVersion(),
                targetCodec.getMinecraftVersion(),
                status,
                action.value(),
                clean(safeCapture.player()),
                clean(safeCapture.xuid()),
                clean(safeCapture.clientAddress()),
                clean(safeCapture.backend()),
                clean(safeCapture.backendAddress()),
                suggestion,
                capturedPayload.decodedPayload(),
                capturedPayload.translatedPayload(),
                capturedPayload.wireBytesBase64(),
                capturedPayload.wireBytesLength(),
                capturedPayload.wireHeaderLength(),
                capturedPayload.tokenRedacted(),
                capturedPayload.redactionReason(),
                capturedPayload.retainedBytes()
        );
        observationLock.lock();
        try {
            while (!observations.isEmpty()
                    && (observations.size() >= capacity
                    || retainedCaptureBytes + observation.retainedBytes() > captureBudgetBytes)) {
                Observation removed = observations.removeFirst();
                retainedCaptureBytes -= removed.retainedBytes();
                evicted.increment();
            }
            observations.addLast(observation);
            retainedCaptureBytes += observation.retainedBytes();
        } finally {
            observationLock.unlock();
        }
    }

    public Map<String, Object> snapshot(Map<String, String> filters) {
        Map<String, String> safeFilters = filters == null ? Map.of() : filters;
        int limit = boundedInt(safeFilters.get("limit"), 500, 1, capacity);
        String direction = normalized(safeFilters.get("direction"));
        String status = normalized(safeFilters.get("status"));
        String query = normalized(safeFilters.get("q"));
        boolean includeDetails = booleanValue(safeFilters.get("includeDetails"));
        boolean redactSensitive = booleanValue(safeFilters.get("redactSensitive"));
        long requestedSequence = boundedLong(safeFilters.get("sequence"), -1L);
        ProtocolPair selected = selectedPair(safeFilters);

        List<Map<String, Object>> records = new ArrayList<>();
        observationLock.lock();
        try {
            var iterator = observations.descendingIterator();
            while (iterator.hasNext() && records.size() < limit) {
                Observation observation = iterator.next();
                if ((requestedSequence < 0 || observation.sequence() == requestedSequence)
                        && observation.matches(direction, status, query)) {
                    records.add(observation.asMap(includeDetails, redactSensitive));
                }
            }
        } finally {
            observationLock.unlock();
        }

        List<Map<String, Object>> matches = aggregates.entrySet().stream()
                .filter(entry -> entry.getKey().matches(direction, status, query))
                .sorted(Comparator.<Map.Entry<AggregateKey, Aggregate>>comparingLong(
                        entry -> entry.getValue().lastSeen()).reversed())
                .limit(MAX_MATCH_RESULTS)
                .map(entry -> entry.getKey().asMap(entry.getValue()))
                .toList();
        List<Map<String, Object>> catalog = catalog(selected.clientProtocol(), selected.backendProtocol());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("observedPackets", observed.sum());
        summary.put("storedRecords", storedRecords());
        summary.put("uniqueMatches", aggregates.size());
        summary.put("nativeMatches", nativeMatches.sum());
        summary.put("automaticMatches", automaticMatches.sum());
        summary.put("explicitTranslations", explicitTranslations.sum());
        summary.put("reviewRequired", reviewRequired.sum());
        summary.put("droppedPackets", dropped.sum());
        summary.put("sampledOut", sampledOut.sum());
        summary.put("evictedRecords", evicted.sum());
        summary.put("capacity", capacity);
        summary.put("movementSampleRate", movementSampleRate);
        summary.put("retainedCaptureBytes", retainedCaptureBytes());
        summary.put("captureBudgetBytes", captureBudgetBytes);
        summary.put("tokenRedactions", tokenRedactions.sum());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("privacy", redactSensitive
                ? "Support-safe metadata view; packet contents and player identity are omitted."
                : "Detailed in-memory capture; authentication tokens and token-bearing login material are always redacted.");
        result.put("summary", summary);
        result.put("protocols", protocols());
        result.put("selectedPair", selectedPairMap(selected));
        result.put("routeAvailable", registry.findBinding(
                selected.clientProtocol(), selected.backendProtocol()).isPresent());
        result.put("records", records);
        result.put("matches", matches);
        result.put("catalog", catalog);
        result.put("catalogCount", catalog.size());
        return result;
    }

    private long storedRecords() {
        observationLock.lock();
        try {
            return observations.size();
        } finally {
            observationLock.unlock();
        }
    }

    private long retainedCaptureBytes() {
        observationLock.lock();
        try {
            return retainedCaptureBytes;
        } finally {
            observationLock.unlock();
        }
    }

    private List<Map<String, Object>> protocols() {
        return registry.supportedCodecs().stream().map(codec -> Map.<String, Object>of(
                "protocol", codec.getProtocolVersion(),
                "minecraftVersion", codec.getMinecraftVersion(),
                "packetModels", definitions(codec).size()
        )).toList();
    }

    private ProtocolPair selectedPair(Map<String, String> filters) {
        List<BedrockCodec> codecs = registry.supportedCodecs();
        int newest = codecs.get(codecs.size() - 1).getProtocolVersion();
        ProtocolPair active = lastPair == null ? new ProtocolPair(newest, newest) : lastPair;
        int client = knownProtocol(filters.get("clientProtocol"), active.clientProtocol());
        int backend = knownProtocol(filters.get("backendProtocol"), active.backendProtocol());
        return new ProtocolPair(client, backend);
    }

    private int knownProtocol(String value, int fallback) {
        int requested = boundedInt(value, fallback, 1, Integer.MAX_VALUE);
        return registry.findClientCodec(requested).isPresent() ? requested : fallback;
    }

    private Map<String, Object> selectedPairMap(ProtocolPair pair) {
        BedrockCodec client = registry.findClientCodec(pair.clientProtocol()).orElseThrow();
        BedrockCodec backend = registry.findBackendCodec(pair.backendProtocol()).orElseThrow();
        return Map.of(
                "clientProtocol", client.getProtocolVersion(),
                "clientVersion", client.getMinecraftVersion(),
                "backendProtocol", backend.getProtocolVersion(),
                "backendVersion", backend.getMinecraftVersion()
        );
    }

    private List<Map<String, Object>> catalog(int clientProtocol, int backendProtocol) {
        BedrockCodec client = registry.findClientCodec(clientProtocol).orElseThrow();
        BedrockCodec backend = registry.findBackendCodec(backendProtocol).orElseThrow();
        List<CatalogEntry> entries = new ArrayList<>();
        Map<CatalogCountKey, Long> counts = catalogCounts();
        addCatalogDirection(entries, Direction.SERVERBOUND, client, backend, counts);
        addCatalogDirection(entries, Direction.CLIENTBOUND, backend, client, counts);
        return entries.stream()
                .sorted(Comparator.comparing(CatalogEntry::packetName)
                        .thenComparing(CatalogEntry::direction))
                .map(CatalogEntry::asMap)
                .toList();
    }

    private void addCatalogDirection(
            List<CatalogEntry> entries,
            Direction direction,
            BedrockCodec sourceCodec,
            BedrockCodec targetCodec,
            Map<CatalogCountKey, Long> counts
    ) {
        PacketRecipient recipient = direction == Direction.SERVERBOUND
                ? PacketRecipient.SERVER
                : PacketRecipient.CLIENT;
        for (BedrockPacketDefinition<? extends BedrockPacket> source : definitions(sourceCodec)) {
            if (source.getRecipient() != PacketRecipient.BOTH && source.getRecipient() != recipient) {
                continue;
            }
            Class<? extends BedrockPacket> packetClass = source.getFactory().get().getClass();
            BedrockPacketDefinition<? extends BedrockPacket> target =
                    targetCodec.getPacketDefinition(packetClass);
            BedrockPacketDefinition<? extends BedrockPacket> candidate = target == null
                    ? targetCodec.getPacketDefinition(source.getId())
                    : null;
            String status = target == null
                    ? "review_required"
                    : sourceCodec.getProtocolVersion() == targetCodec.getProtocolVersion()
                            ? "native"
                            : "automatic_codec_match";
            long count = counts.getOrDefault(new CatalogCountKey(
                    direction.value(),
                    sourceCodec.getProtocolVersion(),
                    targetCodec.getProtocolVersion(),
                    packetClass.getSimpleName()), 0L);
            entries.add(new CatalogEntry(
                    direction.value(),
                    packetClass.getSimpleName(),
                    source.getId(),
                    target == null ? -1 : target.getId(),
                    status,
                    candidate == null ? "" : packetName(candidate),
                    count
            ));
        }
    }

    private Map<CatalogCountKey, Long> catalogCounts() {
        Map<CatalogCountKey, Long> counts = new HashMap<>();
        aggregates.forEach((key, aggregate) -> counts.merge(
                new CatalogCountKey(
                        key.direction(),
                        key.sourceProtocol(),
                        key.targetProtocol(),
                        key.packetName()),
                aggregate.count(),
                Long::sum));
        return counts;
    }

    private List<BedrockPacketDefinition<? extends BedrockPacket>> definitions(BedrockCodec codec) {
        return definitionsByProtocol.getOrDefault(codec.getProtocolVersion(), List.of());
    }

    private static List<BedrockPacketDefinition<? extends BedrockPacket>> packetDefinitions(
            BedrockCodec codec
    ) {
        List<BedrockPacketDefinition<? extends BedrockPacket>> definitions = new ArrayList<>();
        // Bedrock's packet header reserves ten bits for the packet ID.
        for (int packetId = 0; packetId <= MAX_PACKET_ID; packetId++) {
            BedrockPacketDefinition<? extends BedrockPacket> definition =
                    codec.getPacketDefinition(packetId);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        return List.copyOf(definitions);
    }

    private boolean sampled(Direction direction, String packetName) {
        if (!HIGH_VOLUME_PACKETS.contains(packetName) || movementSampleRate <= 1) {
            return false;
        }
        String key = direction.value() + ':' + packetName;
        long count = sampleSequences.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        return (count - 1) % movementSampleRate != 0;
    }

    private void incrementStatus(String status) {
        switch (status) {
            case "native" -> nativeMatches.increment();
            case "automatic_codec_match" -> automaticMatches.increment();
            case "explicit_translation" -> explicitTranslations.increment();
            case "review_required", "unknown_packet" -> reviewRequired.increment();
            default -> {
            }
        }
    }

    private static String matchStatus(
            BedrockCodec sourceCodec,
            BedrockCodec targetCodec,
            BedrockPacket original,
            BedrockPacket translated,
            Definition target
    ) {
        if (original instanceof UnknownPacket) {
            return "unknown_packet";
        }
        if (translated != null && translated.getClass() != original.getClass()) {
            return target.present() ? "explicit_translation" : "review_required";
        }
        if (translated == null && target.present()) {
            return "explicit_translation";
        }
        if (!target.present()) {
            return "review_required";
        }
        return sourceCodec.getProtocolVersion() == targetCodec.getProtocolVersion()
                ? "native"
                : "automatic_codec_match";
    }

    private static String suggestion(String status, Definition candidate) {
        if (!("review_required".equals(status) || "unknown_packet".equals(status)) || !candidate.present()) {
            return "";
        }
        return "Same numeric ID maps to " + candidate.name() + "; review schema before adding a translator.";
    }

    private static Definition definition(BedrockCodec codec, BedrockPacket packet) {
        if (packet instanceof UnknownPacket unknown) {
            BedrockPacketDefinition<? extends BedrockPacket> known =
                    codec.getPacketDefinition(unknown.getPacketId());
            return known == null
                    ? new Definition("UnknownPacket", unknown.getPacketId(), false)
                    : new Definition(packetName(known), unknown.getPacketId(), true);
        }
        BedrockPacketDefinition<? extends BedrockPacket> definition =
                codec.getPacketDefinition(packet.getClass());
        return definition == null
                ? new Definition(packet.getClass().getSimpleName(), -1, false)
                : new Definition(packet.getClass().getSimpleName(), definition.getId(), true);
    }

    private static Definition definition(BedrockCodec codec, int packetId) {
        BedrockPacketDefinition<? extends BedrockPacket> definition = codec.getPacketDefinition(packetId);
        return definition == null
                ? Definition.missing()
                : new Definition(packetName(definition), packetId, true);
    }

    private static String packetName(BedrockPacketDefinition<? extends BedrockPacket> definition) {
        return definition.getFactory().get().getClass().getSimpleName();
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.strip();
        return clean.length() <= 128 ? clean : clean.substring(0, 128);
    }

    private static CapturedPayload capturePayload(
            BedrockPacket original,
            BedrockPacket translated,
            CaptureContext capture
    ) {
        boolean authenticationPacket = containsAuthenticationToken(original)
                || containsAuthenticationToken(translated);
        String decoded = authenticationPacket
                ? redactedPacketSummary(original)
                : redactTokens(boundedPayload(String.valueOf(original)));
        String translatedPayload = translated == null || translated == original
                ? ""
                : authenticationPacket
                        ? redactedPacketSummary(translated)
                        : redactTokens(boundedPayload(String.valueOf(translated)));
        byte[] wireBytes = capture.wireBytes();
        int wireLength = wireBytes == null ? 0 : wireBytes.length;
        boolean detectedToken = !authenticationPacket && wireContainsToken(wireBytes);
        boolean tokenRedacted = authenticationPacket || detectedToken;
        String raw = tokenRedacted || wireLength == 0
                ? ""
                : Base64.getEncoder().encodeToString(wireBytes);
        String reason = authenticationPacket
                ? "Authentication packet body omitted because it contains login or handshake tokens."
                : detectedToken
                        ? "Raw packet body omitted because a token-shaped value was detected."
                        : "";
        long retainedBytes = decoded.getBytes(StandardCharsets.UTF_8).length
                + translatedPayload.getBytes(StandardCharsets.UTF_8).length
                + raw.length();
        return new CapturedPayload(
                decoded,
                translatedPayload,
                raw,
                wireLength,
                Math.max(0, capture.wireHeaderLength()),
                tokenRedacted,
                reason,
                retainedBytes
        );
    }

    private static boolean containsAuthenticationToken(BedrockPacket packet) {
        return packet instanceof LoginPacket
                || packet instanceof SubClientLoginPacket
                || packet instanceof ServerToClientHandshakePacket;
    }

    private static String redactedPacketSummary(BedrockPacket packet) {
        if (packet == null) {
            return "";
        }
        if (packet instanceof LoginPacket login) {
            return "LoginPacket{protocolVersion=" + login.getProtocolVersion()
                    + ", authPayload=<redacted>, clientJwt=<redacted>}";
        }
        if (packet instanceof SubClientLoginPacket) {
            return "SubClientLoginPacket{authPayload=<redacted>, clientJwt=<redacted>}";
        }
        if (packet instanceof ServerToClientHandshakePacket) {
            return "ServerToClientHandshakePacket{jwt=<redacted>}";
        }
        return packet.getClass().getSimpleName() + "{authenticationMaterial=<redacted>}";
    }

    private static String boundedPayload(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_DECODED_PAYLOAD_CHARS) {
            return value;
        }
        return value.substring(0, MAX_DECODED_PAYLOAD_CHARS)
                + "\n<decoded payload truncated after " + MAX_DECODED_PAYLOAD_CHARS + " characters>";
    }

    private static String redactTokens(String value) {
        String redacted = JWT_PATTERN.matcher(value).replaceAll("<redacted-jwt>");
        redacted = BEARER_PATTERN.matcher(redacted).replaceAll("$1<redacted-token>");
        return LABELED_TOKEN_PATTERN.matcher(redacted).replaceAll("$1<redacted-token>");
    }

    private static boolean wireContainsToken(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length == 0) {
            return false;
        }
        String raw = new String(wireBytes, StandardCharsets.ISO_8859_1);
        return JWT_PATTERN.matcher(raw).find()
                || BEARER_PATTERN.matcher(raw).find()
                || LABELED_TOKEN_PATTERN.matcher(raw).find();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static int boundedInt(String value, int fallback, int minimum, int maximum) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long boundedLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    public record CaptureContext(
            String player,
            String xuid,
            String clientAddress,
            String backend,
            String backendAddress,
            byte[] wireBytes,
            int wireHeaderLength
    ) {
        private static CaptureContext empty() {
            return new CaptureContext("", "", "", "", "", null, 0);
        }
    }

    private record CapturedPayload(
            String decodedPayload,
            String translatedPayload,
            String wireBytesBase64,
            int wireBytesLength,
            int wireHeaderLength,
            boolean tokenRedacted,
            String redactionReason,
            long retainedBytes
    ) {
    }

    private record Definition(String name, int id, boolean present) {
        private static Definition missing() {
            return new Definition("", -1, false);
        }
    }

    private record ProtocolPair(int clientProtocol, int backendProtocol) {
    }

    private record CatalogCountKey(
            String direction,
            int sourceProtocol,
            int targetProtocol,
            String packetName
    ) {
    }

    private record Observation(
            long sequence,
            long timestamp,
            String direction,
            String directionLabel,
            String packetName,
            int sourcePacketId,
            int targetPacketId,
            int sourceProtocol,
            String sourceVersion,
            int targetProtocol,
            String targetVersion,
            String status,
            String action,
            String player,
            String xuid,
            String clientAddress,
            String backend,
            String backendAddress,
            String suggestion,
            String decodedPayload,
            String translatedPayload,
            String wireBytesBase64,
            int wireBytesLength,
            int wireHeaderLength,
            boolean tokenRedacted,
            String redactionReason,
            long retainedBytes
    ) {
        private boolean matches(String directionFilter, String statusFilter, String query) {
            if (!directionFilter.isEmpty() && !direction.equals(directionFilter)) {
                return false;
            }
            if (!statusFilter.isEmpty() && !status.equals(statusFilter)) {
                return false;
            }
            if (query.isEmpty()) {
                return true;
            }
            String haystack = (packetName + ' ' + action + ' ' + player + ' ' + xuid + ' '
                    + clientAddress + ' ' + backend + ' ' + backendAddress + ' ' + suggestion + ' '
                    + decodedPayload + ' ' + translatedPayload).toLowerCase(Locale.ROOT);
            return haystack.contains(query);
        }

        private Map<String, Object> asMap(boolean includeDetails, boolean redactSensitive) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sequence", sequence);
            map.put("timestamp", Instant.ofEpochMilli(timestamp).toString());
            map.put("direction", direction);
            map.put("directionLabel", directionLabel);
            map.put("packetName", packetName);
            map.put("sourcePacketId", sourcePacketId);
            map.put("targetPacketId", targetPacketId);
            map.put("sourceProtocol", sourceProtocol);
            map.put("sourceVersion", sourceVersion);
            map.put("targetProtocol", targetProtocol);
            map.put("targetVersion", targetVersion);
            map.put("status", status);
            map.put("action", action);
            map.put("player", redactSensitive ? "" : player);
            map.put("xuid", redactSensitive ? "" : xuid);
            map.put("clientAddress", redactSensitive ? "" : clientAddress);
            map.put("backend", backend);
            map.put("backendAddress", redactSensitive ? "" : backendAddress);
            map.put("suggestion", suggestion);
            map.put("wireBytesLength", wireBytesLength);
            map.put("wireHeaderLength", wireHeaderLength);
            map.put("tokenRedacted", tokenRedacted);
            map.put("redactionReason", redactionReason);
            if (includeDetails && !redactSensitive) {
                map.put("decodedPayload", decodedPayload);
                map.put("translatedPayload", translatedPayload);
                map.put("wireBytesBase64", wireBytesBase64);
            }
            return map;
        }
    }

    private record AggregateKey(
            String direction,
            int sourceProtocol,
            int targetProtocol,
            String packetName,
            int sourcePacketId,
            int targetPacketId,
            String status,
            String action,
            String suggestion
    ) {
        private boolean matches(String directionFilter, String statusFilter, String query) {
            if (!directionFilter.isEmpty() && !direction.equals(directionFilter)) {
                return false;
            }
            if (!statusFilter.isEmpty() && !status.equals(statusFilter)) {
                return false;
            }
            return query.isEmpty() || (packetName + ' ' + action + ' ' + suggestion)
                    .toLowerCase(Locale.ROOT).contains(query);
        }

        private Map<String, Object> asMap(Aggregate aggregate) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("direction", direction);
            map.put("packetName", packetName);
            map.put("sourcePacketId", sourcePacketId);
            map.put("targetPacketId", targetPacketId);
            map.put("sourceProtocol", sourceProtocol);
            map.put("targetProtocol", targetProtocol);
            map.put("status", status);
            map.put("action", action);
            map.put("suggestion", suggestion);
            map.put("count", aggregate.count());
            map.put("lastSeen", Instant.ofEpochMilli(aggregate.lastSeen()).toString());
            return map;
        }
    }

    private static final class Aggregate {
        private final LongAdder count = new LongAdder();
        private volatile long lastSeen;

        private void mark(long timestamp) {
            count.increment();
            lastSeen = timestamp;
        }

        private long count() {
            return count.sum();
        }

        private long lastSeen() {
            return lastSeen;
        }
    }

    private record CatalogEntry(
            String direction,
            String packetName,
            int sourcePacketId,
            int targetPacketId,
            String status,
            String candidate,
            long observedCount
    ) {
        private Map<String, Object> asMap() {
            return Map.of(
                    "direction", direction,
                    "packetName", packetName,
                    "sourcePacketId", sourcePacketId,
                    "targetPacketId", targetPacketId,
                    "status", status,
                    "candidate", candidate,
                    "observedCount", observedCount
            );
        }
    }
}
