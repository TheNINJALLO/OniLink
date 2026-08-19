package dev.onistone.onilink.protocol;

import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.packet.PartyDestinationCookieResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PacketMonitorTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-19T22:00:00Z"), ZoneOffset.UTC);

    @Test
    void matchesSharedPacketModelsAndNeverStoresPayloadValues() {
        PacketMonitor monitor = new PacketMonitor(ProtocolRegistry.createDefault(), 10, 1, CLOCK);
        TranslationContext context = new TranslationContext(
                Bedrock_v2168.CODEC, Bedrock_v1001.CODEC, Bedrock_v1001.CODEC);
        TextPacket packet = new TextPacket();
        packet.setMessage("private-chat-token-that-must-not-be-stored");

        monitor.observe(
                PacketMonitor.Direction.SERVERBOUND,
                packet,
                packet,
                PacketMonitor.Action.FORWARDED,
                context,
                "TestPlayer",
                "survival"
        );

        Map<String, Object> snapshot = monitor.snapshot(Map.of());
        List<?> records = (List<?>) snapshot.get("records");
        assertEquals(1, records.size());
        assertTrue(records.get(0).toString().contains("automatic_codec_match"));
        assertTrue(records.get(0).toString().contains("TestPlayer"));
        assertFalse(snapshot.toString().contains("private-chat-token"));
        assertTrue((Boolean) snapshot.get("routeAvailable"));
        assertFalse(((List<?>) snapshot.get("catalog")).isEmpty());
    }

    @Test
    void flagsOneSidedPacketsForReviewedTranslation() {
        PacketMonitor monitor = new PacketMonitor(ProtocolRegistry.createDefault(), 10, 1, CLOCK);
        TranslationContext context = new TranslationContext(
                Bedrock_v1001.CODEC, Bedrock_v975.CODEC, Bedrock_v975.CODEC);
        PartyDestinationCookieResponsePacket packet = new PartyDestinationCookieResponsePacket();

        monitor.observe(
                PacketMonitor.Direction.SERVERBOUND,
                packet,
                null,
                PacketMonitor.Action.DROPPED,
                context,
                "TestPlayer",
                "legacy"
        );

        Map<?, ?> record = (Map<?, ?>) ((List<?>) monitor.snapshot(Map.of()).get("records")).get(0);
        assertEquals("review_required", record.get("status"));
        assertEquals("dropped", record.get("action"));
        assertEquals(-1, record.get("targetPacketId"));
    }

    @Test
    void boundsTheRollingRecordWindowAndReportsEvictions() {
        PacketMonitor monitor = new PacketMonitor(ProtocolRegistry.createDefault(), 2, 1, CLOCK);
        TranslationContext context = new TranslationContext(
                Bedrock_v2168.CODEC, Bedrock_v2168.CODEC, Bedrock_v2168.CODEC);
        for (int index = 0; index < 3; index++) {
            TextPacket packet = new TextPacket();
            packet.setMessage("payload-" + index);
            monitor.observe(
                    PacketMonitor.Direction.SERVERBOUND,
                    packet,
                    packet,
                    PacketMonitor.Action.FORWARDED,
                    context,
                    "Player",
                    "survival"
            );
        }

        Map<String, Object> snapshot = monitor.snapshot(Map.of("limit", "10"));
        assertEquals(2, ((List<?>) snapshot.get("records")).size());
        Map<?, ?> summary = (Map<?, ?>) snapshot.get("summary");
        assertEquals(3L, summary.get("observedPackets"));
        assertEquals(3L, summary.get("nativeMatches"));
        assertEquals(0L, summary.get("automaticMatches"));
        assertEquals(1L, summary.get("evictedRecords"));
    }

    @Test
    void samplesMovementRecordsButKeepsCompleteAggregateCounts() {
        PacketMonitor monitor = new PacketMonitor(ProtocolRegistry.createDefault(), 10, 2, CLOCK);
        TranslationContext context = new TranslationContext(
                Bedrock_v2168.CODEC, Bedrock_v2168.CODEC, Bedrock_v2168.CODEC);

        for (int index = 0; index < 5; index++) {
            PlayerAuthInputPacket packet = new PlayerAuthInputPacket();
            monitor.observe(
                    PacketMonitor.Direction.SERVERBOUND,
                    packet,
                    packet,
                    PacketMonitor.Action.FORWARDED,
                    context,
                    "Player",
                    "survival"
            );
        }

        Map<String, Object> snapshot = monitor.snapshot(Map.of("status", "native"));
        assertEquals(3, ((List<?>) snapshot.get("records")).size());
        Map<?, ?> summary = (Map<?, ?>) snapshot.get("summary");
        assertEquals(5L, summary.get("observedPackets"));
        assertEquals(2L, summary.get("sampledOut"));
        Map<?, ?> aggregate = (Map<?, ?>) ((List<?>) snapshot.get("matches")).get(0);
        assertEquals(5L, aggregate.get("count"));
    }
}
