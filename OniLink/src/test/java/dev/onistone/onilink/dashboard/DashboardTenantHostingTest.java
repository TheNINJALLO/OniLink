package dev.onistone.onilink.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardTenantHostingTest {
    @Test
    void runsTenantProxiesInsideOneContainerAndPersistsTheirIsolation(@TempDir Path directory) throws Exception {
        DashboardAccounts accounts = new DashboardAccounts(
                directory.resolve("accounts"), 60, "http://127.0.0.1:8080");
        List<FakeRuntime> started = new ArrayList<>();
        DashboardTenantHosting.RuntimeFactory factory = configPath -> {
            FakeRuntime runtime = new FakeRuntime(configPath);
            started.add(runtime);
            return runtime;
        };

        try (DashboardTenantHosting hosting = new DashboardTenantHosting(
                directory.resolve("dashboard"), accounts, factory, 19130)) {
            hosting.createTenant(Map.of(
                    "tenant", "acme",
                    "label", "Acme Network",
                    "username", "acme-admin",
                    "password", "a secure tenant password"));
            hosting.createTenant(Map.of(
                    "tenant", "birch",
                    "label", "Birch Network",
                    "username", "birch-admin",
                    "password", "another secure tenant password"));
            assertEquals("acme", accounts.login(
                    "acme-admin", "a secure tenant password", "").session().principal().tenantId());

            Map<String, Object> created = hosting.createProxy(proxyForm("survival", 19135));
            assertTrue(created.toString().contains("running"));
            assertEquals(1, started.size());

            Path configPath = started.getFirst().configPath;
            Properties properties = loadProperties(configPath);
            assertEquals("19135", properties.getProperty("listener.port"));
            assertEquals("false", properties.getProperty("dashboard.enabled"));
            assertEquals("secrets/default.key",
                    properties.getProperty("backend.default.forwarding.activeSecretFile"));
            assertEquals("45.143.196.160", properties.getProperty("backend.default.host"));
            assertEquals("25570", properties.getProperty("backend.default.port"));

            String secret = Files.readString(configPath.resolveSibling("secrets/default.key")).trim();
            assertEquals(32, Base64.getDecoder().decode(secret).length);
            Map<String, String> handoff = unzip(hosting.handoff("acme", "survival"));
            assertEquals(secret, handoff.get("backend/default.key").trim());
            assertTrue(handoff.get("CUSTOMER-START-HERE.txt").contains("existing OniLink container"));
            assertTrue(handoff.get("backend/onibridge.toml")
                    .contains("trusted_proxy_cidrs = [\"45.143.196.108/32\"]"));

            Map<String, Object> tenantView = hosting.overview("acme");
            assertEquals("single-container", tenantView.get("mode"));
            assertEquals(19130, tenantView.get("providerPort"));
            assertFalse(DashboardJson.encode(tenantView).contains(secret));
            assertFalse(DashboardJson.encode(tenantView).contains("birch"));

            assertThrows(IllegalStateException.class,
                    () -> hosting.createProxy(proxyForm("provider-conflict", 19130)));
            assertThrows(IllegalStateException.class,
                    () -> hosting.createProxy(proxyForm("tenant-conflict", 19135)));

            hosting.proxyAction("acme", "survival", "stop");
            assertTrue(started.getFirst().closed.get());
            assertTrue(DashboardJson.encode(hosting.overview("acme")).contains("\"status\":\"stopped\""));
            hosting.proxyAction("acme", "survival", "start");
            assertEquals(2, started.size());
            hosting.tenantAction("acme", "suspend");
            assertTrue(DashboardJson.encode(hosting.overview("acme")).contains("\"suspended\":true"));
        }

        List<FakeRuntime> reloadedRuntimes = new ArrayList<>();
        try (DashboardTenantHosting reloaded = new DashboardTenantHosting(
                directory.resolve("dashboard"), accounts, configPath -> {
                    FakeRuntime runtime = new FakeRuntime(configPath);
                    reloadedRuntimes.add(runtime);
                    return runtime;
                }, 19130)) {
            assertTrue(DashboardJson.encode(reloaded.overview("acme")).contains("\"id\":\"survival\""));
            assertTrue(reloadedRuntimes.isEmpty(), "suspended tenant proxies stay stopped after reboot");
        }
    }

    @Test
    void tenantPrimaryBackendChangeRestartsOnlyItsProxy(@TempDir Path directory) throws Exception {
        DashboardAccounts accounts = new DashboardAccounts(
                directory.resolve("accounts"), 60, "http://127.0.0.1:8080");
        List<FakeRuntime> started = new ArrayList<>();
        DashboardTenantHosting.RuntimeFactory factory = configPath -> {
            FakeRuntime runtime = new FakeRuntime(configPath);
            started.add(runtime);
            return runtime;
        };

        try (DashboardTenantHosting hosting = new DashboardTenantHosting(
                directory.resolve("dashboard"), accounts, factory, 19130)) {
            hosting.createTenant(Map.of(
                    "tenant", "acme",
                    "label", "Acme Network",
                    "username", "acme-admin",
                    "password", "a secure tenant password"));
            hosting.createProxy(proxyForm("survival", 19135));
            Map<String, Object> dashboard = hosting.proxyDashboard("acme", "survival");
            hosting.addBackend(Map.of(
                    "tenant", "acme",
                    "proxy", "survival",
                    "revision", String.valueOf(dashboard.get("configurationRevision")),
                    "name", "creative",
                    "address", "45.143.196.161:25571",
                    "proxyPublicIp", "45.143.196.108"));
            assertEquals(2, started.size(), "adding a backend restarts the running proxy");

            Map<String, Object> withCreative = hosting.proxyDashboard("acme", "survival");
            Map<String, Object> changed = hosting.setPrimaryBackend(Map.of(
                    "tenant", "acme",
                    "proxy", "survival",
                    "revision", String.valueOf(withCreative.get("configurationRevision")),
                    "backend", "creative"));

            assertEquals(3, started.size());
            assertTrue(started.get(1).closed.get());
            assertEquals("creative", changed.get("primaryBackend"));
            Properties properties = loadProperties(started.getLast().configPath);
            assertEquals("creative", properties.getProperty("backend.name"));
            assertEquals("45.143.196.161", properties.getProperty("backend.host"));
            assertEquals("25571", properties.getProperty("backend.port"));
            assertTrue(DashboardJson.encode(hosting.overview("acme"))
                    .contains("\"primaryBackend\":\"creative\""));
            assertTrue(DashboardJson.encode(hosting.proxyDashboard("acme", "survival"))
                    .contains("\"primaryBackend\":\"creative\""));
        }
    }

    private static Map<String, String> proxyForm(String proxyId, int port) {
        return Map.ofEntries(
                Map.entry("tenant", "acme"),
                Map.entry("proxy", proxyId),
                Map.entry("label", "Survival Proxy"),
                Map.entry("port", Integer.toString(port)),
                Map.entry("publicHost", "45.143.196.108"),
                Map.entry("backendAddress", "45.143.196.160:25570"),
                Map.entry("proxySourceIp", "45.143.196.108"),
                Map.entry("maxPlayers", "20"),
                Map.entry("motd", "Acme Network"),
                Map.entry("bdsProfile", "test-profile"));
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static Map<String, String> unzip(byte[] archive) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static final class FakeRuntime implements DashboardTenantHosting.RuntimeHandle {
        private final Path configPath;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final DashboardControl control = new FakeControl();

        private FakeRuntime(Path configPath) {
            this.configPath = configPath;
        }

        @Override
        public DashboardControl control() {
            return control;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class FakeControl implements DashboardControl {
        @Override
        public Map<String, Object> state() {
            return Map.of("players", 0, "listener", Map.of("host", "0.0.0.0", "port", 19135));
        }

        @Override
        public List<Map<String, Object>> players(boolean includeAddresses) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> backends(boolean includeAddresses) {
            return List.of(Map.of("name", "default", "host", "45.143.196.160", "port", 25570));
        }

        @Override
        public ActionResult transfer(String player, String backend) {
            return new ActionResult(true, "transferred");
        }

        @Override
        public ActionResult disconnect(String player, String reason) {
            return new ActionResult(true, "disconnected");
        }

        @Override
        public ActionResult alert(String message) {
            return new ActionResult(true, "alerted");
        }

        @Override
        public ActionResult trace(String player, long milliseconds) {
            return new ActionResult(true, "tracing");
        }

        @Override
        public void shutdown() {
        }
    }
}
