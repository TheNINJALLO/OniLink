package dev.onistone.onilink.modules.pulse;

import dev.onistone.onilink.control.ControlJson;
import io.netty.buffer.PooledByteBufAllocator;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** OTLP/HTTP JSON metrics without player identifiers, payload bytes or exporter credentials in status. */
public final class OperationalMetrics {
    private final URI endpoint;
    private final String authorization;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final AtomicLong exports = new AtomicLong(), failures = new AtomicLong();
    private volatile long nextExport;
    public OperationalMetrics(String endpoint, String authorization) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint);
        this.authorization = authorization == null ? "" : authorization;
        if (this.endpoint != null && (this.endpoint.getUserInfo() != null || this.endpoint.getFragment() != null
                || !("https".equals(this.endpoint.getScheme()) || "http".equals(this.endpoint.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(this.endpoint.getHost()))))
            throw new IllegalArgumentException("OTLP requires HTTPS except on literal loopback");
    }
    public Map<String, Object> snapshot(Map<String, Long> eventBus, int workerQueue, int playerQueue) {
        var runtime = Runtime.getRuntime();
        return Map.of("relayRoutes", RelayMetrics.snapshot(), "eventBus", eventBus, "workerQueue", workerQueue, "playerQueue", playerQueue,
                "heapUsedBytes", runtime.totalMemory() - runtime.freeMemory(), "nettyDirectBytes", PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory(),
                "otlpEnabled", endpoint != null, "exports", exports.get(), "exportFailures", failures.get());
    }
    public void export(Map<String, Object> snapshot) {
        long now = System.currentTimeMillis();
        if (endpoint == null || now < nextExport) return;
        nextExport = now + 30000;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4)).header("Content-Type", "application/json");
            if (!authorization.isBlank()) request.header("Authorization", authorization);
            var response = dev.onistone.onilink.modules.operations.BoundedHttpBody.send(client,
                    request.POST(HttpRequest.BodyPublishers.ofString(ControlJson.encode(otlp(snapshot)), StandardCharsets.UTF_8)).build(), 65536, 5);
            {
                byte[] bytes = response.body();
                if (response.statusCode() != 200) throw new java.io.IOException("OTLP export failed");
                if (bytes.length > 0) {
                    var result = ControlJson.parseObject(new String(bytes, StandardCharsets.UTF_8), 65536);
                    if (result.get("partialSuccess") instanceof Map<?, ?> partial && !partial.isEmpty()) throw new java.io.IOException("OTLP partial success");
                }
            }
            exports.incrementAndGet();
        } catch (Exception failure) { if (failure instanceof InterruptedException) Thread.currentThread().interrupt(); failures.incrementAndGet(); }
    }
    public static Map<String, Object> otlp(Map<String, Object> snapshot) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        String now = Long.toString(System.currentTimeMillis() * 1000000L);
        for (String name : List.of("workerQueue", "playerQueue", "heapUsedBytes", "nettyDirectBytes", "exports", "exportFailures")) {
            metrics.add(gauge("onilink." + name, snapshot.get(name), now, List.of()));
        }
        if (snapshot.get("eventBus") instanceof Map<?, ?> events) events.forEach((key, value) -> metrics.add(gauge("onilink.events." + key, value, now, List.of())));
        if (snapshot.get("relayRoutes") instanceof List<?> routes) for (Object raw : routes) {
            var route = (Map<?, ?>) raw;
            var attributes = List.of(Map.of("key", "protocol.route", "value", Map.of("stringValue", route.get("route"))));
            for (String name : List.of("packets", "failures", "dropped", "sampledNanos", "samples", "sampledAllocatedBytes"))
                metrics.add(gauge("onilink.translation." + name, route.get(name), now, attributes));
        }
        return Map.of("resourceMetrics", List.of(Map.of("resource", Map.of("attributes", List.of(Map.of("key", "service.name", "value", Map.of("stringValue", "OniLink")))),
                "scopeMetrics", List.of(Map.of("scope", Map.of("name", "dev.onistone.onilink.pulse", "version", "1"), "metrics", metrics)))));
    }
    private static Map<String, Object> gauge(String name, Object value, String time, List<?> attributes) {
        return Map.of("name", name, "gauge", Map.of("dataPoints", List.of(Map.of("timeUnixNano", time, "asInt", String.valueOf(value == null ? 0 : value), "attributes", attributes))));
    }
}
