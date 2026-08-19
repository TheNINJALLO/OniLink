package dev.onistone.onilink.config;

import java.util.List;
import java.util.Properties;

/**
 * Initial backend candidates and retry count.
 *
 * @param tryOrder backend names in priority order; empty keeps the routed backend
 * @param attemptsPerBackend connection attempts before trying the next candidate
 */
public record JoinConfig(List<String> tryOrder, int attemptsPerBackend) {
    public JoinConfig {
        if (tryOrder == null) {
            throw new IllegalArgumentException("tryOrder cannot be null");
        }
        if (attemptsPerBackend < 1) {
            throw new IllegalArgumentException("attemptsPerBackend must be positive");
        }
        tryOrder = List.copyOf(tryOrder);
    }

    public static JoinConfig defaults() {
        return new JoinConfig(List.of(), 1);
    }

    /** Reads {@code join.try}, using the failover chain when it is not set. */
    public static JoinConfig from(Properties properties, FailoverConfig failover) {
        List<String> tryOrder = properties.containsKey("join.try")
                ? ConfigValues.commaList(properties.getProperty("join.try"), "join.try")
                : failover.fallbacks();
        String attempts = properties.getProperty("join.attemptsPerBackend");
        return new JoinConfig(
                tryOrder,
                attempts == null || attempts.isBlank()
                        ? 1
                        : Math.max(1, Integer.parseInt(ConfigValues.stripInlineComment(attempts)))
        );
    }
}
