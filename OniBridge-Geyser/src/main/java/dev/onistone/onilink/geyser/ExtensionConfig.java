package dev.onistone.onilink.geyser;

import dev.onistone.onilink.geyser.forwarding.OniForwardVerifier;
import dev.onistone.onilink.geyser.forwarding.TrustedProxyMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

record ExtensionConfig(
        String bridgeId,
        String backendName,
        TrustedProxyMatcher trustedProxies,
        OniForwardVerifier.KeyRing keys,
        int maximumTokenSize,
        long maximumLifetimeMs,
        long allowedClockSkewMs,
        int replayCacheMaximumEntries
) {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "bridge_id", "backend_name", "trusted_proxy_cidrs",
            "active_key_id", "active_secret_env", "active_secret_file",
            "previous_key_id", "previous_secret_env", "previous_secret_file",
            "maximum_token_size", "maximum_lifetime_millis", "allowed_clock_skew_millis",
            "replay_cache_maximum_entries");

    static ExtensionConfig load(Path file) throws IOException {
        return load(file, System::getenv);
    }

    static ExtensionConfig load(Path file, Function<String, String> environment) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        Set<String> unknown = new HashSet<>(properties.stringPropertyNames());
        unknown.removeAll(ALLOWED_KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown OniBridge-Geyser configuration keys: " + unknown);
        }

        String bridgeId = required(properties, "bridge_id");
        String backendName = required(properties, "backend_name");
        List<String> cidrs = Arrays.stream(required(properties, "trusted_proxy_cidrs").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        TrustedProxyMatcher trustedProxies = new TrustedProxyMatcher(cidrs);

        String activeId = required(properties, "active_key_id");
        byte[] activeSecret = loadSecret(properties, "active", file.getParent(), environment);
        OniForwardVerifier.Key active = new OniForwardVerifier.Key(activeId, activeSecret);
        Arrays.fill(activeSecret, (byte) 0);

        String previousId = properties.getProperty("previous_key_id", "").trim();
        OniForwardVerifier.Key previous = null;
        boolean previousSource = !properties.getProperty("previous_secret_env", "").isBlank()
                || !properties.getProperty("previous_secret_file", "").isBlank();
        if (previousId.isEmpty() != !previousSource) {
            throw new IllegalArgumentException("previous key ID and exactly one previous secret source are required together");
        }
        if (!previousId.isEmpty()) {
            if (previousId.equals(activeId)) {
                throw new IllegalArgumentException("active and previous key IDs must differ");
            }
            byte[] previousSecret = loadSecret(properties, "previous", file.getParent(), environment);
            previous = new OniForwardVerifier.Key(previousId, previousSecret);
            Arrays.fill(previousSecret, (byte) 0);
        }

        int maximumTokenSize = integer(properties, "maximum_token_size", 4_096, 256, 65_536);
        long maximumLifetime = integer(properties, "maximum_lifetime_millis", 10_000, 1, 10_000);
        long allowedClockSkew = integer(properties, "allowed_clock_skew_millis", 2_000, 0, 10_000);
        int replayCapacity = integer(properties, "replay_cache_maximum_entries", 10_000, 1, 1_000_000);
        return new ExtensionConfig(bridgeId, backendName, trustedProxies,
                new OniForwardVerifier.KeyRing(active, previous), maximumTokenSize,
                maximumLifetime, allowedClockSkew, replayCapacity);
    }

    static void writeTemplate(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                # OniBridge-Geyser verifies OniForward locally before Geyser connects to Java.
                # The Bedrock listener must also be firewalled and bound to the proxy-facing address.
                bridge_id=java-main
                backend_name=java
                trusted_proxy_cidrs=127.0.0.1/32,::1/128

                active_key_id=key-1
                active_secret_env=ONIBRIDGE_GEYSER_FORWARDING_SECRET
                active_secret_file=

                # Keep at most one previous key during a short rotation window.
                previous_key_id=
                previous_secret_env=
                previous_secret_file=

                maximum_token_size=4096
                maximum_lifetime_millis=10000
                allowed_clock_skew_millis=2000
                replay_cache_maximum_entries=10000
                """);
    }

    private static byte[] loadSecret(
            Properties properties,
            String prefix,
            Path configFolder,
            Function<String, String> environment
    ) throws IOException {
        String environmentName = properties.getProperty(prefix + "_secret_env", "").trim();
        String fileName = properties.getProperty(prefix + "_secret_file", "").trim();
        if (environmentName.isEmpty() == fileName.isEmpty()) {
            throw new IllegalArgumentException("configure exactly one " + prefix + " secret source");
        }
        String encoded;
        if (!environmentName.isEmpty()) {
            encoded = environment.apply(environmentName);
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalArgumentException("configured " + prefix + " secret environment variable is not set");
            }
            encoded = encoded.trim();
        } else {
            Path secretFile = configFolder.resolve(fileName).normalize();
            PosixFileAttributeView view = Files.getFileAttributeView(secretFile, PosixFileAttributeView.class);
            if (view == null) {
                throw new IllegalArgumentException("secret-file permissions cannot be verified; use an environment variable");
            }
            Set<PosixFilePermission> permissions = view.readAttributes().permissions();
            if (permissions.stream().anyMatch(permission -> switch (permission) {
                case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
                default -> false;
            })) {
                throw new IllegalArgumentException("configured secret file is accessible by group or others");
            }
            encoded = Files.readString(secretFile).trim();
        }
        final byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(prefix + " secret must be standard Base64", exception);
        }
        if (secret.length < 32) {
            Arrays.fill(secret, (byte) 0);
            throw new IllegalArgumentException(prefix + " secret must contain at least 32 decoded bytes");
        }
        return secret;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static int integer(Properties properties, String key, int fallback, int minimum, int maximum) {
        final int value;
        try {
            value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " is outside the allowed range");
        }
        return value;
    }
}
