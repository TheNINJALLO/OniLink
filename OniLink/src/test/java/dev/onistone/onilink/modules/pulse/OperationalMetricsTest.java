package dev.onistone.onilink.modules.pulse;

import com.sun.net.httpserver.HttpServer;
import dev.onistone.onilink.control.ControlJson;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OperationalMetricsTest {
    @Test void exportsValidOtlpJsonToAnActualHttpCollectorWithoutPlayerData() throws Exception {
        var received = new AtomicReference<Map<String, Object>>();
        var collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
        collector.createContext("/v1/metrics", exchange -> {
            received.set(ControlJson.parseObject(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), 1048576));
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes()); exchange.close();
        });
        collector.start();
        try {
            var metrics = new OperationalMetrics("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/metrics", "");
            metrics.export(metrics.snapshot(Map.of("queued", 2L), 3, 4));
            assertNotNull(received.get()); assertTrue(received.get().containsKey("resourceMetrics"));
            String payload = ControlJson.encode(received.get());
            assertTrue(payload.contains("onilink.playerQueue")); assertFalse(payload.contains("xuid"));
            assertEquals(1L, metrics.snapshot(Map.of(), 0, 0).get("exports"));
            assertThrows(IllegalArgumentException.class, () -> new OperationalMetrics("http://remote.example/v1/metrics", ""));
        } finally { collector.stop(0); }
    }
}
