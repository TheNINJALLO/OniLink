package dev.onistone.onilink.control.wire;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded insertion-order nonce cache. */
public final class ControlReplayCache {
    private final int maximumEntries;
    private final long retentionMillis;
    private final Clock clock;
    private final LinkedHashMap<String, Long> nonces = new LinkedHashMap<>();

    public ControlReplayCache(int maximumEntries, long retentionMillis, Clock clock) {
        if (maximumEntries < 1 || retentionMillis < 1 || clock == null) throw new IllegalArgumentException("invalid replay cache limits");
        this.maximumEntries = maximumEntries;
        this.retentionMillis = retentionMillis;
        this.clock = clock;
    }

    public synchronized boolean consume(String nonce) {
        long now = clock.millis();
        Iterator<Map.Entry<String, Long>> iterator = nonces.entrySet().iterator();
        while (iterator.hasNext() && iterator.next().getValue() < now) iterator.remove();
        if (nonces.containsKey(nonce) || nonces.size() >= maximumEntries) return false;
        nonces.put(nonce, now + retentionMillis);
        return true;
    }

    public synchronized int size() {
        return nonces.size();
    }
}
