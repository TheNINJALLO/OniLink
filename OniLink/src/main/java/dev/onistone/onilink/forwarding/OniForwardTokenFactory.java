package dev.onistone.onilink.forwarding;

import dev.onistone.onilink.auth.AuthData;
import dev.onistone.onilink.config.BackendConfig;
import dev.onistone.onilink.config.BackendForwardingConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/** Issues one short-lived, independently replayable token for each backend login attempt. */
public final class OniForwardTokenFactory {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Clock clock;

    public OniForwardTokenFactory() {
        this(Clock.systemUTC());
    }

    OniForwardTokenFactory(Clock clock) {
        this.clock = clock;
    }

    public String issue(BackendConfig backend, AuthData auth, String sessionId, SocketAddress clientAddress) {
        BackendForwardingConfig config = backend.forwarding();
        if (!config.enabled()) {
            return null;
        }
        if (!(clientAddress instanceof InetSocketAddress inet)) {
            throw new IllegalStateException("OniForward requires an Internet client address");
        }
        byte[] secret = loadSecret(config.activeSecretEnv(), config.activeSecretFile());
        try {
            long issued = clock.millis();
            var claims = new OniForward.Claims(
                    OniForward.PROTOCOL_VERSION,
                    config.activeKeyId(),
                    config.proxyId(),
                    config.bridgeId(),
                    backend.name(),
                    sessionId,
                    nonce(),
                    auth.displayName(),
                    auth.xuid(),
                    auth.identity(),
                    inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString(),
                    inet.getPort(),
                    issued,
                    issued + config.tokenLifetimeMillis()
            );
            String token = OniForward.sign(claims, new OniForward.Key(config.activeKeyId(), secret));
            if (token.length() > 4_096) {
                throw new IllegalStateException("OniForward token exceeds the 4096-byte policy limit");
            }
            return token;
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    static byte[] loadSecret(String environmentName, String fileName) {
        String encoded;
        if (!environmentName.isBlank()) {
            encoded = System.getenv(environmentName);
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalStateException("Required OniForward secret environment variable is not set: " + environmentName);
            }
        } else {
            try {
                Path path = Path.of(fileName);
                PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
                if (view == null) {
                    throw new IllegalStateException(
                            "Secret-file permissions cannot be verified on this filesystem; use an environment variable");
                }
                var permissions = view.readAttributes().permissions();
                if (permissions.stream().anyMatch(permission -> switch (permission) {
                    case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
                    default -> false;
                })) {
                    throw new IllegalStateException("OniForward secret file is accessible by group or others");
                }
                encoded = Files.readString(path).trim();
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read configured OniForward secret file", exception);
            }
        }
        final byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("OniForward secret must be standard Base64", exception);
        }
        if (secret.length < 32) {
            java.util.Arrays.fill(secret, (byte) 0);
            throw new IllegalStateException("OniForward secret must contain at least 32 decoded bytes");
        }
        return secret;
    }

    private static String nonce() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        return java.util.HexFormat.of().formatHex(value);
    }
}
