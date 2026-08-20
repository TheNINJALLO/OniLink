package dev.onistone.onilink.protocol;

import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.packet.PartyDestinationCookieResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
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
    void capturesPacketBodiesIdentityAddressesAndWireBytesOnDemand() {
        PacketMonitor monitor = new PacketMonitor(ProtocolRegistry.createDefault(), 10, 1, CLOCK);
        TranslationContext context = new TranslationContext(
                Bedrock_v2168.CODEC, Bedrock_v1001.CODEC, Bedrock_v1001.CODEC);
        TextPacket packet = new TextPacket();
        packet.setMessage("hello from private chat");

        monitor.observe(
                PacketMonitor.Direction.SERVERBOUND,
                packet,
                packet,
                PacketMonitor.Action.FORWARDED,
                context,
                new PacketMonitor.CaptureContext(
                        "TestPlayer",
                        "2535438695543476",
                        "174.84.137.109:51120",
                        "survival",
                        "45.143.196.160:25570",
                        new byte[]{1, 2, 3, 4},
                        2
                )
        );

        Map<String, Object> snapshot = monitor.snapshot(Map.of());
        List<?> records = (List<?>) snapshot.get("records");
        assertEquals(1, records.size());
        assertTrue(records.get(0).toString().contains("automatic_codec_match"));
        assertTrue(records.get(0).toString().contains("TestPlayer"));
        assertTrue(records.get(0).toString().contains("2535438695543476"));
        assertTrue(records.get(0).toString().contains("174.84.137.109:51120"));
        assertFalse(records.get(0).toString().contains("hello from private chat"));

        Map<String, Object> detailed = monitor.snapshot(Map.of("includeDetails", "true"));
        Map<?, ?> detail = (Map<?, ?>) ((List<?>) detailed.get("records")).get(0);
        assertTrue(String.valueOf(detail.get("decodedPayload")).contains("hello from private chat"));
        assertEquals("AQIDBA==", detail.get("wireBytesBase64"));
        assertEquals(4, detail.get("wireBytesLength"));
        assertEquals(2, detail.get("wireHeaderLength"));
        assertEquals(false, detail.get("tokenRedacted"));
        assertTrue((Boolean) snapshot.get("routeAvailable"));
        assertFalse(((List<?>) snapshot.get("catalog")).isEmpty());
    }

    @Test
    void redactsAuthenticationPacketsAndTokenShapedValuesBeforeStorage() {
        PacketMonitor monitor = new PacketMonitor(ProtocolRegistry.createDefault(), 10, 1, CLOCK);
        TranslationContext context = new TranslationContext(
                Bedrock_v2168.CODEC, Bedrock_v2168.CODEC, Bedrock_v2168.CODEC);
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJwbGF5ZXIifQ.signature-value";
        LoginPacket login = new LoginPacket();
        login.setProtocolVersion(2168);
        login.setClientJwt(jwt);

        monitor.observe(
                PacketMonitor.Direction.SERVERBOUND,
                login,
                login,
                PacketMonitor.Action.FORWARDED,
                context,
                new PacketMonitor.CaptureContext(
                        "Player", "123", "127.0.0.1:19132", "survival",
                        "127.0.0.1:19133", jwt.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1)
        );

        TextPacket chat = new TextPacket();
        chat.setMessage("Bearer abcdefghijklmnopqrstuvwxyz.1234567890");
        monitor.observe(
                PacketMonitor.Direction.SERVERBOUND,
                chat,
                chat,
                PacketMonitor.Action.FORWARDED,
                context,
                new PacketMonitor.CaptureContext(
                        "Player", "123", "127.0.0.1:19132", "survival",
                        "127.0.0.1:19133",
                        chat.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8), 1)
        );

        Map<String, Object> detailed = monitor.snapshot(Map.of("includeDetails", "true"));
        assertFalse(detailed.toString().contains(jwt));
        assertFalse(detailed.toString().contains("abcdefghijklmnopqrstuvwxyz.1234567890"));
        List<?> records = (List<?>) detailed.get("records");
        assertTrue(records.stream().allMatch(record -> Boolean.TRUE.equals(((Map<?, ?>) record).get("tokenRedacted"))));
        assertTrue(records.stream().allMatch(record -> "".equals(((Map<?, ?>) record).get("wireBytesBase64"))));
        Map<?, ?> summary = (Map<?, ?>) detailed.get("summary");
        assertEquals(2L, summary.get("tokenRedactions"));
    }

    @Test
    void supportSafeSnapshotOmitsCapturedIdentityAndPacketBodies() {
        PacketMonitor monitor = new PacketMonitor(ProtocolRegistry.createDefault(), 10, 1, CLOCK);
        TranslationContext context = new TranslationContext(
                Bedrock_v2168.CODEC, Bedrock_v2168.CODEC, Bedrock_v2168.CODEC);
        TextPacket packet = new TextPacket();
        packet.setMessage("private support bundle chat");
        monitor.observe(
                PacketMonitor.Direction.SERVERBOUND,
                packet,
                packet,
                PacketMonitor.Action.FORWARDED,
                context,
                new PacketMonitor.CaptureContext(
                        "PrivatePlayer", "123456789", "10.0.0.1:19132", "survival",
                        "10.0.0.2:19132", new byte[]{9, 8, 7}, 1)
        );

        Map<String, Object> snapshot = monitor.snapshot(Map.of(
                "includeDetails", "true",
                "redactSensitive", "true"
        ));
        assertFalse(snapshot.toString().contains("PrivatePlayer"));
        assertFalse(snapshot.toString().contains("123456789"));
        assertFalse(snapshot.toString().contains("private support bundle chat"));
        assertFalse(snapshot.toString().contains("CQgH"));
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
