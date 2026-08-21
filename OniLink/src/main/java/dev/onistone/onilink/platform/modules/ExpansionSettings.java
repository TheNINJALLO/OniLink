package dev.onistone.onilink.platform.modules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Validated expansion settings loaded from the existing OniLink properties file. */
public final class ExpansionSettings {
    private final Map<String, String> values;

    private ExpansionSettings(Map<String, String> values) {
        this.values = Map.copyOf(values);
        validate();
    }

    public static ExpansionSettings load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) values.put(name, properties.getProperty(name).trim());
        return new ExpansionSettings(values);
    }

    public boolean enabled(String module) {
        boolean safeDefault = "pulse".equals(module) || "packs.scanner".equals(module);
        return bool("modules." + module + ".enabled", safeDefault);
    }

    public String value(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public int integer(String key, int fallback, int minimum, int maximum) {
        String raw = values.get(key);
        int result;
        try {
            result = raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(key + " must be " + minimum + ".." + maximum);
        }
        return result;
    }

    public Map<String, String> values() {
        return values;
    }

    private boolean bool(String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private void validate() {
        integer("flow.maxWorkflows", 500, 1, 10_000);
        integer("flow.maxSteps", 100, 1, 1_000);
        integer("flow.maxParallelBranches", 8, 1, 32);
        integer("flow.maxExecutionSeconds", 3_600, 1, 86_400);
        integer("flow.maxConcurrentExecutions", 32, 1, 256);
        integer("continuity.maxReservations", 10_000, 1, 100_000);
        integer("journeys.maxRecords", 10_000, 10, 100_000);
        integer("journeys.retentionHours", 72, 1, 8_760);
        integer("fleet.maxDynamicBackends", 1_000, 1, 10_000);
        integer("fleet.maxCanaryPercentage", 25, 0, 100);
        integer("fleet.canaryAssignmentMinutes", 120, 1, 10_080);
        integer("presence.expirationSeconds", 60, 5, 3_600);
        integer("support.maxOpenTicketsPerPlayer", 5, 1, 100);
        integer("support.ticketRateLimitMinutes", 10, 1, 1_440);
        integer("packs.scanner.maxArchiveBytes", 268_435_456, 1_024, Integer.MAX_VALUE);
        integer("packs.scanner.maxEntries", 10_000, 1, 100_000);
        integer("notifications.maxSubscriptionsPerUser", 10, 1, 100);
    }
}
