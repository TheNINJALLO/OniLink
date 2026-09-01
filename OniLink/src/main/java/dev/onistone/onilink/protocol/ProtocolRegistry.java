package dev.onistone.onilink.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a connecting client protocol to a backend protocol, chaining adjacent-version translators when
 * the two are several versions apart &mdash; the ViaVersion / endweave model.
 *
 * <p>Translators are stored as a <em>directed</em> graph: every edge goes from a newer protocol to an
 * older one (the only direction the proxy needs, since clients are newer than or equal to backends).
 * {@link #findBinding(int, int)} runs a BFS for the shortest chain of edges from the client protocol
 * down to the backend protocol. A single-hop result returns the raw translator; a multi-hop result is
 * wrapped in a {@link ChainedPacketTranslator}. Equal protocols use {@link IdentityTranslator898}.</p>
 *
 * @see ChainedPacketTranslator
 * @see dev.onistone.onilink.protocol.ProtocolBinding
 */
public final class ProtocolRegistry {
    private final Map<Integer, BedrockCodec> codecs;
    private final Map<Integer, List<Edge>> outgoing;
    private final Map<Long, Optional<List<PacketTranslator>>> pathCache = new ConcurrentHashMap<>();

    private record Edge(int target, PacketTranslator translator) {
    }

    private ProtocolRegistry(Map<Integer, BedrockCodec> codecs, Map<Integer, List<Edge>> outgoing) {
        this.codecs = Map.copyOf(codecs);
        Map<Integer, List<Edge>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Edge>> entry : outgoing.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.outgoing = Map.copyOf(copy);
    }

    public static ProtocolRegistry createDefault() {
        return defaultBuilder().build();
    }

    /**
     * Everything {@link #createDefault()} registers, still open for more.
     *
     * <p>Exists so an addon can contribute edges the proxy has no business knowing about. The proxy's
     * own graph only ever goes newer&rarr;older; an addon adds the upgrade edge its translator needs, and a proxy running without it has no idea that direction exists.</p>
     */
    public static Builder defaultBuilder() {
        return builder()
                // All known client/backend codecs.
                .codec(CanonicalProtocol.V1_21_130)
                .codec(CanonicalProtocol.V1_26_0)
                .codec(CanonicalProtocol.V1_26_10)
                .codec(CanonicalProtocol.V1_26_20)
                .codec(CanonicalProtocol.V1_26_30)
                .codec(CanonicalProtocol.V1_26_40)
                .codec(CanonicalProtocol.V1_26_45)
                .codec(CanonicalProtocol.V1_26_50)
                // Directed adjacent translators (newer -> older). Longer gaps are auto-chained.
                .edge(CanonicalProtocol.V1_26_50, CanonicalProtocol.V1_26_45, ModernClientTo2168Translator.INSTANCE)
                .edge(CanonicalProtocol.V1_26_50, CanonicalProtocol.V1_26_40, ModernClientTo2168Translator.INSTANCE)
                .edge(CanonicalProtocol.V1_26_45, CanonicalProtocol.V1_26_40, IdentityTranslator898.INSTANCE)
                // Endstone 0.11.10 accepts wire-compatible 2168 clients on a 2169 backend.
                .upgradeEdge(CanonicalProtocol.V1_26_40, CanonicalProtocol.V1_26_45, IdentityTranslator898.INSTANCE)
                .edge(CanonicalProtocol.V1_26_40, CanonicalProtocol.V1_26_30, ModernClientTo1001Translator.INSTANCE)
                .edge(CanonicalProtocol.V1_26_30, CanonicalProtocol.V1_26_20, ModernClientTo975Translator.INSTANCE)
                .edge(CanonicalProtocol.V1_26_20, CanonicalProtocol.V1_26_10, ModernClientTo944Translator.INSTANCE)
                .edge(CanonicalProtocol.V1_26_20, CanonicalProtocol.V1_21_130, ModernClientTo898Translator.INSTANCE)
                .edge(CanonicalProtocol.V1_26_10, CanonicalProtocol.V1_21_130, ModernClientTo898Translator.INSTANCE)
                .edge(CanonicalProtocol.V1_26_0, CanonicalProtocol.V1_21_130, ModernClientTo898Translator.INSTANCE);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<BedrockCodec> findClientCodec(int protocolVersion) {
        return Optional.ofNullable(codecs.get(protocolVersion));
    }

    public Optional<BedrockCodec> findBackendCodec(int protocolVersion) {
        return Optional.ofNullable(codecs.get(protocolVersion));
    }

    public Optional<ProtocolBinding> findClient(int protocolVersion) {
        return findClientCodec(protocolVersion).map(clientCodec -> new ProtocolBinding(
                clientCodec,
                clientCodec,
                clientCodec,
                IdentityTranslator898.INSTANCE
        ));
    }

    public Optional<ProtocolBinding> findBinding(int clientProtocolVersion, int backendProtocolVersion) {
        BedrockCodec clientCodec = codecs.get(clientProtocolVersion);
        BedrockCodec backendCodec = codecs.get(backendProtocolVersion);
        if (clientCodec == null || backendCodec == null) {
            return Optional.empty();
        }
        if (clientProtocolVersion == backendProtocolVersion) {
            return Optional.of(new ProtocolBinding(clientCodec, backendCodec, backendCodec, IdentityTranslator898.INSTANCE));
        }
        return findPath(clientProtocolVersion, backendProtocolVersion).map(path -> {
            PacketTranslator translator = path.size() == 1
                    ? path.get(0)
                    : new ChainedPacketTranslator(path, true);
            return new ProtocolBinding(clientCodec, backendCodec, backendCodec, translator);
        });
    }

    /**
     * Shortest directed chain of translators from {@code clientProtocol} down to {@code backendProtocol},
     * ordered client &rarr; backend. Empty Optional when no path exists.
     */
    public Optional<List<PacketTranslator>> findPath(int clientProtocol, int backendProtocol) {
        if (clientProtocol == backendProtocol) {
            return Optional.of(List.of());
        }
        long key = (((long) clientProtocol) << 32) | (backendProtocol & 0xffffffffL);
        return pathCache.computeIfAbsent(key, ignored -> bfs(clientProtocol, backendProtocol));
    }

    private Optional<List<PacketTranslator>> bfs(int clientProtocol, int backendProtocol) {
        Map<Integer, Integer> parent = new LinkedHashMap<>();
        Map<Integer, PacketTranslator> parentEdge = new LinkedHashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(clientProtocol);
        parent.put(clientProtocol, clientProtocol);

        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == backendProtocol) {
                return Optional.of(reconstruct(clientProtocol, backendProtocol, parent, parentEdge));
            }
            for (Edge edge : outgoing.getOrDefault(current, List.of())) {
                if (!parent.containsKey(edge.target())) {
                    parent.put(edge.target(), current);
                    parentEdge.put(edge.target(), edge.translator());
                    queue.add(edge.target());
                }
            }
        }
        return Optional.empty();
    }

    private static List<PacketTranslator> reconstruct(int from, int to, Map<Integer, Integer> parent,
                                                      Map<Integer, PacketTranslator> parentEdge) {
        List<PacketTranslator> reversed = new ArrayList<>();
        int node = to;
        while (node != from) {
            reversed.add(parentEdge.get(node));
            node = parent.get(node);
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    public Collection<ProtocolBinding> supportedClients() {
        return Collections.unmodifiableCollection(codecs.values().stream()
                .map(codec -> new ProtocolBinding(codec, codec, codec, IdentityTranslator898.INSTANCE))
                .toList());
    }

    /**
     * Resolves a binding while preserving wire dialects that share a protocol number.
     *
     * <p>1.26.40 and 1.26.44 both announce 2168. The backend's pong/login version is therefore the
     * only way to choose the correct SetScore serializer.</p>
     */
    public Optional<ProtocolBinding> findBinding(
            int clientProtocolVersion,
            int backendProtocolVersion,
            String backendMinecraftVersion
    ) {
        return findBinding(clientProtocolVersion, backendProtocolVersion).map(binding -> {
            if (backendProtocolVersion != 2168 || !atLeast12644(backendMinecraftVersion)) {
                return binding;
            }
            return new ProtocolBinding(
                    binding.clientCodec(),
                    Bedrock_v2168_hotfix4.CODEC,
                    Bedrock_v2168_hotfix4.CODEC,
                    binding.translator()
            );
        });
    }

    /** Preserves a login-refined client dialect when two releases share one protocol number. */
    public Optional<ProtocolBinding> findBinding(
            BedrockCodec clientCodec,
            int backendProtocolVersion,
            String backendMinecraftVersion
    ) {
        if (clientCodec == null) {
            throw new IllegalArgumentException("clientCodec cannot be null");
        }
        return findBinding(
                clientCodec.getProtocolVersion(),
                backendProtocolVersion,
                backendMinecraftVersion
        ).map(binding -> new ProtocolBinding(
                clientCodec,
                binding.canonicalCodec(),
                binding.backendCodec(),
                binding.translator()
        ));
    }

    private static boolean atLeast12644(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String[] raw = version.trim().split("\\.");
        int offset = raw.length > 0 && "1".equals(raw[0]) ? 1 : 0;
        if (raw.length < offset + 2) {
            return false;
        }
        try {
            int minor = Integer.parseInt(raw[offset]);
            int patch = Integer.parseInt(raw[offset + 1]);
            return minor > 26 || minor == 26 && patch >= 44;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** All registered codecs in protocol order for diagnostics and compatibility tooling. */
    public List<BedrockCodec> supportedCodecs() {
        return codecs.values().stream()
                .sorted(Comparator.comparingInt(BedrockCodec::getProtocolVersion))
                .toList();
    }

    public BedrockCodec advertisedClientCodec() {
        return codecs.values().stream()
                .max((left, right) -> Integer.compare(left.getProtocolVersion(), right.getProtocolVersion()))
                .orElse(CanonicalProtocol.V1_21_130.codec());
    }

    public PlayStatusPacket.Status unsupportedStatus(int protocolVersion) {
        int newestSupportedProtocol = advertisedClientCodec().getProtocolVersion();
        return protocolVersion > newestSupportedProtocol
                ? PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD
                : PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD;
    }

    public static final class Builder {
        private final Map<Integer, BedrockCodec> codecs = new LinkedHashMap<>();
        private final Map<Integer, List<Edge>> outgoing = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder codec(CanonicalProtocol protocol) {
            BedrockCodec codec = protocol.codec();
            codecs.putIfAbsent(codec.getProtocolVersion(), codec);
            return this;
        }

        /**
         * Registers a directed adjacent translator from a newer protocol to an older one.
         */
        public Builder edge(CanonicalProtocol newer, CanonicalProtocol older, PacketTranslator translator) {
            if (translator == null) {
                throw new IllegalArgumentException("translator cannot be null");
            }
            int from = newer.protocolVersion();
            int to = older.protocolVersion();
            if (from <= to) {
                throw new IllegalArgumentException("edge must go from a newer protocol to an older one: " + from + " -> " + to);
            }
            codec(newer);
            codec(older);
            outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new Edge(to, translator));
            return this;
        }

        /**
         * Registers a directed adjacent translator from an older protocol to a newer one.
         *
         * <p>The mirror image of {@link #edge(CanonicalProtocol, CanonicalProtocol, PacketTranslator)},
         * and the only way an older client can reach a newer backend. Kept as a separate method rather
         * than relaxing {@code edge}'s direction check, so that registering a downgrade backwards stays
         * the loud mistake it is today.</p>
         */
        public Builder upgradeEdge(CanonicalProtocol older, CanonicalProtocol newer, PacketTranslator translator) {
            if (translator == null) {
                throw new IllegalArgumentException("translator cannot be null");
            }
            int from = older.protocolVersion();
            int to = newer.protocolVersion();
            if (from >= to) {
                throw new IllegalArgumentException("upgrade edge must go from an older protocol to a newer one: " + from + " -> " + to);
            }
            codec(older);
            codec(newer);
            outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new Edge(to, translator));
            return this;
        }

        public ProtocolRegistry build() {
            return new ProtocolRegistry(codecs, outgoing);
        }
    }
}
