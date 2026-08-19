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
import static org.junit.jupiter.api.Assertions.assertTrue;

class OniLinkDashboardTest {
    @Test
    void servesAssetsAndProtectsRuntimeApisWithFirstRunOwnerSetup(@TempDir Path directory) throws Exception {
        Path proxyConfig = directory.resolve("config.properties");
        ProxyConfig.loadOrCreate(proxyConfig);
        Path log = directory.resolve("logs/latest.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "OniLink test log\n");
        DashboardConfig dashboardConfig = new DashboardConfig(
                true, new InetSocketAddress("127.0.0.1", 0), 60,
                directory.resolve("dashboard"), 65_536, 100);

        try (OniLinkDashboard dashboard = new OniLinkDashboard(
                dashboardConfig, new FakeControl(), proxyConfig, log)) {
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + dashboard.port());

            HttpResponse<String> home = client.send(
                    HttpRequest.newBuilder(base.resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, home.statusCode());
            assertTrue(home.body().contains("OniLink Control Plane"));
            assertTrue(home.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .contains("frame-ancestors 'none'"));

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/state")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

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
            Matcher token = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"").matcher(setup.body());
            assertTrue(token.find());

            HttpResponse<String> state = client.send(HttpRequest.newBuilder(base.resolve("/api/state"))
                    .header("Authorization", "Bearer " + token.group(1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, state.statusCode());
            assertTrue(state.body().contains("\"players\":2"));
            assertTrue(state.body().contains("\"role\":\"owner\""));

            HttpResponse<String> config = client.send(HttpRequest.newBuilder(base.resolve("/api/config"))
                    .header("Authorization", "Bearer " + token.group(1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, config.statusCode());
            Matcher revision = Pattern.compile("\\\"revision\\\":\\\"([^\\\"]+)\\\"").matcher(config.body());
            assertTrue(revision.find());
            HttpResponse<String> backend = post(client, base.resolve("/api/config/backends"), Map.of(
                    "revision", revision.group(1),
                    "name", "creative",
                    "host", "127.0.0.1",
                    "port", "19134",
                    "trustedProxyCidr", "127.0.0.1/32"), Map.of(
                    "Authorization", "Bearer " + token.group(1)));
            assertEquals(201, backend.statusCode());
            assertTrue(backend.body().contains("\"backendName\":\"creative\""));
            assertTrue(Files.isRegularFile(directory.resolve("secrets/creative.key")));
        }
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

    private static final class FakeControl implements DashboardControl {
        @Override
        public Map<String, Object> state() {
            return Map.of(
                    "players", 2, "backends", 1, "uptimeMillis", 1000,
                    "memoryUsedBytes", 1024, "memoryMaxBytes", 2048);
        }

        @Override
        public List<Map<String, Object>> players(boolean includeAddresses) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> backends(boolean includeAddresses) {
            return List.of();
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
