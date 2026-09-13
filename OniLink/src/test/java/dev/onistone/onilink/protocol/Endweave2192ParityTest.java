package dev.onistone.onilink.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire fixtures written from Endweave's bedrock-protocol v0.1.0 schema, commit
 * 9c93e0c6e711b161df6eb1934270936b48527120. Expected bytes never use an OniLink encoder.
 * See docs/CROSS_VERSION_1_26_50.md for scope and the remaining live acceptance.
 */
class Endweave2192ParityTest {
    private static final BedrockCodec NEW = CanonicalProtocol.V1_26_50.codec();
    private static final BedrockCodec OLD = CanonicalProtocol.V1_26_45.codec();
    private static final String AIR = "00".repeat(8);

    @Test
    void populatedInventoryActionsRemoveBothOldSourceMarkers() {
        // Normal transaction, one container source (signed ID -1), slot 3, two air descriptors.
        String incoming = "00 00 00 01 00 01 ff 00 03 " + AIR + AIR;
        String outgoing = "00 00 01 00 01 01 00 01 01 ff 01 00 03 " + AIR + AIR;
        InventoryTransactionPacket packet = (InventoryTransactionPacket) checkWire(NEW, 30, incoming);
        assertEquals(-1, packet.getActions().getFirst().getSource().getContainerId());
        assertEquals(3, packet.getActions().getFirst().getSlot());
        assertRelay(30, incoming, outgoing, true);
    }

    @Test
    void embeddedItemUseConsumesTheHandBeforeTheItemDescriptor() {
        String incoming = inputPrefix(false) + "01 00 00 00 02 01 02 80 01 04 01 04 01 "
                + AIR + "00".repeat(24) + "00 01 00 " + inputSuffix(false);
        String outgoing = inputPrefix(true) + "01 00 00 01 01 00 02 01 02 80 01 04 01 04 "
                + AIR + "00".repeat(24) + "00 01 00 " + inputSuffix(true);
        PlayerAuthInputPacket packet = (PlayerAuthInputPacket) checkWire(NEW, 144, incoming);
        assertEquals(1, packet.getItemUseTransaction().getActionType());
        assertEquals(2, packet.getItemUseTransaction().getHotbarSlot());
        assertEquals(InventoryTransactionPacket.HandSlot.OFF_HAND, packet.getItemUseTransaction().getHandSlot());
        assertRelay(144, incoming, outgoing, true);
    }

    @Test
    void worldPresentationUsesTheNewFieldsAndDefaults() {
        assertRelay(74, "54 c601 00 03 426f73 00 0000403f 02 01",
                "54 00 03 426f73 00 0000403f 02 01", false);
        assertRelay(180, "01 03 736b79 8006 7f 02 04 " + "00".repeat(16),
                "01 03 736b79 7f 8007 02 04 " + "00".repeat(16) + "00", false);
        assertRelay(198, "01 03 63616d 00 " + "00".repeat(20),
                "01 03 63616d 00 " + "00".repeat(22), false);
        assertRelay(86, "04 68617270 10 20 30 0000003f 0000a03f 02 01 1300000000000000",
                "04 68617270 10 20 30 0000003f 0000a03f 02 00 01 1300000000000000 00", false);
        assertRelay(111, "07 01 0000c03f 00 00 00 00 00 01 00 00 00",
                "07 01 0000c03f 00 00 00 00 00 01 00 00 00 00", false);
    }

    @Test
    void chunkHeightMapsPreserveEveryCellAndTheOpaqueChunkPayload() {
        byte[] cells = new byte[256];
        for (int index = 0; index < cells.length; index++) cells[index] = (byte) index;
        String fixed = HexFormat.of().formatHex(cells);
        StringBuilder rows = new StringBuilder();
        for (int row = 0; row < 16; row++) rows.append("10").append(fixed, row * 32, (row + 1) * 32);
        // Cache disabled, overworld, center (0,0,0), one chunk at (1,-1,2), success,
        // opaque payload "abc", both complete maps, blob ID 7.
        String prefix = "00 00 " + "00".repeat(12) + "01 01 ff 02 01 01 03 616263 01 01 ";
        String suffix = "01 0700000000000000";
        assertRelay(174, prefix + fixed + "01 01 " + fixed + suffix,
                prefix + rows + "01 01 " + rows + suffix, false);
    }

    @Test
    void unreviewedNextProtocolIsNotAliasedToThePreview() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();
        assertTrue(registry.findClientCodec(2208).isEmpty());
        assertTrue(registry.findBinding(2208, 2169).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renamedInventorySlotsCarryAnOptionalFilteredName(boolean filtered) {
        // One success, request -1, one anvil-input container, one slot, stack ID 7,
        // custom name "Sword", optional filtered name "Blade", durability correction -2.
        String slot = "00 01 02 ";
        String tail = "01 0e 05 53776f7264 " + (filtered ? "01 05 426c616465 " : "00 ") + "03";
        String incoming = "01 00 01 01 01 00 00 01 " + slot + tail;
        String outgoing = "01 00 01 01 01 01 00 00 01 " + slot + "01 " + tail;
        ItemStackResponsePacket packet = (ItemStackResponsePacket) checkWire(NEW, 148, incoming);
        assertEquals(filtered ? "Blade" : null,
                packet.getEntries().getFirst().getContainers().getFirst().getItems().getFirst().getFilteredCustomName());
        assertRelay(148, outgoing, incoming, false);
    }

    @Test
    void diagnosticsCarryTheOptionalSystemCategoryList() {
        // Nine floats, no memory/entity/system timings, present category ["tick", index 7], no scopes.
        String payload = "00".repeat(36) + "00 00 00 01 01 04 7469636b 0700000000000000 00";
        ServerboundDiagnosticsPacket packet = (ServerboundDiagnosticsPacket) checkWire(NEW, 315, payload);
        assertEquals("tick", packet.getSystemCategories().getFirst().getCategoryName());
        assertRelay(315, payload, payload, true);
    }

    @Test
    void newContainerCloseDecodesAndStopsBeforeAnOlderBackend() {
        ContainerClosePacket packet = (ContainerClosePacket) checkWire(NEW, 47, "01 25 00");
        assertEquals(37, packet.getType().getId());
        assertNull(ModernClientTo2168Translator.INSTANCE.translateServerbound(packet, null));
    }

    private static String inputPrefix(boolean old) {
        // Rot/pos/move/head; one PERFORM_ITEM_INTERACTION flag; mouse/normal/touch;
        // interact rotation, tick 5, delta; constant wrapper of the item-use optional on 2169.
        return "00".repeat(32) + (old ? "01 " : "") + "01 44 01 00 00 "
                + "00".repeat(8) + "05 " + "00".repeat(12) + (old ? "01 " : "");
    }

    private static String inputSuffix(boolean old) {
        return (old ? "01 00 01 00 01 00 01 00 " : "00 00 00 00 ") + "00".repeat(28);
    }

    private static BedrockPacket checkWire(BedrockCodec codec, int packetId, String hex) {
        byte[] expected = bytes(hex);
        ByteBuf input = Unpooled.wrappedBuffer(expected);
        ByteBuf output = Unpooled.buffer();
        BedrockPacket packet = null;
        boolean success = false;
        try {
            packet = codec.tryDecode(CrossProtocolCoverageTest.helperFor(codec), input, packetId);
            assertFalse(packet instanceof UnknownPacket, "schema fixture failed to decode: " + packetId);
            assertFalse(input.isReadable(), "unread source bytes");
            codec.tryEncode(CrossProtocolCoverageTest.helperFor(codec), output, packet);
            assertArrayEquals(expected, ByteBufUtil.getBytes(output), "schema fixture changed on encode");
            success = true;
            return packet;
        } finally {
            input.release();
            output.release();
            if (!success) ReferenceCountUtil.release(packet);
        }
    }

    private static void assertRelay(int packetId, String source, String target, boolean serverbound) {
        BedrockPacket decoded = checkWire(serverbound ? NEW : OLD, packetId, source);
        ProtocolBinding binding = ProtocolRegistry.createDefault().findBinding(2192, 2169).orElseThrow();
        TranslationContext context = new TranslationContext(NEW, OLD, OLD);
        BedrockPacket translated = serverbound ? binding.translator().translateServerbound(decoded, context)
                : binding.translator().translateClientbound(decoded, context);
        ByteBuf output = Unpooled.buffer();
        try {
            assertNotNull(translated);
            BedrockCodec targetCodec = serverbound ? OLD : NEW;
            targetCodec.tryEncode(CrossProtocolCoverageTest.helperFor(targetCodec), output, translated);
            assertArrayEquals(bytes(target), ByteBufUtil.getBytes(output), "relay differs from upstream schema");
            ReferenceCountUtil.release(checkWire(targetCodec, packetId, target));
        } finally {
            output.release();
            ReferenceCountUtil.release(decoded);
        }
    }

    private static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex.replace(" ", ""));
    }
}
