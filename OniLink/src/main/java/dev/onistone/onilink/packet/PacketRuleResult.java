package dev.onistone.onilink.packet;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.List;

/** Fully materialized relay decision. */
public record PacketRuleResult(
        PacketDecisionType decision,
        BedrockPacket packet,
        List<BedrockPacket> before,
        List<BedrockPacket> after,
        List<BedrockPacket> responses,
        List<String> matchedRuleIds,
        String reason
) {
    public PacketRuleResult {
        if (decision == null) throw new IllegalArgumentException("packet decision is required");
        before = List.copyOf(before == null ? List.of() : before);
        after = List.copyOf(after == null ? List.of() : after);
        responses = List.copyOf(responses == null ? List.of() : responses);
        matchedRuleIds = List.copyOf(matchedRuleIds == null ? List.of() : matchedRuleIds);
        reason = reason == null ? "" : reason;
    }

    public boolean forwards() {
        return decision != PacketDecisionType.DROP && decision != PacketDecisionType.CONSUME && packet != null;
    }

    public static PacketRuleResult pass(BedrockPacket packet) {
        return new PacketRuleResult(PacketDecisionType.PASS, packet, List.of(), List.of(), List.of(), List.of(), "");
    }
}
