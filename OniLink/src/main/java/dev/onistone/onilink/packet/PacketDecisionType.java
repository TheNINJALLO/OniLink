package dev.onistone.onilink.packet;

public enum PacketDecisionType {
    PASS,
    DROP,
    REPLACE,
    INJECT_BEFORE,
    INJECT_AFTER,
    CONSUME
}
