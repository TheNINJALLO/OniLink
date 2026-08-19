package dev.onistone.onilink.config;

import java.nio.file.Path;
import java.util.Properties;

/** Configuration for the authenticated, proxy-level player allowlist. */
public record AllowlistConfig(
        boolean enabled,
        Path file,
        String kickMessage,
        boolean disconnectOnRemoval
) {
    public static final String DEFAULT_FILE = "allowlist.properties";
    public static final String DEFAULT_KICK_MESSAGE = "You are not allow-listed on this server.";

    public AllowlistConfig {
        if (file == null) {
            throw new IllegalArgumentException("allowlist file cannot be null");
        }
        file = file.toAbsolutePath().normalize();
        if (kickMessage == null || kickMessage.isBlank()) {
            throw new IllegalArgumentException("allowlist kick message cannot be blank");
        }
        kickMessage = kickMessage.trim();
        if (kickMessage.length() > 200 || kickMessage.indexOf('\0') >= 0
                || kickMessage.indexOf('\r') >= 0 || kickMessage.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("allowlist kick message must be one line of at most 200 characters");
        }
    }

    public static AllowlistConfig from(Properties properties, Path configDirectory) {
        String configuredFile = ConfigValues.stripInlineComment(
                properties.getProperty("allowlist.file", DEFAULT_FILE)).trim();
        if (configuredFile.isEmpty()) {
            throw new IllegalArgumentException("allowlist.file cannot be blank");
        }
        Path path = Path.of(configuredFile);
        if (!path.isAbsolute() && configDirectory != null) {
            path = configDirectory.resolve(path);
        }
        return new AllowlistConfig(
                Boolean.parseBoolean(ConfigValues.stripInlineComment(
                        properties.getProperty("allowlist.enabled", "false"))),
                path,
                ConfigValues.stripInlineComment(properties.getProperty(
                        "allowlist.kickMessage", DEFAULT_KICK_MESSAGE)),
                Boolean.parseBoolean(ConfigValues.stripInlineComment(
                        properties.getProperty("allowlist.disconnectOnRemoval", "true")))
        );
    }

    public static AllowlistConfig defaults() {
        return new AllowlistConfig(false, Path.of(DEFAULT_FILE), DEFAULT_KICK_MESSAGE, true);
    }
}
