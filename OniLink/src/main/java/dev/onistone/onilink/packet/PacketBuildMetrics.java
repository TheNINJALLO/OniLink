package dev.onistone.onilink.packet;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Low-cardinality packet factory counters. */
public final class PacketBuildMetrics {
    private final LongAdder attempted = new LongAdder();
    private final LongAdder built = new LongAdder();
    private final LongAdder unsupported = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder encodeFailed = new LongAdder();

    void mark(PacketBuildResult.Status status) {
        attempted.increment();
        switch (status) {
            case SUPPORTED -> built.increment();
            case UNSUPPORTED -> unsupported.increment();
            case REJECTED -> rejected.increment();
            case ENCODE_FAILED -> encodeFailed.increment();
        }
    }

    public Map<String, Long> snapshot() {
        return Map.of(
                "attempted", attempted.sum(),
                "built", built.sum(),
                "unsupported", unsupported.sum(),
                "rejected", rejected.sum(),
                "encodeFailed", encodeFailed.sum());
    }
}
