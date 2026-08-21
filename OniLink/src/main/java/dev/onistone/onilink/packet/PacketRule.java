package dev.onistone.onilink.packet;

import java.util.Locale;

public record PacketRule(
        String id,
        String tenantId,
        String proxyId,
        boolean enabled,
        int priority,
        PacketRuleDirection direction,
        PacketRuleStage stage,
        PacketRuleCondition condition,
        PacketRuleAction action,
        String description
) {
    public PacketRule {
        id = identifier(id, "rule ID");
        tenantId = identifier(tenantId, "tenant ID");
        proxyId = identifier(proxyId, "proxy ID");
        if (direction == null || stage == null || condition == null || action == null) {
            throw new IllegalArgumentException("rule direction, stage, condition, and action are required");
        }
        if (priority < -100_000 || priority > 100_000) throw new IllegalArgumentException("rule priority is out of range");
        description = description == null ? "" : description.trim();
        if (description.length() > 512) throw new IllegalArgumentException("rule description is too long");
        if (direction == PacketRuleDirection.SERVERBOUND
                && action.type() != PacketDecisionType.PASS
                && action.type() != PacketDecisionType.DROP) {
            throw new IllegalArgumentException("serverbound rules currently support PASS or DROP only; no reviewed semantic input builder exists");
        }
        if (stage == PacketRuleStage.PRE_TRANSLATION
                && action.type() != PacketDecisionType.PASS
                && action.type() != PacketDecisionType.DROP) {
            throw new IllegalArgumentException(
                    "pre-translation rules currently support PASS or DROP only; constructed packets must use the destination codec");
        }
    }

    boolean matchesScope(PacketContext context) {
        return tenantId.equals(context.tenantId().toLowerCase(Locale.ROOT))
                && proxyId.equals(context.proxyId().toLowerCase(Locale.ROOT));
    }

    private static String identifier(String value, String label) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!clean.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IllegalArgumentException(label + " is invalid");
        return clean;
    }
}
