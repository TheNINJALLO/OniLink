package dev.onistone.onilink.config;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Properties;

/** Configuration for the embedded OniLink operator dashboard. */
public record DashboardConfig(
        boolean enabled,
        InetSocketAddress listenAddress,
        int sessionMinutes,
        Path dataDirectory,
        int maxRequestBytes,
        int logTailLines
) {
    public DashboardConfig {
        if (listenAddress == null) {
            throw new IllegalArgumentException("dashboard listenAddress cannot be null");
        }
        if (listenAddress.getPort() < 0 || listenAddress.getPort() > 65_535) {
            throw new IllegalArgumentException("dashboard port must be between 0 and 65535");
        }
        if (sessionMinutes < 15 || sessionMinutes > 10_080) {
            throw new IllegalArgumentException("dashboard sessionMinutes must be between 15 and 10080");
        }
        if (dataDirectory == null) {
            throw new IllegalArgumentException("dashboard dataDirectory cannot be null");
        }
        if (maxRequestBytes < 16_384 || maxRequestBytes > 1_048_576) {
            throw new IllegalArgumentException("dashboard maxRequestBytes must be between 16384 and 1048576");
        }
        if (logTailLines < 50 || logTailLines > 5_000) {
            throw new IllegalArgumentException("dashboard logTailLines must be between 50 and 5000");
        }
    }

    public static DashboardConfig from(Properties properties, Path configDirectory) {
        boolean enabled = booleanProperty(properties, "dashboard.enabled", true);
        String host = ConfigValues.stripInlineComment(
                properties.getProperty("dashboard.host", "127.0.0.1")).trim();
        int port = intProperty(properties, "dashboard.port", 8080);
        int sessionMinutes = intProperty(properties, "dashboard.sessionMinutes", 480);
        int maxRequestBytes = intProperty(properties, "dashboard.maxRequestBytes", 262_144);
        int logTailLines = intProperty(properties, "dashboard.logTailLines", 400);
        String dataDirectoryValue = ConfigValues.stripInlineComment(
                properties.getProperty("dashboard.dataDirectory", "dashboard")).trim();
        Path base = configDirectory == null ? Path.of(".").toAbsolutePath() : configDirectory;
        Path dataDirectory = base.resolve(dataDirectoryValue).toAbsolutePath().normalize();
        return new DashboardConfig(
                enabled,
                new InetSocketAddress(host, port),
                sessionMinutes,
                dataDirectory,
                maxRequestBytes,
                logTailLines
        );
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank()
                ? fallback
                : Integer.parseInt(ConfigValues.stripInlineComment(value));
    }

    private static boolean booleanProperty(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank()
                ? fallback
                : Boolean.parseBoolean(ConfigValues.stripInlineComment(value));
    }
}
