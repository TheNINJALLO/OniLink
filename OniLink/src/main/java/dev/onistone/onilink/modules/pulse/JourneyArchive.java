package dev.onistone.onilink.modules.pulse;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Process-local bounded presence-like archive; completed records expire automatically. */
public final class JourneyArchive {
    private static final ArrayDeque<Entry> COMPLETED = new ArrayDeque<>();
    private static volatile int capacity = 10_000;
    private static volatile int retentionHours = 72;

    private record Entry(JourneyTrace trace, Instant completedAt) {}

    private JourneyArchive() {}

    public static synchronized void configure(int maximum, int hours) {
        capacity = Math.max(10, maximum);
        retentionHours = Math.max(1, hours);
        prune();
    }

    public static synchronized void complete(JourneyTrace trace) {
        if (trace == null) return;
        trace.mark(JourneyTrace.Stage.DISCONNECTED);
        COMPLETED.addLast(new Entry(trace, Instant.now()));
        prune();
    }

    public static synchronized List<Map<String, Object>> recent(
            String tenantId, String proxyId, boolean revealIdentity, int limit
    ) {
        prune();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Entry entry : COMPLETED.reversed()) {
            if (!entry.trace().tenantId().equals(tenantId) || !entry.trace().proxyId().equals(proxyId)) continue;
            result.add(entry.trace().snapshot(revealIdentity));
            if (result.size() >= Math.max(1, Math.min(limit, capacity))) break;
        }
        return List.copyOf(result);
    }

    private static void prune() {
        Instant cutoff = Instant.now().minusSeconds(retentionHours * 3_600L);
        while (!COMPLETED.isEmpty()
                && (COMPLETED.size() > capacity || COMPLETED.peekFirst().completedAt().isBefore(cutoff))) {
            COMPLETED.removeFirst();
        }
    }
}
