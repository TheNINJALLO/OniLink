package dev.onistone.onilink.modules.pulse;

import dev.onistone.onilink.protocol.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Bounded route counters; timing and allocation are sampled once per 256 translated packets. */
public final class RelayMetrics {
    private static final Map<String, Counters> routes = new ConcurrentHashMap<>();
    private static final com.sun.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private static final Counters overflow = new Counters();
    private RelayMetrics() { }
    private static class Counters {
        final LongAdder packets = new LongAdder(), failures = new LongAdder(), dropped = new LongAdder(), nanos = new LongAdder(), samples = new LongAdder(), allocated = new LongAdder();
        final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, Object> view(String route) { return Map.of("route", route, "packets", packets.sum(), "failures", failures.sum(), "dropped", dropped.sum(),
                "sampledNanos", nanos.sum(), "samples", samples.sum(), "sampledAllocatedBytes", allocated.sum()); }
    }
    public static PacketTranslator measure(PacketTranslator delegate, String client, String backend) {
        Counters serverbound = counters(client + "->" + backend + ":serverbound");
        Counters clientbound = counters(client + "->" + backend + ":clientbound");
        return new PacketTranslator() {
            public BedrockPacket translateServerbound(BedrockPacket packet, TranslationContext context) { return invoke(serverbound, delegate, packet, context, 0); }
            public BedrockPacket translateClientbound(BedrockPacket packet, TranslationContext context) { return invoke(clientbound, delegate, packet, context, 1); }
            public AvailableCommandsPacket translateCommandTree(AvailableCommandsPacket packet, TranslationContext context) { return (AvailableCommandsPacket) invoke(clientbound, delegate, packet, context, 2); }
        };
    }
    private static synchronized Counters counters(String key) {
        var existing = routes.get(key); if (existing != null) return existing;
        if (routes.size() >= 1024) return overflow;
        var counters = new Counters(); routes.put(key, counters); return counters;
    }
    private static BedrockPacket invoke(Counters counters, PacketTranslator delegate, BedrockPacket packet, TranslationContext context, int direction) {
        counters.packets.increment();
        boolean sample = (counters.sequence.incrementAndGet() & 255) == 0;
        long start = sample ? System.nanoTime() : 0;
        long allocation = sample ? allocated() : -1;
        try {
            var result = direction == 0 ? delegate.translateServerbound(packet, context) : direction == 1
                    ? delegate.translateClientbound(packet, context) : delegate.translateCommandTree((AvailableCommandsPacket) packet, context);
            if (result == null) counters.dropped.increment(); return result;
        }
        catch (RuntimeException | LinkageError failure) { counters.failures.increment(); throw failure; }
        finally { if (sample) { counters.samples.increment(); counters.nanos.add(System.nanoTime() - start); long end = allocated(); if (allocation >= 0 && end >= allocation) counters.allocated.add(end - allocation); } }
    }
    private static long allocated() { return threads != null && threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled()
            ? threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1; }
    public static List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>(); routes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> result.add(e.getValue().view(e.getKey())));
        if (overflow.packets.sum() != 0) result.add(overflow.view("overflow"));
        return List.copyOf(result);
    }
}
