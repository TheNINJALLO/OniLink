package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.config.DashboardConfig;
import dev.onistone.onilink.config.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OniLinkDashboardTest {
    @Test
    void servesOneControlPlaneAndEnforcesTenantIsolation(@TempDir Path directory) throws Exception {
        Path proxyConfig = directory.resolve("config.properties");
        ProxyConfig.loadOrCreate(proxyConfig);
        Path log = directory.resolve("logs/latest.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "OniLink test log\n");
        DashboardConfig dashboardConfig = new DashboardConfig(
                true, new InetSocketAddress("127.0.0.1", 0), 60,
                directory.resolve("dashboard"), 65_536, 100);

        try (OniLinkDashboard dashboard = new OniLinkDashboard(
                dashboardConfig, new FakeControl(19130), proxyConfig, log, FakeRuntime::new)) {
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + dashboard.port());

            HttpResponse<String> home = get(client, base.resolve("/"), "");
            assertEquals(200, home.statusCode());
            assertTrue(home.body().contains("OniLink Control Plane"));
            assertTrue(home.body().contains("<div id=\"root\"></div>"));
            assertTrue(home.body().contains("type=\"module\""));
            assertEquals("no-store", home.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(home.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .contains("frame-ancestors 'none'"));

            String applicationPath = assetPath(home.body(), "src", "js");
            HttpResponse<String> application = get(client, base.resolve(applicationPath), "");
            assertEquals(200, application.statusCode());
            assertTrue(application.headers().firstValue("Content-Type").orElseThrow()
                    .startsWith("text/javascript"));
            assertTrue(application.headers().firstValue("Cache-Control").orElseThrow().contains("immutable"));
            assertTrue(application.body().contains("/api/tenancy/tenants"));
            assertTrue(application.body().contains("/api/tenancy/proxy/runtime"));
            assertTrue(application.body().contains("/api/packets"));
            assertFalse(application.body().contains("/api/hosting"));
            String stylesheetPath = assetPath(home.body(), "href", "css");
            HttpResponse<String> stylesheet = get(client, base.resolve(stylesheetPath), "");
            assertEquals(200, stylesheet.statusCode());
            assertTrue(stylesheet.headers().firstValue("Content-Type").orElseThrow().startsWith("text/css"));
            assertEquals(404, get(client, base.resolve("/assets/missing-deadbeef.js"), "").statusCode());
            assertEquals(404, get(client, base.resolve("/assets/%2e%2e/index.html"), "").statusCode());
            assertEquals(404, get(client, base.resolve("/unknown-dashboard-path"), "").statusCode());

            assertEquals(401, get(client, base.resolve("/api/state"), "").statusCode());
            HttpResponse<String> missingApi = get(client, base.resolve("/api/does-not-exist"), "");
            assertEquals(401, missingApi.statusCode());
            assertTrue(missingApi.headers().firstValue("Content-Type").orElseThrow()
                    .startsWith("application/json"));
            HttpResponse<String> crossOrigin = post(client, base.resolve("/api/setup"),
                    Map.of("setupCode", "bad", "username", "owner", "password", "a secure password"),
                    Map.of("Origin", "https://attacker.example"));
            assertEquals(403, crossOrigin.statusCode());

            String setupCode = Files.readAllLines(directory.resolve("dashboard/FIRST_RUN_SETUP.txt")).stream()
                    .filter(line -> line.startsWith("Setup code: "))
                    .findFirst().orElseThrow().substring("Setup code: ".length());
            HttpResponse<String> setup = post(client, base.resolve("/api/setup"), Map.of(
                    "setupCode", setupCode,
                    "username", "owner",
                    "password", "a secure owner password"), Map.of());
            assertEquals(201, setup.statusCode());
            String ownerToken = token(setup.body());
            assertEquals(404, get(client, base.resolve("/api/does-not-exist"), ownerToken).statusCode());

            String viewerToken = createAndLogin(client, base, ownerToken, "test-viewer", "viewer");
            String operatorToken = createAndLogin(client, base, ownerToken, "test-operator", "operator");
            String adminToken = createAndLogin(client, base, ownerToken, "test-admin", "admin");
            assertEquals(200, get(client, base.resolve("/api/state"), viewerToken).statusCode());
            assertEquals(200, get(client, base.resolve("/api/packets?limit=100"), viewerToken).statusCode());
            assertEquals(403, get(client, base.resolve("/api/logs?limit=50"), viewerToken).statusCode());
            assertEquals(200, get(client, base.resolve("/api/logs?limit=50"), operatorToken).statusCode());
            assertEquals(403, get(client, base.resolve("/api/config"), operatorToken).statusCode());
            assertEquals(200, get(client, base.resolve("/api/config"), adminToken).statusCode());
            assertEquals(403, get(client, base.resolve("/api/users"), adminToken).statusCode());
            assertEquals(200, get(client, base.resolve("/api/users"), ownerToken).statusCode());

            HttpResponse<String> state = get(client, base.resolve("/api/state"), ownerToken);
            assertEquals(200, state.statusCode());
            assertTrue(state.body().contains("\"players\":2"));
            assertTrue(state.body().contains("\"role\":\"owner\""));
            HttpResponse<String> tenancy = get(client, base.resolve("/api/tenancy"), ownerToken);
            assertEquals(200, tenancy.statusCode());
            assertTrue(tenancy.body().contains("\"mode\":\"single-container\""));
            assertTrue(tenancy.body().contains("\"providerPort\":19130"));

            HttpResponse<String> tenantCreate = post(client, base.resolve("/api/tenancy/tenants"), Map.of(
                    "tenant", "acme",
                    "label", "Acme Network",
                    "username", "acme-admin",
                    "password", "a secure tenant password"), bearer(ownerToken));
            assertEquals(201, tenantCreate.statusCode());

            HttpResponse<String> proxyCreate = post(client, base.resolve("/api/tenancy/proxies"), Map.ofEntries(
                    Map.entry("tenant", "acme"),
                    Map.entry("proxy", "survival"),
                    Map.entry("label", "Survival Proxy"),
                    Map.entry("port", "19135"),
                    Map.entry("publicHost", "45.143.196.108"),
                    Map.entry("backendAddress", "45.143.196.160:25570"),
                    Map.entry("proxySourceIp", "45.143.196.108"),
                    Map.entry("maxPlayers", "20"),
                    Map.entry("motd", "Acme Network"),
                    Map.entry("bdsProfile", "test-profile")), bearer(ownerToken));
            assertEquals(201, proxyCreate.statusCode());
            assertTrue(proxyCreate.body().contains("\"status\":\"running\""));

            HttpResponse<String> login = post(client, base.resolve("/api/login"), Map.of(
                    "username", "acme-admin",
                    "password", "a secure tenant password",
                    "totp", ""), Map.of());
            assertEquals(200, login.statusCode());
            String tenantToken = token(login.body());
            assertTrue(login.body().contains("\"role\":\"tenant\""));
            assertTrue(login.body().contains("\"tenantId\":\"acme\""));

            HttpResponse<String> tenantOverview = get(client, base.resolve("/api/tenancy"), tenantToken);
            assertEquals(200, tenantOverview.statusCode());
            assertTrue(tenantOverview.body().contains("\"tenantScope\":\"acme\""));
            assertTrue(tenantOverview.body().contains("\"id\":\"survival\""));
            HttpResponse<String> tenantProxy = get(client,
                    base.resolve("/api/tenancy/proxy?tenant=acme&proxy=survival"), tenantToken);
            assertEquals(200, tenantProxy.statusCode());
            assertTrue(tenantProxy.body().contains("45.143.196.108:19135"));
            assertEquals(403, get(client, base.resolve("/api/state"), tenantToken).statusCode());
            assertEquals(403, get(client,
                    base.resolve("/api/tenancy/proxy?tenant=other&proxy=survival"), tenantToken).statusCode());
            HttpResponse<String> tenantPackets = get(client,
                    base.resolve("/api/packets?tenant=acme&proxy=survival&limit=100"), tenantToken);
            assertEquals(200, tenantPackets.statusCode());
            assertTrue(tenantPackets.body().contains("\"enabled\":false"));
            assertEquals(403, get(client,
                    base.resolve("/api/packets?tenant=other&proxy=survival"), tenantToken).statusCode());

            HttpResponse<String> allowlist = get(client, base.resolve("/api/allowlist"), ownerToken);
            assertEquals(200, allowlist.statusCode());
            assertTrue(allowlist.body().contains("\"enabled\":true"));
            HttpResponse<String> allowlistAdd = post(client, base.resolve("/api/allowlist"), Map.of(
                    "xuid", "2533274790000001", "name", "ExamplePlayer"), bearer(ownerToken));
            assertEquals(200, allowlistAdd.statusCode());

            HttpResponse<String> config = get(client, base.resolve("/api/config"), ownerToken);
            Matcher revision = Pattern.compile("\\\"revision\\\":\\\"([^\\\"]+)\\\"").matcher(config.body());
            assertTrue(revision.find());
            HttpResponse<String> backend = post(client, base.resolve("/api/config/backends"), Map.of(
                    "revision", revision.group(1),
                    "name", "creative",
                    "address", "127.0.0.1:19134",
                    "proxyPublicIp", "127.0.0.1"), bearer(ownerToken));
            assertEquals(201, backend.statusCode());
            assertTrue(backend.body().contains("\"setupBundleBase64\""));
            assertTrue(Files.isRegularFile(directory.resolve("secrets/creative.key")));
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String createAndLogin(
            HttpClient client,
            URI base,
            String ownerToken,
            String username,
            String role
    ) throws Exception {
        String password = "a secure dashboard password";
        assertEquals(201, post(client, base.resolve("/api/users"), Map.of(
                "username", username, "password", password, "role", role), bearer(ownerToken)).statusCode());
        HttpResponse<String> login = post(client, base.resolve("/api/login"), Map.of(
                "username", username, "password", password, "totp", ""), Map.of());
        assertEquals(200, login.statusCode());
        return token(login.body());
    }

    private static String assetPath(String html, String attribute, String extension) {
        Matcher matcher = Pattern.compile(attribute + "=\"([^\"]+\\." + extension + ")\"").matcher(html);
        assertTrue(matcher.find(), "Generated " + extension + " asset reference is missing");
        return matcher.group(1);
    }

    private static HttpResponse<String> post(
            HttpClient client,
            URI uri,
            Map<String, String> fields,
            Map<String, String> headers
    ) throws Exception {
        String body = fields.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right).orElse("");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private static String token(String body) {
        Matcher matcher = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private record FakeRuntime(DashboardControl control) implements DashboardTenantHosting.RuntimeHandle {
        private FakeRuntime(Path ignored) {
            this(new FakeControl(19135));
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeControl implements DashboardControl {
        private final int port;

        private FakeControl(int port) {
            this.port = port;
        }

        @Override
        public Map<String, Object> state() {
            return Map.of(
                    "players", 2,
                    "backends", 1,
                    "uptimeMillis", 1000,
                    "memoryUsedBytes", 1024,
                    "memoryMaxBytes", 2048,
                    "listener", Map.of("host", "127.0.0.1", "port", port));
        }

        @Override
        public List<Map<String, Object>> players(boolean includeAddresses) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> backends(boolean includeAddresses) {
            return List.of(Map.of("name", "default", "host", "127.0.0.1", "port", 19132));
        }

        @Override
        public Map<String, Object> allowlist() {
            return Map.of("enabled", true, "count", 1, "entries", List.of(
                    Map.of("xuid", "2533274790000001", "name", "ExamplePlayer")));
        }

        @Override
        public ActionResult allowlistAdd(String xuid, String name) {
            return new ActionResult(true, "Allow-listed " + xuid);
        }

        @Override
        public ActionResult allowlistRemove(String xuid) {
            return new ActionResult(true, "Removed " + xuid);
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
