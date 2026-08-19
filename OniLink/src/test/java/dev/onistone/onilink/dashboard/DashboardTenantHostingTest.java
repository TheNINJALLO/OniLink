package dev.onistone.onilink.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardTenantHostingTest {
    @Test
    void discoversAndProvisionsAnIsolatedTenant(@TempDir Path directory) throws Exception {
        AtomicBoolean created = new AtomicBoolean();
        AtomicBoolean suspended = new AtomicBoolean();
        AtomicReference<String> createRequest = new AtomicReference<>("");
        HttpServer panel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        panel.createContext("/api/application", exchange -> respond(
                exchange, created, suspended, createRequest));
        panel.start();
        try {
            writeSettings(directory, panel.getAddress().getPort());
            DashboardTenantHosting hosting = new DashboardTenantHosting(directory);

            Map<String, Object> discovery = hosting.discovery();
            assertTrue(discovery.toString().contains("customer"));
            assertTrue(discovery.toString().contains("OniLink"));
            assertEquals(1, ((java.util.List<?>) hosting.allocations(4).get("allocations")).size());

            Map<String, Object> result = hosting.provision(Map.of(
                    "tenant", "acme",
                    "customerLabel", "Acme Network",
                    "plan", "starter",
                    "userId", "7",
                    "userDisplay", "customer",
                    "nodeId", "4",
                    "allocationId", "44",
                    "backendAddress", "10.0.0.25:19132",
                    "proxySourceIp", "127.0.0.1"));

            assertTrue(created.get());
            assertTrue(result.toString().contains("active"));
            assertTrue(createRequest.get().contains("\"external_id\":\"onilink-tenant-acme\""));
            assertTrue(createRequest.get().contains("\"allocations\":0"));
            assertTrue(createRequest.get().contains("\"ONILINK_DASHBOARD_SETUP_CODE\""));

            Map<String, String> handoff = unzip(hosting.handoff("acme"));
            assertTrue(handoff.get("CUSTOMER-START-HERE.txt").contains("127.0.0.1:19140"));
            assertTrue(handoff.get("backend/onibridge.toml").contains("trusted_proxy_cidrs = [\"127.0.0.1/32\"]"));
            byte[] forwardingKey = Base64.getDecoder().decode(handoff.get("backend/default.key").trim());
            assertEquals(32, forwardingKey.length);

            String overview = DashboardJson.encode(hosting.overview());
            assertFalse(overview.contains(handoff.get("backend/default.key").trim()));
            assertFalse(overview.contains("ptla-test-key"));

            hosting.action("acme", "suspend");
            assertTrue(suspended.get());
            assertTrue(DashboardJson.encode(hosting.overview()).contains("\"suspended\":true"));

            DashboardTenantHosting reloaded = new DashboardTenantHosting(directory);
            assertTrue(DashboardJson.encode(reloaded.overview()).contains("\"id\":\"acme\""));
        } finally {
            panel.stop(0);
        }
    }

    private static void writeSettings(Path directory, int port) throws IOException {
        Path hosting = directory.resolve("hosting");
        Files.createDirectories(hosting);
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        // The production settings form requires HTTPS. This loopback HTTP endpoint exists only in this test.
        properties.setProperty("panelUrl", "http://127.0.0.1:" + port);
        properties.setProperty("apiKey", "ptla-test-key");
        properties.setProperty("eggId", "12");
        properties.setProperty("dockerImage", "ghcr.io/ptero-eggs/yolks:java_21");
        properties.setProperty("startup", "bash ./start-onilink.sh");
        properties.setProperty("onilinkVersion", "v0.1.5");
        properties.setProperty("bdsProfile", "test-profile");
        try (var output = Files.newOutputStream(hosting.resolve("settings.properties"))) {
            properties.store(output, "test settings");
        }
    }

    private static void respond(
            HttpExchange exchange,
            AtomicBoolean created,
            AtomicBoolean suspended,
            AtomicReference<String> createRequest
    ) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String response;
        int status = 200;
        if ("GET".equals(method) && path.equals("/api/application/users")) {
            response = collection("{\"id\":7,\"username\":\"customer\",\"email\":\"customer@example.test\",\"first_name\":\"Test\",\"last_name\":\"Customer\"}");
        } else if ("GET".equals(method) && path.equals("/api/application/nodes")) {
            response = collection("{\"id\":4,\"name\":\"Node One\",\"fqdn\":\"node.example.test\"}");
        } else if ("GET".equals(method) && path.equals("/api/application/nests")) {
            response = collection("{\"id\":1,\"name\":\"OniLink\"}");
        } else if ("GET".equals(method) && path.equals("/api/application/nests/1/eggs")) {
            response = collection("{\"id\":12,\"name\":\"OniLink\",\"startup\":\"bash ./start-onilink.sh\",\"docker_image\":\"ghcr.io/ptero-eggs/yolks:java_21\"}");
        } else if ("GET".equals(method) && path.equals("/api/application/nodes/4/allocations")) {
            response = collection("{\"id\":44,\"ip\":\"127.0.0.1\",\"alias\":null,\"port\":19140,\"assigned\":false}");
        } else if ("GET".equals(method) && path.equals("/api/application/servers/external/onilink-tenant-acme")) {
            if (!created.get()) {
                status = 404;
                response = "{\"errors\":[{\"detail\":\"Not found\"}]}";
            } else {
                response = item("{\"id\":91,\"uuid\":\"tenant-uuid\",\"suspended\":"
                        + suspended.get() + ",\"status\":null}");
            }
        } else if ("POST".equals(method) && path.equals("/api/application/servers")) {
            createRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            created.set(true);
            response = item("{\"id\":91,\"uuid\":\"tenant-uuid\",\"suspended\":false,\"status\":null}");
        } else if ("POST".equals(method) && path.equals("/api/application/servers/91/suspend")) {
            suspended.set(true);
            response = "{}";
        } else {
            status = 404;
            response = "{\"errors\":[{\"detail\":\"Unexpected test route\"}]}";
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String collection(String attributes) {
        return "{\"object\":\"list\",\"data\":[{\"object\":\"resource\",\"attributes\":"
                + attributes + "}],\"meta\":{\"pagination\":{\"total_pages\":1}}}";
    }

    private static String item(String attributes) {
        return "{\"object\":\"resource\",\"attributes\":" + attributes + "}";
    }

    private static Map<String, String> unzip(byte[] archive) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
