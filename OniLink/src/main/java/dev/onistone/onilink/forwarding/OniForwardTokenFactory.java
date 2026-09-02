package dev.onistone.onilink.forwarding;

import dev.onistone.onilink.auth.AuthData;
import dev.onistone.onilink.config.BackendConfig;
import dev.onistone.onilink.config.BackendForwardingConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Issues one short-lived, independently replayable token for each backend login attempt. */
public final class OniForwardTokenFactory {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Clock clock;
    private final UUID proxyBootId;
    private final AtomicLong sequence = new AtomicLong();

    public OniForwardTokenFactory() {
        this(Path.of("dashboard", "oniforward-proxy.state"));
    }

    OniForwardTokenFactory(Clock clock) {
        this.clock = clock;
        this.proxyBootId = bootId(Math.max(1, clock.millis()));
    }

    public OniForwardTokenFactory(Path stateFile) {
        this(Clock.systemUTC(), stateFile);
    }

    OniForwardTokenFactory(Clock clock, Path stateFile) {
        this.clock = clock;
        this.proxyBootId = bootId(nextBootEpoch(stateFile, clock));
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
                    issued + config.tokenLifetimeMillis(),
                    proxyBootId,
                    nextSequence()
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

    private long nextSequence() {
        long value = sequence.incrementAndGet();
        if (value <= 0) {
            throw new IllegalStateException("OniForward sequence space is exhausted; restart OniLink");
        }
        return value;
    }

    static long nextBootEpoch(Path stateFile, Clock clock) {
        Path normalized = stateFile.toAbsolutePath().normalize();
        try {
            Path parent = normalized.getParent();
            if (parent != null) Files.createDirectories(parent);
            long previous = 0;
            if (Files.exists(normalized)) {
                String stored = Files.readString(normalized).trim();
                if (!stored.startsWith("ONIFORWARD_PROXY_EPOCH_V1 ")) {
                    throw new IllegalStateException("OniForward proxy epoch state is malformed");
                }
                previous = Long.parseLong(stored.substring("ONIFORWARD_PROXY_EPOCH_V1 ".length()));
                if (previous < 1) {
                    throw new IllegalStateException("OniForward proxy epoch state is invalid");
                }
            }
            long wallEpoch = Math.max(1, clock.millis());
            long epoch = Math.max(wallEpoch, Math.addExact(previous, 1));
            Path temporary = normalized.resolveSibling(
                    normalized.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(temporary,
                    "ONIFORWARD_PROXY_EPOCH_V1 " + epoch + System.lineSeparator());
            PosixFileAttributeView view =
                    Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
            try {
                Files.move(temporary, normalized,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            return epoch;
        } catch (IOException | ArithmeticException | NumberFormatException exception) {
            throw new IllegalStateException("Cannot advance OniForward proxy epoch state: " + normalized, exception);
        }
    }

    static UUID bootId(long epoch) {
        long random = RANDOM.nextLong();
        random = (random & 0x3fff_ffff_ffff_ffffL) | 0x8000_0000_0000_0000L;
        return new UUID(epoch, random);
    }
}
