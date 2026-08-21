package dev.onistone.onilink.platform.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Ordered, bounded dispatcher. Overflow drops the newest event and is observable. */
public final class BoundedEventBus implements AutoCloseable {
    private final int capacity;
    private final Executor executor;
    private final ArrayDeque<OniEvent> queue = new ArrayDeque<>();
    private final Map<OniEventType, List<Consumer<OniEvent>>> subscribers = new EnumMap<>(OniEventType.class);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;

    public BoundedEventBus(int capacity, Executor executor) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        if (executor == null) throw new IllegalArgumentException("executor is required");
        this.capacity = capacity;
        this.executor = executor;
    }

    public synchronized AutoCloseable subscribe(OniEventType type, Consumer<OniEvent> consumer) {
        if (closed) throw new IllegalStateException("event bus is closed");
        subscribers.computeIfAbsent(type, ignored -> new ArrayList<>()).add(consumer);
        return () -> unsubscribe(type, consumer);
    }

    public boolean publish(OniEvent event) {
        synchronized (this) {
            if (closed) return false;
            if (queue.size() >= capacity) {
                dropped.incrementAndGet();
                return false;
            }
            queue.addLast(event);
            accepted.incrementAndGet();
        }
        scheduleDrain();
        return true;
    }

    public Map<String, Long> metrics() {
        synchronized (this) {
            return Map.of("accepted", accepted.get(), "dropped", dropped.get(), "queued", (long) queue.size(),
                    "capacity", (long) capacity);
        }
    }

    private synchronized void unsubscribe(OniEventType type, Consumer<OniEvent> consumer) {
        List<Consumer<OniEvent>> listeners = subscribers.get(type);
        if (listeners != null) listeners.remove(consumer);
    }

    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) executor.execute(this::drain);
    }

    private void drain() {
        try {
            while (true) {
                OniEvent event;
                List<Consumer<OniEvent>> listeners;
                synchronized (this) {
                    event = queue.pollFirst();
                    if (event == null) return;
                    listeners = List.copyOf(subscribers.getOrDefault(event.type(), List.of()));
                }
                for (Consumer<OniEvent> listener : listeners) {
                    try {
                        listener.accept(event);
                    } catch (RuntimeException ignored) {
                        // One consumer cannot break ordering or isolate other consumers.
                    }
                }
            }
        } finally {
            draining.set(false);
            synchronized (this) {
                if (!queue.isEmpty() && !closed) scheduleDrain();
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        queue.clear();
        subscribers.clear();
    }
}
