package dev.onistone.onilink.packet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class PacketRuleMetrics {
    private final LongAdder evaluated = new LongAdder();
    private final Map<PacketDecisionType, LongAdder> decisions = new java.util.concurrent.ConcurrentHashMap<>();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder failed = new LongAdder();

    void evaluated() { evaluated.increment(); }
    void decision(PacketDecisionType decision) { decisions.computeIfAbsent(decision, ignored -> new LongAdder()).increment(); }
    void rejected() { rejected.increment(); }
    void failed() { failed.increment(); }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("evaluated", evaluated.sum());
        for (PacketDecisionType type : PacketDecisionType.values()) {
            result.put(type.name().toLowerCase(java.util.Locale.ROOT), decisions.getOrDefault(type, new LongAdder()).sum());
        }
        result.put("rejected", rejected.sum());
        result.put("failed", failed.sum());
        return Map.copyOf(result);
    }
}
