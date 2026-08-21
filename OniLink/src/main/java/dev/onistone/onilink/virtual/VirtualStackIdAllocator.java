package dev.onistone.onilink.virtual;

import java.util.concurrent.atomic.AtomicInteger;

public final class VirtualStackIdAllocator {
    private final AtomicInteger next = new AtomicInteger(1_500_000_000);

    public int next() {
        return next.updateAndGet(value -> value >= 2_000_000_000 ? 1_500_000_000 : value + 1);
    }
}
