package dev.onistone.onilink.geyser.forwarding;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Atomically consumes bridge/session/nonce tuples and fails closed when capacity is exhausted. */
public final class ReplayCache {
    private final int maximumEntries;
    private final Map<String, Long> entries = new HashMap<>();

    public ReplayCache(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("replay cache capacity must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    public synchronized boolean consume(OniForwardVerifier.Claims claims, long nowMs, long retentionMs) {
        Iterator<Map.Entry<String, Long>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < nowMs) {
                iterator.remove();
            }
        }
        String key = claims.bridgeId() + '\0' + claims.sessionId() + '\0' + claims.nonce();
        if (entries.containsKey(key) || entries.size() >= maximumEntries) {
            return false;
        }
        entries.put(key, retentionMs);
        return true;
    }

    public synchronized int size() {
        return entries.size();
    }
}
