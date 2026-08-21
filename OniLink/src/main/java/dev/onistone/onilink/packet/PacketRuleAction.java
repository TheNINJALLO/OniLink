package dev.onistone.onilink.packet;

import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ValidatedActionPayload;

/** A typed decision. Semantic actions are required whenever a packet must be constructed. */
public record PacketRuleAction(
        PacketDecisionType type,
        ActionType semanticAction,
        ValidatedActionPayload payload
) {
    public PacketRuleAction {
        if (type == null) throw new IllegalArgumentException("rule action type is required");
        boolean construction = type == PacketDecisionType.REPLACE
                || type == PacketDecisionType.INJECT_BEFORE
                || type == PacketDecisionType.INJECT_AFTER
                || type == PacketDecisionType.CONSUME;
        if (construction != (semanticAction != null && payload != null)) {
            throw new IllegalArgumentException(construction
                    ? type + " requires a typed semantic action and payload"
                    : type + " cannot carry a semantic packet action");
        }
    }
}
