package dev.onistone.onilink.modules.forge;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.protocol.*;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.codec.*;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.data.definitions.*;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.nbt.NbtMap;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** Offline replay of sanitized source bytes against reviewed, exact target bytes. No live packets. */
public final class CompatibilityLab {
    public static final Set<String> CATEGORIES = Set.of("join", "movement", "inventory", "crafting", "commands", "packs", "transfers");
    public Map<String, Object> runArchive(ProtocolRegistry registry, Path archive) throws IOException {
        return runArchive(registry, archive, null);
    }
    public Map<String, Object> runArchive(ProtocolRegistry registry, Path archive, String requiredBackend) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry("fixtures.json");
            if (entry == null || entry.getSize() > 8_388_608) throw new IOException("fixtures.json is missing or too large");
            try (InputStream stream = zip.getInputStream(entry)) {
                byte[] bytes = stream.readNBytes(8_388_609);
                if (bytes.length > 8_388_608) throw new IOException("fixture suite is too large");
                var suite = new LinkedHashMap<>(ControlJson.parseObject(new String(bytes, StandardCharsets.UTF_8), 8_388_608));
                if (requiredBackend != null && suite.get("fixtures") instanceof List<?> fixtures)
                    suite.put("fixtures", fixtures.stream().filter(f -> f instanceof Map<?, ?> m && requiredBackend.equals(m.get("backendVersion"))).toList());
                return run(registry, suite);
            }
        }
    }
    public Map<String, Object> run(ProtocolRegistry registry, Map<String, Object> suite) {
        if (!Boolean.TRUE.equals(suite.get("sanitized"))) throw new IllegalArgumentException("fixture suite must attest sanitized data");
        if (!(suite.get("fixtures") instanceof List<?> fixtures) || fixtures.isEmpty() || fixtures.size() > 1000)
            throw new IllegalArgumentException("expected 1 to 1000 fixtures");
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> passed = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Object raw : fixtures) {
            if (!(raw instanceof Map<?, ?> fixture)) throw new IllegalArgumentException("invalid fixture");
            String id = text(fixture, "id");
            if (id.length() > 128 || !ids.add(id)) throw new IllegalArgumentException("fixture IDs must be unique and bounded");
            String category = text(fixture, "category");
            if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("invalid fixture category");
            long start = System.nanoTime();
            try {
                String packet = replay(registry, fixture);
                passed.add(category);
                results.add(Map.of("id", id, "category", category, "packet", packet, "status", "PASS", "durationNanos", System.nanoTime() - start,
                        "clientVersion", text(fixture, "clientVersion"), "backendVersion", text(fixture, "backendVersion")));
            } catch (Exception | LinkageError failure) {
                // Do not echo a decoded packet, login token or input bytes into the report.
                results.add(Map.of("id", id, "category", category, "status", "FAIL", "error", failure.getClass().getSimpleName(),
                        "durationNanos", System.nanoTime() - start));
            }
        }
        var missing = CATEGORIES.stream().filter(c -> !passed.contains(c)).sorted().toList();
        boolean success = results.stream().allMatch(r -> "PASS".equals(r.get("status")));
        return Map.of("status", success ? "PASS" : "FAIL", "coverageComplete", success && missing.isEmpty(),
                "missingCategories", missing, "fixtures", results, "evidenceType", "offline-wire-replay", "liveAcceptance", false);
    }
    private String replay(ProtocolRegistry registry, Map<?, ?> fixture) throws Exception {
        BedrockCodec client = codec(registry, text(fixture, "clientVersion"));
        BedrockCodec backend = codec(registry, text(fixture, "backendVersion"));
        ProtocolBinding binding = registry.findBinding(client, backend.getProtocolVersion(), backend.getMinecraftVersion()).orElseThrow();
        String direction = text(fixture, "direction");
        if (!Set.of("serverbound", "clientbound").contains(direction)) throw new IllegalArgumentException("invalid direction");
        boolean serverbound = direction.equals("serverbound");
        BedrockCodec source = serverbound ? client : backend;
        BedrockCodec target = serverbound ? backend : client;
        byte[] sourceBytes = bytes(fixture, "input");
        byte[] expectedBytes = bytes(fixture, "expected");
        var input = Unpooled.wrappedBuffer(sourceBytes);
        var output = Unpooled.buffer();
        BedrockPacket decoded = null, translated = null, check = null;
        try {
            int packetId = ((Number) fixture.get("packetId")).intValue();
            var definition = source.getPacketDefinition(packetId);
            if (definition == null) throw new IllegalArgumentException("packet absent from source codec");
            var recipient = serverbound ? org.cloudburstmc.protocol.bedrock.data.PacketRecipient.SERVER : org.cloudburstmc.protocol.bedrock.data.PacketRecipient.CLIENT;
            if (definition.getRecipient() != null && definition.getRecipient() != recipient
                    && definition.getRecipient() != org.cloudburstmc.protocol.bedrock.data.PacketRecipient.BOTH) throw new IllegalArgumentException("fixture uses an invalid packet direction");
            decoded = source.tryDecode(helper(source, fixture.get("sourcePalette")), input, packetId);
            if (input.isReadable() || decoded.getClass() != definition.getFactory().get().getClass()) throw new IOException("source decode failed");
            String packet = decoded.getClass().getSimpleName();
            Set<String> allowed = switch (text(fixture, "category")) {
                case "join" -> Set.of("StartGamePacket", "PlayStatusPacket", "SetLocalPlayerAsInitializedPacket");
                case "movement" -> Set.of("PlayerAuthInputPacket", "MovePlayerPacket", "MoveEntityDeltaPacket");
                case "inventory" -> Set.of("InventoryContentPacket", "InventoryTransactionPacket", "ItemStackRequestPacket", "ItemStackResponsePacket");
                case "crafting" -> Set.of("CraftingDataPacket", "CraftingEventPacket");
                case "commands" -> Set.of("AvailableCommandsPacket", "CommandRequestPacket");
                case "packs" -> Set.of("ResourcePacksInfoPacket", "ResourcePackStackPacket", "ResourcePackClientResponsePacket");
                case "transfers" -> Set.of("TransferPacket", "ChangeDimensionPacket", "RespawnPacket");
                default -> Set.of();
            };
            if (!allowed.contains(packet)) throw new IOException("packet does not exercise the claimed category");
            var context = new TranslationContext(client, backend, backend);
            translated = serverbound ? binding.translator().translateServerbound(decoded, context)
                    : binding.translator().translateClientbound(decoded, context);
            if (translated == null) throw new IOException("fixture packet was dropped");
            var targetDefinition = target.getPacketDefinition(translated.getClass());
            if (targetDefinition == null || targetDefinition.getId() != ((Number) fixture.get("expectedPacketId")).intValue())
                throw new IOException("unexpected target packet ID");
            target.tryEncode(helper(target, fixture.get("targetPalette")), output, translated);
            byte[] actual = new byte[output.readableBytes()];
            output.getBytes(output.readerIndex(), actual);
            if (!Arrays.equals(expectedBytes, actual)) throw new IOException("target bytes differ from reviewed fixture");
            check = target.tryDecode(helper(target, fixture.get("targetPalette")), output, targetDefinition.getId());
            if (output.isReadable() || check.getClass() != translated.getClass()) throw new IOException("target decode failed");
            return packet;
        } finally {
            input.release(); output.release();
            ReferenceCountUtil.release(check);
            if (translated != decoded) ReferenceCountUtil.release(translated);
            ReferenceCountUtil.release(decoded);
        }
    }
    public static BedrockCodec codec(ProtocolRegistry registry, String release) {
        return registry.supportedDialects().stream().filter(c -> c.getMinecraftVersion().equals(release)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown release " + release));
    }
    private static BedrockCodecHelper helper(BedrockCodec codec, Object raw) {
        var helper = codec.createHelper();
        Map<Integer, ItemDefinition> items = new HashMap<>();
        Map<Integer, BlockDefinition> blocks = new HashMap<>();
        if (raw instanceof Map<?, ?> palette) {
            if (palette.get("items") instanceof List<?> list) for (Object entry : list) {
                var item = (Map<?, ?>) entry;
                int id = ((Number) item.get("id")).intValue();
                if (items.put(id, new SimpleItemDefinition(text(item, "name"), id, Boolean.TRUE.equals(item.get("componentBased")))) != null)
                    throw new IllegalArgumentException("duplicate item ID");
            }
            if (palette.get("blocks") instanceof List<?> list) for (Object entry : list) {
                var block = (Map<?, ?>) entry;
                int id = ((Number) block.get("id")).intValue();
                if (blocks.put(id, new SimpleBlockDefinition(text(block, "name"), id, NbtMap.EMPTY)) != null)
                    throw new IllegalArgumentException("duplicate block ID");
            }
        }
        helper.setItemDefinitions(new DefinitionRegistry<>() {
            public ItemDefinition getDefinition(int id) { return Objects.requireNonNull(items.get(id), "fixture item definition missing"); }
            public boolean isRegistered(ItemDefinition d) { return d != null && items.containsKey(d.getRuntimeId()); }
        });
        helper.setBlockDefinitions(new DefinitionRegistry<>() {
            public BlockDefinition getDefinition(int id) { return Objects.requireNonNull(blocks.get(id), "fixture block definition missing"); }
            public boolean isRegistered(BlockDefinition d) { return d != null && blocks.containsKey(d.getRuntimeId()); }
        });
        return helper;
    }
    private static String text(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is required");
        return text;
    }
    private static byte[] bytes(Map<?, ?> map, String key) {
        String value = text(map, key);
        if (value.length() > 1_398_104) throw new IllegalArgumentException("fixture packet exceeds 1 MiB");
        return Base64.getDecoder().decode(value);
    }
}
