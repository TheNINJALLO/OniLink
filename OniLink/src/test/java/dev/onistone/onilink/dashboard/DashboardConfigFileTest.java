package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.config.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardConfigFileTest {
    @Test
    void redactsSecretsAndValidatesAtomicEditsWithRollback(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.properties");
        ProxyConfig.loadOrCreate(path);
        String protectedBlock = "backend.default.forwarding.activeSecretEnv=SERVER_\\"
                + System.lineSeparator() + "    ONLY_SECRET_ENV";
        String original = Files.readString(path).replace(
                "backend.default.forwarding.activeSecretEnv=ONIBRIDGE_FORWARDING_SECRET",
                protectedBlock);
        Files.writeString(path, original);
        DashboardConfigFile editor = new DashboardConfigFile(path);

        Map<String, Object> view = editor.read();
        String redacted = String.valueOf(view.get("content"));
        assertTrue(redacted.contains("backend.default.forwarding.activeSecretEnv=" + DashboardConfigFile.REDACTED));
        assertTrue(!redacted.contains("ONLY_SECRET_ENV"));

        Map<String, Object> saved = editor.save(String.valueOf(view.get("revision")),
                redacted.replace("motd=OniLink", "motd=Dashboard Test"));
        assertTrue(String.valueOf(saved.get("content")).contains("motd=Dashboard Test"));
        assertTrue(Files.readString(path).contains(protectedBlock));
        assertTrue(Files.isRegularFile(editor.backupPath()));

        Map<String, Object> latest = editor.read();
        String invalid = String.valueOf(latest.get("content")).replace("listener.port=19132", "listener.port=invalid");
        assertThrows(NumberFormatException.class,
                () -> editor.save(String.valueOf(latest.get("revision")), invalid));
        assertTrue(Files.readString(path).contains("motd=Dashboard Test"));

        Map<String, Object> restored = editor.rollback();
        assertEquals(original, Files.readString(path));
        assertTrue(String.valueOf(restored.get("content")).contains("motd=OniLink"));
    }
}
