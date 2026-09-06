package dev.onistone.onilink.protocol;

import dev.onistone.onilink.control.ControlJson;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Synthetic baseline fixtures for testing the lab itself; never native acceptance evidence. */
public final class ProtocolFixtureSuites {
    public static Map<String, Object> suite() {
        var codec = CanonicalProtocol.V1_26_45.codec();
        Map<String, BedrockPacket> packets = new LinkedHashMap<>();
        packets.put("join", new PlayStatusPacket()); packets.put("movement", new MovePlayerPacket());
        packets.put("inventory", new InventoryContentPacket()); packets.put("crafting", new CraftingDataPacket());
        packets.put("commands", new AvailableCommandsPacket()); packets.put("packs", new ResourcePackClientResponsePacket());
        packets.put("transfers", new TransferPacket());
        List<Map<String, Object>> fixtures = new ArrayList<>();
        packets.forEach((category, value) -> {
            var packet = PacketPopulator.populate(value);
            var bytes = Unpooled.buffer();
            try {
                codec.tryEncode(CrossProtocolCoverageTest.helperFor(codec), bytes, packet);
                byte[] wire = new byte[bytes.readableBytes()]; bytes.readBytes(wire);
                var definition = codec.getPacketDefinition(packet.getClass());
                String encoded = Base64.getEncoder().encodeToString(wire);
                fixtures.add(Map.of("id", category, "category", category, "clientVersion", codec.getMinecraftVersion(), "backendVersion", codec.getMinecraftVersion(),
                        "direction", definition.getRecipient() == PacketRecipient.SERVER ? "serverbound" : "clientbound", "packetId", definition.getId(), "expectedPacketId", definition.getId(), "input", encoded, "expected", encoded));
            } finally { bytes.release(); ReferenceCountUtil.release(packet); }
        });
        return Map.of("sanitized", true, "fixtures", fixtures);
    }
    public static byte[] archive() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("fixtures.json")); zip.write(ControlJson.encode(suite()).getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry(); }
        return bytes.toByteArray();
    }
}
