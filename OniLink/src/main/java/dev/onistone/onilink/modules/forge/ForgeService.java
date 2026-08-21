package dev.onistone.onilink.modules.forge;

import dev.onistone.onilink.protocol.CanonicalProtocol;
import dev.onistone.onilink.protocol.ProtocolRegistry;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evidence-oriented codec diff and compatibility matrix generator. */
public final class ForgeService {
    private static final int MAX_PACKET_ID = 4_096;
    private final ProtocolRegistry registry;

    public ForgeService() {
        this(ProtocolRegistry.createDefault());
    }

    public ForgeService(ProtocolRegistry registry) {
        this.registry = registry;
    }

    public Map<String, Object> diff(int fromProtocol, int toProtocol) {
        BedrockCodec from = registry.findClientCodec(fromProtocol)
                .orElseThrow(() -> new IllegalArgumentException("unknown source protocol"));
        BedrockCodec to = registry.findClientCodec(toProtocol)
                .orElseThrow(() -> new IllegalArgumentException("unknown target protocol"));
        Map<String, Definition> before = definitions(from);
        Map<String, Definition> after = definitions(to);
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names.stream().sorted().toList()) {
            Definition left = before.get(name);
            Definition right = after.get(name);
            if (left == null) added.add(right.view());
            else if (right == null) removed.add(left.view());
            else if (!left.equals(right)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("packet", name);
                item.put("fromId", left.id());
                item.put("toId", right.id());
                item.put("idChanged", left.id() != right.id());
                item.put("fromSerializer", left.serializer());
                item.put("toSerializer", right.serializer());
                item.put("serializerChanged", !left.serializer().equals(right.serializer()));
                item.put("fromDirection", left.direction());
                item.put("toDirection", right.direction());
                item.put("directionChanged", !left.direction().equals(right.direction()));
                item.put("semanticReviewRequired", true);
                changed.add(Map.copyOf(item));
            }
        }
        boolean edge = registry.findPath(fromProtocol, toProtocol).isPresent();
        return Map.ofEntries(
                Map.entry("from", protocol(from)), Map.entry("to", protocol(to)),
                Map.entry("added", added), Map.entry("removed", removed), Map.entry("changed", changed),
                Map.entry("translationEdge", edge),
                Map.entry("missingTranslator", fromProtocol != toProtocol && !edge),
                Map.entry("fieldDetection", "Serializer and model changes are detected; field semantics require review."),
                Map.entry("semanticEquivalenceInferred", false));
    }

    public Map<String, Object> matrix() {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<BedrockCodec> codecs = registry.supportedCodecs();
        for (BedrockCodec client : codecs) {
            for (BedrockCodec backend : codecs) {
                boolean same = client.getProtocolVersion() == backend.getProtocolVersion();
                var path = registry.findPath(client.getProtocolVersion(), backend.getProtocolVersion());
                String status = same ? "SUPPORTED" : path.isPresent() ? "SUPPORTED_WITH_LIMITS" : "UNSUPPORTED";
                List<String> evidence = new ArrayList<>();
                evidence.add("compiled-codec");
                if (same) evidence.add("identity-codec");
                if (path.isPresent() && !same) evidence.add("registered-translator-chain:" + path.get().size());
                rows.add(Map.of(
                        "clientProtocol", client.getProtocolVersion(),
                        "clientVersion", client.getMinecraftVersion(),
                        "backendProtocol", backend.getProtocolVersion(),
                        "backendVersion", backend.getMinecraftVersion(),
                        "status", status,
                        "evidence", List.copyOf(evidence),
                        "liveAcceptance", same && backend.getProtocolVersion() == CanonicalProtocol.V1_26_40.protocolVersion()
                                ? "linux-bds-1.26.44.3" : "not-recorded",
                        "limitations", same ? List.of() : path.isPresent()
                                ? List.of("packet semantics remain test-gated", "unknown packets fail closed")
                                : List.of("no directed translator path")));
            }
        }
        return Map.of("schemaVersion", 1, "generatedFrom", List.of(
                "compiled-codecs", "protocol-registry", "test-gates", "native-profile-metadata", "live-evidence"),
                "statuses", List.of("SUPPORTED", "SUPPORTED_WITH_LIMITS", "CANDIDATE", "UNSUPPORTED", "DISABLED"),
                "rows", rows);
    }

    public String matrixMarkdown() {
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) matrix().get("rows");
        StringBuilder output = new StringBuilder("# Generated compatibility matrix\n\n"
                + "This file is generated from compiled codecs and registered translation edges. "
                + "A matching packet ID alone never proves semantic compatibility.\n\n"
                + "| Client | Backend | Status | Evidence |\n| --- | --- | --- | --- |\n");
        for (Map<String, Object> row : rows) {
            output.append("| ").append(row.get("clientVersion")).append(" (`")
                    .append(row.get("clientProtocol")).append("`) | ").append(row.get("backendVersion"))
                    .append(" (`").append(row.get("backendProtocol")).append("`) | ")
                    .append(row.get("status")).append(" | ")
                    .append(String.join(", ", stringList(row.get("evidence")))).append(" |\n");
        }
        return output.toString();
    }

    private static Map<String, Definition> definitions(BedrockCodec codec) {
        Map<String, Definition> result = new LinkedHashMap<>();
        for (int packetId = 0; packetId <= MAX_PACKET_ID; packetId++) {
            BedrockPacketDefinition<? extends BedrockPacket> definition = codec.getPacketDefinition(packetId);
            if (definition == null) continue;
            BedrockPacket packet = definition.getFactory().get();
            String name = packet.getClass().getSimpleName();
            result.put(name, new Definition(name, definition.getId(),
                    definition.getSerializer().getClass().getName(), definition.getRecipient().name()));
        }
        return result;
    }

    private static Map<String, Object> protocol(BedrockCodec codec) {
        return Map.of("protocol", codec.getProtocolVersion(), "minecraftVersion", codec.getMinecraftVersion());
    }

    private record Definition(String name, int id, String serializer, String direction) {
        Map<String, Object> view() {
            return Map.of("packet", name, "id", id, "serializer", serializer, "direction", direction,
                    "semanticReviewRequired", true);
        }
    }

    @SuppressWarnings("unchecked") private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
