package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.config.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    @Test
    void guidedBackendSetupWritesMatchedProxyAndEndstoneFiles(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.properties");
        ProxyConfig.loadOrCreate(path);
        DashboardConfigFile editor = new DashboardConfigFile(path);
        String revision = String.valueOf(editor.read().get("revision"));

        Map<String, Object> result = editor.addBackend(revision, Map.of(
                "name", "creative",
                "address", "198.51.100.20:25571",
                "proxyPublicIp", "198.51.100.10"));

        assertTrue((Boolean) result.get("added"));
        String secret = String.valueOf(result.get("secret"));
        assertEquals(32, Base64.getDecoder().decode(secret).length);
        Path secretPath = directory.resolve("secrets/creative.key");
        assertTrue(Files.isRegularFile(secretPath));
        assertEquals(secret, Files.readString(secretPath).trim());
        if (Files.getFileAttributeView(secretPath, PosixFileAttributeView.class) != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(secretPath));
        }

        String saved = Files.readString(path);
        assertTrue(saved.contains("backends=default,creative"));
        assertTrue(saved.contains("backend.creative.host=198.51.100.20"));
        assertTrue(saved.contains("backend.creative.forwarding.activeSecretEnv="));
        assertTrue(saved.contains("backend.creative.forwarding.activeSecretFile=secrets/creative.key"));
        assertTrue(!saved.contains(secret));
        String proxyProperties = String.valueOf(result.get("onilinkProperties"));
        assertTrue(proxyProperties.contains("backends=default,creative"));
        assertTrue(proxyProperties.contains("backend.creative.forwarding.activeSecretFile=secrets/creative.key"));
        assertTrue(!proxyProperties.contains(secret));

        ProxyConfig config = ProxyConfig.loadOrCreate(path);
        assertEquals(2, config.backends().size());
        assertEquals("creative-main", config.backends().get("creative").forwarding().bridgeId());
        String toml = String.valueOf(result.get("onibridgeToml"));
        assertTrue(toml.contains("backend_name = \"creative\""));
        assertTrue(toml.contains("trusted_proxy_cidrs = [\"198.51.100.10/32\"]"));
        assertTrue(toml.contains("active_key_id = \"key-1\""));
        assertTrue(toml.contains("active_secret_file = \"creative.key\""));
        assertTrue(toml.contains(
                "required_profile = \"bds-1.26.44.3-linux-x86_64-06effdd00067f1ae\""));
        assertTrue(toml.contains("allow_unreviewed_profile = false"));
        assertEquals("198.51.100.20:25571", result.get("backendEndpoint"));
        assertEquals("198.51.100.10/32", result.get("trustedProxyCidr"));
        assertEquals("creative-onibridge-setup.zip", result.get("setupBundleFileName"));

        Map<String, String> bundle = unzip(Base64.getDecoder().decode(
                String.valueOf(result.get("setupBundleBase64"))));
        assertEquals(Set.of("INSTALL.txt", "creative.key", "onibridge.toml"), bundle.keySet());
        assertEquals(secret, bundle.get("creative.key").trim());
        assertEquals(toml, bundle.get("onibridge.toml"));
        assertTrue(bundle.get("INSTALL.txt").contains("OniLink needs one primary allocation"));
        assertTrue(bundle.get("INSTALL.txt").contains("198.51.100.20:25571"));

        assertThrows(IllegalStateException.class,
                () -> editor.addBackend(String.valueOf(result.get("revision")), Map.of(
                        "name", "creative", "host", "127.0.0.1", "port", "19133",
                        "trustedProxyCidr", "127.0.0.1/32")));
    }

    @Test
    void guidedBackendSetupRejectsPropertyInjection(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.properties");
        ProxyConfig.loadOrCreate(path);
        DashboardConfigFile editor = new DashboardConfigFile(path);
        String revision = String.valueOf(editor.read().get("revision"));

        assertThrows(IllegalArgumentException.class,
                () -> editor.addBackend(revision, Map.of(
                        "name", "bad.name=owned", "host", "127.0.0.1", "port", "19133",
                        "trustedProxyCidr", "127.0.0.1/32")));
        assertThrows(IllegalArgumentException.class,
                () -> editor.addBackend(revision, Map.of(
                        "name", "shortip", "address", "127.0.0.1:19135",
                        "proxyPublicIp", "1")));
        assertEquals(1, ProxyConfig.loadOrCreate(path).backends().size());
    }

    private static Map<String, String> unzip(byte[] archive) throws Exception {
        Map<String, String> entries = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
