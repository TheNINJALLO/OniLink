package dev.onistone.onilink.packet;

import dev.onistone.onilink.control.PacketOrigin;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OniPacketRuleEngineTest {
    @Test
    void evaluatesBothDirectionsAndKeepsTenantScopesIsolated() {
        OniPacketRuleEngine engine = new OniPacketRuleEngine(true, 10, 4, new OniPacketFactory());
        PacketRuleCondition condition = new PacketRuleCondition(Set.of("TextPacket"), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, null);
        engine.replaceRules(List.of(
                new PacketRule("drop-client", "tenant-a", "proxy-a", true, 1,
                        PacketRuleDirection.CLIENTBOUND, PacketRuleStage.PRE_TRANSLATION,
                        condition, new PacketRuleAction(PacketDecisionType.DROP, null, null), ""),
                new PacketRule("drop-server", "tenant-a", "proxy-a", true, 1,
                        PacketRuleDirection.SERVERBOUND, PacketRuleStage.PRE_TRANSLATION,
                        condition, new PacketRuleAction(PacketDecisionType.DROP, null, null), "")));
        TextPacket packet = new TextPacket();
        packet.setMessage("hello");
        PacketContext clientbound = context(PacketRuleDirection.CLIENTBOUND, "tenant-a");
        PacketContext serverbound = context(PacketRuleDirection.SERVERBOUND, "tenant-a");
        assertFalse(engine.evaluate(clientbound, PacketRuleStage.PRE_TRANSLATION, packet, null, null,
                Vector3f.ZERO, 0, 1).forwards());
        assertFalse(engine.evaluate(serverbound, PacketRuleStage.PRE_TRANSLATION, packet, null, null,
                Vector3f.ZERO, 0, 1).forwards());
        assertTrue(engine.evaluate(context(PacketRuleDirection.CLIENTBOUND, "tenant-b"),
                PacketRuleStage.PRE_TRANSLATION, packet, null, null,
                Vector3f.ZERO, 0, 1).forwards());
        assertEquals(3L, engine.metrics().snapshot().get("evaluated"));
        assertEquals(1L, engine.ruleStatistics().stream()
                .filter(value -> value.get("id").equals("drop-client"))
                .findFirst().orElseThrow().get("matchCount"));
    }

    @Test
    void originConditionDoesNotMatchAnotherRelayOrigin() {
        OniPacketRuleEngine engine = new OniPacketRuleEngine(true, 10, 4, new OniPacketFactory());
        PacketRuleCondition condition = new PacketRuleCondition(Set.of("TextPacket"), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(PacketOrigin.BACKEND), Set.of(), Set.of(), null, null);
        engine.replaceRules(List.of(new PacketRule("backend-only", "tenant-a", "proxy-a", true, 1,
                PacketRuleDirection.CLIENTBOUND, PacketRuleStage.PRE_TRANSLATION,
                condition, new PacketRuleAction(PacketDecisionType.DROP, null, null), "")));
        TextPacket packet = new TextPacket();
        packet.setMessage("hello");

        assertFalse(engine.evaluate(context(PacketRuleDirection.CLIENTBOUND, "tenant-a", PacketOrigin.BACKEND),
                PacketRuleStage.PRE_TRANSLATION, packet, null, null,
                Vector3f.ZERO, 0, 1).forwards());
        assertTrue(engine.evaluate(context(PacketRuleDirection.CLIENTBOUND, "tenant-a", PacketOrigin.CLIENT),
                PacketRuleStage.PRE_TRANSLATION, packet, null, null,
                Vector3f.ZERO, 0, 1).forwards());
    }

    private static PacketContext context(PacketRuleDirection direction, String tenant) {
        return context(direction, tenant,
                direction == PacketRuleDirection.CLIENTBOUND ? PacketOrigin.BACKEND : PacketOrigin.CLIENT);
    }

    private static PacketContext context(
            PacketRuleDirection direction, String tenant, PacketOrigin origin) {
        return new PacketContext("Player", "1", "connection", tenant, "proxy-a", "survival",
                1001, 975, direction, origin,
                "PLAYING", 0, 1, 1, true, false);
    }
}
