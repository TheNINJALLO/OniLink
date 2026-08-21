package dev.onistone.onilink.control.wire;

import dev.onistone.onilink.config.OniControlConfig.ControlBackendConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

public final class ControlSecretLoader {
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private ControlSecretLoader() {
    }

    public static byte[] load(ControlBackendConfig config) throws IOException {
        String encoded;
        if (!config.secretEnvironment().isBlank() && config.secretFile().toString().isBlank()) {
            encoded = System.getenv(config.secretEnvironment());
            if (encoded == null) throw new IOException("configured OniControl secret environment variable is not set");
        } else if (config.secretEnvironment().isBlank() && !config.secretFile().toString().isBlank()) {
            Path path = config.secretFile();
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("configured OniControl secret file must be a regular non-symlink file");
            }
            restrict(path);
            encoded = Files.readString(path, StandardCharsets.US_ASCII);
        } else {
            throw new IOException("configure exactly one OniControl secret source");
        }
        encoded = encoded.strip();
        if (!encoded.matches("[A-Za-z0-9+/]*={0,2}") || encoded.length() % 4 != 0) {
            throw new IOException("OniControl secret must be canonical Base64");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IOException("OniControl secret must be canonical Base64", exception);
        }
        if (secret.length < 32) {
            java.util.Arrays.fill(secret, (byte) 0);
            throw new IOException("OniControl secret must decode to at least 32 bytes");
        }
        return secret;
    }

    private static void restrict(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!OWNER_ONLY.containsAll(actual)) {
                throw new IOException("OniControl secret file is accessible by group or others");
            }
        } catch (UnsupportedOperationException ignored) {
            // Windows access is governed by the account ACL; the file still must be regular and non-symlink.
        }
    }
}
