package dev.onistone.onilink.modules.connect;

import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkCoordinatorTest {
    @TempDir Path directory;
    final PlatformDatabase.Scope scope = PlatformDatabase.Scope.of("tenant", "network");
    @Test void authenticatedHttpExchangeRejectsReplaysAndCrossTenantNodes() throws Exception {
        String key = "test-only-shared-key-with-more-than-32-characters";
        var config = Map.of("node", "authority", "secretEnvironment", "TEST_AUTH", "port", 0,
                "peers", List.of(Map.of("node", "edge", "secretEnvironment", "TEST_EDGE", "scopes", List.of(Map.of("tenant", "tenant", "proxy", "network")))));
        Path file = directory.resolve("cluster.json");
        Files.writeString(file, dev.onistone.onilink.control.ControlJson.encode(config));
        try (var db = new PlatformDatabase(directory.resolve("data")); var authority = new NetworkCoordinator(db, file, ignored -> key)) {
            var client = java.net.http.HttpClient.newHttpClient();
            String time = Long.toString(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
            String body = dev.onistone.onilink.control.ControlJson.encode(Map.of("op", "heartbeat", "tenant", "tenant", "proxy", "network", "players", List.of()));
            var request = signedRequest(authority.port(), body, time, nonce, key);
            assertEquals(200, client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            String forbidden = body.replace("\"tenant\":\"tenant\"", "\"tenant\":\"another\"");
            assertEquals(403, client.send(signedRequest(authority.port(), forbidden, time, UUID.randomUUID().toString(), key), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, client.send(signedRequest(authority.port(), body, "1", UUID.randomUUID().toString(), key), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            Path nodeFile = directory.resolve("node.json");
            Files.writeString(nodeFile, dev.onistone.onilink.control.ControlJson.encode(Map.of("node", "edge", "secretEnvironment", "TEST_EDGE", "coordinator", "http://127.0.0.1:" + authority.port() + "/exchange")));
            try (var nodeDb = new PlatformDatabase(directory.resolve("node-data")); var node = new NetworkCoordinator(nodeDb, nodeFile, ignored -> key)) {
                assertTrue(node.heartbeat(scope, List.of(Map.of("xuid", "5", "name", "Player", "backend", "limbo"))).containsKey("presence"));
            }
        }
    }
    private static java.net.http.HttpRequest signedRequest(int port, String body, String time, String nonce, String key) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(("edge\n" + time + "\n" + nonce + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/exchange"))
                .header("X-Oni-Node", "edge").header("X-Oni-Time", time).header("X-Oni-Nonce", nonce).header("X-Oni-Signature", signature)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
    }
    @Test void sharedLeasesPreventOverbookingAndCannotBeReleasedByAnotherNode() throws Exception {
        try (var db = new PlatformDatabase(directory); var coordinator = NetworkCoordinator.localAuthority(db, "authority")) {
            coordinator.policy(scope, "quota", "game", Map.of("capacity", 1), 0);
            heartbeat(coordinator, "one", "1"); heartbeat(coordinator, "two", "2");
            var first = Map.<String, Object>of("op", "lease", "requestId", "request", "backend", "game", "members", List.of("1"));
            var second = Map.<String, Object>of("op", "lease", "requestId", "request", "backend", "game", "members", List.of("2"));
            assertEquals(true, coordinator.exchange("one", scope, first).get("granted"));
            assertEquals(false, coordinator.exchange("two", scope, second).get("granted"));
            coordinator.exchange("two", scope, Map.of("op", "release", "requestId", "request"));
            assertEquals(false, coordinator.exchange("two", scope, second).get("granted"));
            coordinator.exchange("one", scope, Map.of("op", "release", "requestId", "request"));
            assertEquals(true, coordinator.exchange("two", scope, second).get("granted"));
            assertEquals(false, coordinator.exchange("one", scope, second).get("granted"));
            assertTrue(db.list(PlatformDatabase.Scope.of("other", "network"), "cluster-lease", 100).isEmpty());
        }
    }
    private void heartbeat(NetworkCoordinator coordinator, String node, String xuid) {
        coordinator.exchange(node, scope, Map.of("op", "heartbeat", "players", List.of(Map.of("xuid", xuid, "name", "player" + xuid, "backend", "limbo"))));
    }
}
