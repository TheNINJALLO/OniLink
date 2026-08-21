package dev.onistone.onilink.modules.pulse;

import dev.onistone.onilink.modules.ProxyOperations;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Query/export boundary over active traces and the bounded completed archive. */
public final class JourneyService {
    private final ProxyOperations proxy;
    private final int maximum;

    public JourneyService(ProxyOperations proxy, int maximum, int retentionHours) {
        this.proxy = proxy;
        this.maximum = maximum;
        JourneyArchive.configure(maximum, retentionHours);
    }

    public Map<String, Object> snapshot(
            PlatformDatabase.Scope scope, boolean revealIdentity, Map<String, String> filters
    ) {
        List<Map<String, Object>> journeys = new ArrayList<>(JourneyArchive.recent(
                scope.tenantId(), scope.proxyId(), revealIdentity, maximum));
        Object activeValue = proxy.backendRegistry().get("activeJourneys");
        if (activeValue instanceof List<?> active) {
            for (Object item : active) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    journeys.add(Map.copyOf(copy));
                }
            }
        }
        String backend = filters.getOrDefault("backend", "").trim();
        String player = filters.getOrDefault("player", "").trim();
        boolean failuresOnly = Boolean.parseBoolean(filters.getOrDefault("failures", "false"));
        List<Map<String, Object>> filtered = journeys.stream()
                .filter(item -> backend.isBlank() || backend.equalsIgnoreCase(String.valueOf(item.get("backend"))))
                .filter(item -> player.isBlank() || String.valueOf(item.get("player")).toLowerCase()
                        .contains(player.toLowerCase()))
                .filter(item -> !failuresOnly || !String.valueOf(item.getOrDefault("failure", "")).isBlank())
                .limit(maximum)
                .toList();
        return Map.of("journeys", filtered, "aggregates", aggregates(filtered),
                "privacy", revealIdentity ? "operator" : "redacted", "retentionBound", maximum);
    }

    private static Map<String, Object> aggregates(List<Map<String, Object>> journeys) {
        Map<String, List<Long>> durations = new LinkedHashMap<>();
        int failures = 0;
        for (Map<String, Object> journey : journeys) {
            if (!String.valueOf(journey.getOrDefault("failure", "")).isBlank()) failures++;
            Object timeline = journey.get("timeline");
            if (!(timeline instanceof List<?> entries)) continue;
            for (Object item : entries) {
                if (!(item instanceof Map<?, ?> stage)) continue;
                String name = String.valueOf(stage.get("stage"));
                Object raw = stage.get("durationFromPreviousMillis");
                long value = raw instanceof Number number ? number.longValue() : 0;
                durations.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : durations.entrySet()) {
            List<Long> values = entry.getValue().stream().sorted().toList();
            stages.add(Map.of("stage", entry.getKey(), "p50Millis", percentile(values, 0.50),
                    "p95Millis", percentile(values, 0.95), "samples", values.size()));
        }
        stages.sort(Comparator.comparingLong(item -> -((Number) item.get("p95Millis")).longValue()));
        return Map.of("sessions", journeys.size(), "failures", failures, "stagePercentiles", stages,
                "slowestStage", stages.isEmpty() ? "" : stages.getFirst().get("stage"));
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0;
        int index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * percentile) - 1);
        return values.get(Math.max(0, index));
    }
}
