package dev.onistone.onilink.packet;

import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free relay-path evaluation over atomically replaced immutable rule snapshots. */
public final class OniPacketRuleEngine {
    private final boolean enabled;
    private final int maximumRules;
    private final int maximumInjectedPackets;
    private final OniPacketFactory factory;
    private final AtomicReference<List<PacketRule>> rules = new AtomicReference<>(List.of());
    private final PacketRuleMetrics metrics = new PacketRuleMetrics();
    private final java.util.concurrent.ConcurrentHashMap<String, RuleStatistics> ruleStatistics =
            new java.util.concurrent.ConcurrentHashMap<>();

    public OniPacketRuleEngine(boolean enabled, int maximumRules, int maximumInjectedPackets, OniPacketFactory factory) {
        if (maximumRules < 1 || maximumInjectedPackets < 1) throw new IllegalArgumentException("rule limits must be positive");
        this.enabled = enabled;
        this.maximumRules = maximumRules;
        this.maximumInjectedPackets = maximumInjectedPackets;
        this.factory = factory == null ? new OniPacketFactory() : factory;
    }

    public boolean enabled() { return enabled; }
    public List<PacketRule> rules() { return rules.get(); }
    public PacketRuleMetrics metrics() { return metrics; }

    public void replaceRules(List<PacketRule> replacement) {
        List<PacketRule> safe = new ArrayList<>(replacement == null ? List.of() : replacement);
        if (safe.size() > maximumRules) throw new IllegalArgumentException("packet rule count exceeds " + maximumRules);
        java.util.HashSet<String> identities = new java.util.HashSet<>();
        for (PacketRule rule : safe) {
            String identity = rule.tenantId() + '\0' + rule.proxyId() + '\0' + rule.id();
            if (!identities.add(identity)) throw new IllegalArgumentException("duplicate scoped packet rule ID " + rule.id());
        }
        safe.sort(Comparator.comparingInt(PacketRule::priority).reversed().thenComparing(PacketRule::id));
        rules.set(List.copyOf(safe));
        ruleStatistics.keySet().retainAll(identities);
    }

    public List<Map<String, Object>> ruleStatistics() {
        return rules.get().stream().map(rule -> {
            RuleStatistics statistics = ruleStatistics.get(ruleIdentity(rule));
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("id", rule.id());
            result.put("matchCount", statistics == null ? 0 : statistics.matches.get());
            result.put("lastMatched", statistics == null || statistics.lastMatchedMillis.get() == 0
                    ? "" : java.time.Instant.ofEpochMilli(statistics.lastMatchedMillis.get()).toString());
            return Map.copyOf(result);
        }).toList();
    }

    public PacketRuleResult evaluate(
            PacketContext context,
            PacketRuleStage stage,
            BedrockPacket input,
            BedrockCodec packetCodec,
            BedrockCodec responseCodec,
            Vector3f fallbackPosition,
            long syntheticEntityId,
            int formId
    ) {
        if (!enabled || input == null || context.origin() != dev.onistone.onilink.control.PacketOrigin.CLIENT
                && context.origin() != dev.onistone.onilink.control.PacketOrigin.BACKEND) {
            return PacketRuleResult.pass(input);
        }
        metrics.evaluated();
        BedrockPacket current = input;
        List<BedrockPacket> before = new ArrayList<>();
        List<BedrockPacket> after = new ArrayList<>();
        List<BedrockPacket> responses = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        PacketDecisionType aggregate = PacketDecisionType.PASS;
        try {
            for (PacketRule rule : rules.get()) {
                if (!rule.enabled() || rule.direction() != context.direction() || rule.stage() != stage
                        || !rule.matchesScope(context)
                        || !rule.condition().matches(context, current.getClass().getSimpleName())) continue;
                matched.add(rule.id());
                ruleStatistics.computeIfAbsent(ruleIdentity(rule), ignored -> new RuleStatistics()).matched();
                PacketDecisionType type = rule.action().type();
                if (type == PacketDecisionType.PASS) continue;
                if (type == PacketDecisionType.DROP) {
                    metrics.decision(type);
                    releaseAll(before, after, responses);
                    return new PacketRuleResult(type, null, List.of(), List.of(), List.of(), matched,
                            "dropped by typed rule " + rule.id());
                }
                PacketBuildResult built = factory.buildClientbound(
                        type == PacketDecisionType.CONSUME ? responseCodec : packetCodec,
                        rule.action().semanticAction(), rule.action().payload(), fallbackPosition,
                        syntheticEntityId, formId);
                if (built.status() != PacketBuildResult.Status.SUPPORTED) {
                    metrics.rejected();
                    releaseAll(before, after, responses);
                    return new PacketRuleResult(PacketDecisionType.PASS, input, List.of(), List.of(), List.of(), matched,
                            "rule " + rule.id() + " rejected: " + built.reason());
                }
                if (before.size() + after.size() + responses.size() + built.packets().size() > maximumInjectedPackets) {
                    metrics.rejected();
                    releaseAll(before, after, responses);
                    return new PacketRuleResult(PacketDecisionType.PASS, input, List.of(), List.of(), List.of(), matched,
                            "rule injection limit exceeded");
                }
                switch (type) {
                    case REPLACE -> {
                        if (built.packets().size() != 1) {
                            metrics.rejected();
                            releaseAll(before, after, responses);
                            return new PacketRuleResult(PacketDecisionType.PASS, input, List.of(), List.of(), List.of(), matched,
                                    "replacement action must build exactly one packet");
                        }
                        current = built.packets().getFirst();
                    }
                    case INJECT_BEFORE -> before.addAll(built.packets());
                    case INJECT_AFTER -> after.addAll(built.packets());
                    case CONSUME -> {
                        responses.addAll(built.packets());
                        metrics.decision(type);
                        return new PacketRuleResult(type, null, before, after, responses, matched,
                                "consumed by typed rule " + rule.id());
                    }
                    default -> throw new IllegalStateException("unexpected construction decision " + type);
                }
                aggregate = type;
            }
            metrics.decision(aggregate);
            return new PacketRuleResult(aggregate, current, before, after, responses, matched, "");
        } catch (RuntimeException exception) {
            metrics.failed();
            releaseAll(before, after, responses);
            return new PacketRuleResult(PacketDecisionType.DROP, null, List.of(), List.of(), List.of(), matched,
                    "rule evaluation failed closed: " + exception.getClass().getSimpleName());
        }
    }

    private static void releaseAll(List<BedrockPacket>... collections) {
        for (List<BedrockPacket> collection : collections) {
            for (BedrockPacket packet : collection) ReferenceCountUtil.release(packet);
        }
    }

    private static String ruleIdentity(PacketRule rule) {
        return rule.tenantId() + '\0' + rule.proxyId() + '\0' + rule.id();
    }

    private static final class RuleStatistics {
        private final AtomicLong matches = new AtomicLong();
        private final AtomicLong lastMatchedMillis = new AtomicLong();

        private void matched() {
            matches.incrementAndGet();
            lastMatchedMillis.set(System.currentTimeMillis());
        }
    }
}
