package dev.onistone.onilink.geyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionConfigTest {
    @TempDir
    Path temporary;

    @Test
    void loadsEnvironmentSecretAndStrictPolicy() throws Exception {
        Path config = write(""
                + "bridge_id=java-main\n"
                + "backend_name=java\n"
                + "trusted_proxy_cidrs=127.0.0.1/32,::1/128\n"
                + "active_key_id=key-1\n"
                + "active_secret_env=TEST_SECRET\n");
        String secret = Base64.getEncoder().encodeToString(new byte[32]);

        ExtensionConfig loaded = ExtensionConfig.load(config, name -> name.equals("TEST_SECRET") ? secret : null);
        assertEquals("java-main", loaded.bridgeId());
        assertEquals(10_000, loaded.maximumLifetimeMs());
    }

    @Test
    void rejectsMissingShortAmbiguousAndUnknownSecrets() throws Exception {
        String base = ""
                + "bridge_id=java-main\n"
                + "backend_name=java\n"
                + "trusted_proxy_cidrs=127.0.0.1/32\n"
                + "active_key_id=key-1\n";
        assertThrows(IllegalArgumentException.class,
                () -> ExtensionConfig.load(write(base), ignored -> null));
        assertThrows(IllegalArgumentException.class,
                () -> ExtensionConfig.load(write(base + "active_secret_env=SHORT\n"),
                        ignored -> Base64.getEncoder().encodeToString(new byte[31])));
        assertThrows(IllegalArgumentException.class,
                () -> ExtensionConfig.load(write(base
                        + "active_secret_env=VALUE\nactive_secret_file=secret.txt\n"),
                        ignored -> Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalArgumentException.class,
                () -> ExtensionConfig.load(write(base + "unknown=true\n"), ignored -> null));
    }

    @Test
    void mixedDeploymentExampleLoadsThroughTheStrictParser() throws Exception {
        Path example = Path.of(
                "..", "examples", "mixed-bds-geyser", "onibridge-geyser.properties");
        String secret = Base64.getEncoder().encodeToString(new byte[32]);

        ExtensionConfig loaded = ExtensionConfig.load(
                example,
                name -> name.equals("ONIBRIDGE_JAVA_SECRET") ? secret : null);

        assertEquals("java-main", loaded.bridgeId());
        assertEquals("java", loaded.backendName());
        assertEquals(10_000, loaded.maximumLifetimeMs());
    }

    private Path write(String value) throws Exception {
        Path file = temporary.resolve("config-" + System.nanoTime() + ".properties");
        Files.writeString(file, value);
        return file;
    }
}
