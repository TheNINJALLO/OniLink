package dev.onistone.onilink.modules.connect;

import com.sun.net.httpserver.HttpServer;
import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Single-authority shared state with authenticated nodes and expiring capacity leases. */
public final class NetworkCoordinator extends ScopedRecords implements AutoCloseable {
    private record Peer(byte[] key, Set<PlatformDatabase.Scope> scopes) { }
    private final String node;
    private final byte[] localKey;
    private final URI coordinator;
    private final Map<String, Peer> peers;
    private final Map<String, Long> nonces = new LinkedHashMap<>();
    private boolean noncesLoaded;
    private static final PlatformDatabase.Scope AUTH_SCOPE = PlatformDatabase.Scope.of("provider", "cluster-auth");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private HttpServer server;
    private ExecutorService executor;
    private final boolean enabled;
    private volatile String lastError = "";
    private NetworkCoordinator(PlatformDatabase db, String node) {
        super(db); enabled = true; this.node = node; localKey = new byte[0]; coordinator = null; peers = Map.of();
    }
    public static NetworkCoordinator localAuthority(PlatformDatabase db, String node) { return new NetworkCoordinator(db, node); }
    public NetworkCoordinator(PlatformDatabase db, Path configuration) throws IOException {
        this(db, configuration, System::getenv);
    }
    NetworkCoordinator(PlatformDatabase db, Path configuration, java.util.function.Function<String, String> environment) throws IOException {
        super(db);
        if (configuration == null) { enabled = false; node = "local"; localKey = new byte[0]; coordinator = null; peers = Map.of(); return; }
        var config = ControlJson.parseObject(Files.readString(configuration), 262144);
        enabled = true;
        node = required(config, "node", 64);
        if (!node.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IOException("invalid cluster node ID");
        localKey = secret(required(config, "secretEnvironment", 128), environment);
        String uri = String.valueOf(config.getOrDefault("coordinator", ""));
        coordinator = uri.isBlank() ? null : URI.create(uri);
        if (coordinator != null && (coordinator.getUserInfo() != null || coordinator.getQuery() != null || coordinator.getFragment() != null
                || !("https".equals(coordinator.getScheme()) || "http".equals(coordinator.getScheme()) && loopback(coordinator.getHost()))))
            throw new IOException("coordinator requires HTTPS except on literal loopback addresses");
        Map<String, Peer> configured = new HashMap<>();
        if (config.get("peers") instanceof List<?> list) for (Object raw : list) {
            @SuppressWarnings("unchecked") var peer = (Map<String, Object>) raw;
            Set<PlatformDatabase.Scope> scopes = new HashSet<>();
            if (!(peer.get("scopes") instanceof List<?> allowed)) throw new IOException("peer scopes are required");
            for (Object value : allowed) {
                var scope = (Map<?, ?>) value;
                scopes.add(PlatformDatabase.Scope.of(String.valueOf(scope.get("tenant")), String.valueOf(scope.get("proxy"))));
            }
            configured.put(required(peer, "node", 64), new Peer(secret(required(peer, "secretEnvironment", 128), environment), Set.copyOf(scopes)));
        }
        peers = Map.copyOf(configured);
        if (coordinator == null) {
            String host = String.valueOf(config.getOrDefault("bindHost", "127.0.0.1"));
            int port = Math.toIntExact(longValue(config.get("port"), 0));
            if (port < 0 || port > 65535 || peers.isEmpty()) throw new IOException("authority requires a valid port and explicit peers");
            server = HttpServer.create(new InetSocketAddress(host, port), 32);
            executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "onilink-cluster-http"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            server.setExecutor(executor);
            server.createContext("/exchange", exchange -> {
                try {
                    if (!exchange.getRequestURI().getPath().equals("/exchange") || !"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                    byte[] body = exchange.getRequestBody().readNBytes(1048577);
                    if (body.length > 1048576) throw new IllegalArgumentException("cluster request too large");
                    String sender = exchange.getRequestHeaders().getFirst("X-Oni-Node");
                    authenticate(sender, exchange.getRequestHeaders().getFirst("X-Oni-Time"), exchange.getRequestHeaders().getFirst("X-Oni-Nonce"),
                            exchange.getRequestHeaders().getFirst("X-Oni-Signature"), body);
                    var request = ControlJson.parseObject(new String(body, StandardCharsets.UTF_8), 1048576);
                    var scope = PlatformDatabase.Scope.of(required(request, "tenant", 128), required(request, "proxy", 128));
                    if (!peers.get(sender).scopes().contains(scope)) throw new SecurityException("node cannot access this scope");
                    byte[] response = ControlJson.encode(exchange(sender, scope, request)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    // Bind the response to this request nonce; TLS and the MAC both protect returned routing/quota state.
                    exchange.getResponseHeaders().set("X-Oni-Signature", sign(peers.get(sender).key(), exchange.getRequestHeaders().getFirst("X-Oni-Nonce"), response));
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } catch (SecurityException failure) { exchange.sendResponseHeaders(403, -1); }
                catch (Exception failure) { exchange.sendResponseHeaders(400, -1); }
                finally { exchange.close(); }
            });
            server.start();
        }
    }
    public boolean enabled() { return enabled; }
    public int port() { return server == null ? -1 : server.getAddress().getPort(); }
    public Map<String, Object> status(PlatformDatabase.Scope scope) {
        return Map.of("enabled", enabled, "node", node, "authority", enabled && coordinator == null, "lastError", lastError,
                "presence", views(database.list(scope, "cluster-presence", 1000)), "policies", views(database.list(scope, "cluster-policy", 1000)),
                "leases", views(database.list(scope, "cluster-lease", 1000)), "failover", "Reconnect through another node; active sessions do not migrate.");
    }
    public synchronized Map<String, Object> policy(PlatformDatabase.Scope scope, String kind, String target, Map<String, Object> value, long revision) {
        if (!enabled || coordinator != null) throw new IllegalStateException("edit shared policy on the configured authority dashboard");
        if (!Set.of("moderation", "routing", "quota", "node").contains(kind)) throw new IllegalArgumentException("unknown shared policy");
        if (kind.equals("moderation") && !target.matches("[0-9]{1,32}")) throw new IllegalArgumentException("moderation target must be an XUID");
        if (kind.equals("quota")) {
            long capacity = longValue(value.get("capacity"), 0);
            if (capacity < 1 || capacity > 100000) throw new IllegalArgumentException("invalid quota capacity");
        }
        if (kind.equals("node") && Boolean.TRUE.equals(value.get("retired"))) {
            if (database.get(scope, "cluster-presence", target).map(p -> longValue(p.value().get("expiresAt"), 0) > System.currentTimeMillis()).orElse(false))
                throw new IllegalStateException("a node with a live heartbeat cannot be retired");
        }
        var data = new LinkedHashMap<>(value); data.put("type", kind); data.put("target", id(target));
        var saved = database.put(scope, "cluster-policy", kind + ":" + target, revision, data);
        if (kind.equals("node") && Boolean.TRUE.equals(value.get("retired"))) {
            database.get(scope, "cluster-occupancy", target).ifPresent(r -> database.delete(scope, r.kind(), r.id(), r.revision()));
            for (var lease : database.list(scope, "cluster-lease", 10000)) if (target.equals(lease.value().get("node"))) database.delete(scope, lease.kind(), lease.id(), lease.revision());
        }
        return view(saved);
    }
    public Map<String, Object> heartbeat(PlatformDatabase.Scope scope, List<Map<String, Object>> players) {
        if (!enabled) return Map.of();
        List<Map<String, Object>> sanitized = players.stream().limit(10000).map(p -> Map.<String, Object>of("xuid", p.get("xuid"), "name", p.get("name"),
                "backend", p.get("backend"), "protocol", p.getOrDefault("protocol", ""))).toList();
        try { var response = request(scope, Map.of("op", "heartbeat", "players", sanitized)); lastError = ""; return response; }
        catch (Exception failure) { lastError = failure.getClass().getSimpleName(); return Map.of(); }
    }
    public boolean lease(PlatformDatabase.Scope scope, String requestId, String backend, List<String> members) {
        if (!enabled) return true;
        try { return Boolean.TRUE.equals(request(scope, Map.of("op", "lease", "requestId", requestId, "backend", backend, "members", members)).get("granted")); }
        catch (Exception failure) { lastError = failure.getClass().getSimpleName(); return false; }
    }
    public void release(PlatformDatabase.Scope scope, String requestId) {
        if (!enabled) return;
        try { request(scope, Map.of("op", "release", "requestId", requestId)); } catch (Exception failure) { lastError = failure.getClass().getSimpleName(); }
    }
    private Map<String, Object> request(PlatformDatabase.Scope scope, Map<String, Object> request) throws Exception {
        if (coordinator == null) return exchange(node, scope, request);
        var envelope = new LinkedHashMap<>(request); envelope.put("tenant", scope.tenantId()); envelope.put("proxy", scope.proxyId());
        byte[] body = ControlJson.encode(envelope).getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        var http = HttpRequest.newBuilder(coordinator).timeout(Duration.ofSeconds(4)).header("Content-Type", "application/json")
                .header("X-Oni-Node", node).header("X-Oni-Time", timestamp).header("X-Oni-Nonce", nonce)
                .header("X-Oni-Signature", sign(localKey, node + "\n" + timestamp + "\n" + nonce, body)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response = dev.onistone.onilink.modules.operations.BoundedHttpBody.send(client, http, 1048576, 5);
        {
            byte[] bytes = response.body();
            if (response.statusCode() != 200) throw new IOException("coordinator response failed");
            if (!constantEquals(sign(localKey, nonce, bytes), response.headers().firstValue("X-Oni-Signature").orElse(""))) throw new SecurityException("invalid authority response signature");
            return ControlJson.parseObject(new String(bytes, StandardCharsets.UTF_8), 1048576);
        }
    }
    private synchronized void authenticate(String sender, String time, String nonce, String signature, byte[] bytes) throws Exception {
        Peer peer = peers.get(sender);
        long now = System.currentTimeMillis();
        if (peer == null || time == null || nonce == null || !nonce.matches("[a-f0-9-]{36}") || Long.parseLong(time) < now - 30000 || Long.parseLong(time) > now + 30000)
            throw new SecurityException("invalid cluster identity or timestamp");
        if (!noncesLoaded) {
            for (var record : database.list(AUTH_SCOPE, "cluster-nonce", 10000)) nonces.put(record.id(), longValue(record.value().get("expiresAt"), 0));
            noncesLoaded = true;
        }
        nonces.entrySet().removeIf(e -> {
            if (e.getValue() >= now) return false;
            database.get(AUTH_SCOPE, "cluster-nonce", e.getKey()).ifPresent(r -> database.delete(AUTH_SCOPE, r.kind(), r.id(), r.revision()));
            return true;
        });
        if (nonces.size() >= 10000 || nonces.containsKey(sender + ':' + nonce)
                || !constantEquals(sign(peer.key(), sender + "\n" + time + "\n" + nonce, bytes), signature)) throw new SecurityException("invalid or replayed cluster request");
        try { database.put(AUTH_SCOPE, "cluster-nonce", sender + ':' + nonce, 0L, Map.of("expiresAt", now + 60000)); }
        catch (PlatformDatabase.RevisionConflict replay) { throw new SecurityException("replayed cluster request"); }
        nonces.put(sender + ':' + nonce, now + 60000);
    }
    public synchronized Map<String, Object> exchange(String sender, PlatformDatabase.Scope scope, Map<String, Object> request) {
        long now = System.currentTimeMillis();
        for (String kind : List.of("cluster-presence", "cluster-lease")) for (var record : database.list(scope, kind, 10000))
            if (longValue(record.value().get("expiresAt"), 0) < now && (kind.equals("cluster-presence")
                    || database.get(scope, "cluster-presence", String.valueOf(record.value().get("node"))).map(p -> longValue(p.value().get("expiresAt"), 0) > now).orElse(false)))
                database.delete(scope, record.kind(), record.id(), record.revision());
        if (database.get(scope, "cluster-policy", "node:" + sender).map(p -> Boolean.TRUE.equals(p.value().get("retired"))).orElse(false))
            throw new SecurityException("node is retired");
        String operation = required(request, "op", 32);
        if (operation.equals("heartbeat")) {
            if (!(request.get("players") instanceof List<?> players) || players.size() > 1000) throw new IllegalArgumentException("presence snapshot exceeds 1000 players per node");
            var known = database.list(scope, "cluster-presence", 1000);
            if (known.stream().filter(p -> !p.id().equals(sender)).mapToInt(p -> ((List<?>) p.value().get("players")).size()).sum() + players.size() > 4000
                    || known.size() >= 64 && known.stream().noneMatch(p -> p.id().equals(sender))) throw new IllegalStateException("cluster presence capacity exceeded");
            List<Map<String, Object>> sanitized = new ArrayList<>();
            for (Object raw : players) {
                @SuppressWarnings("unchecked") var player = (Map<String, Object>) raw;
                String xuid = required(player, "xuid", 32);
                if (!xuid.matches("[0-9]{1,32}")) throw new IllegalArgumentException("invalid presence XUID");
                sanitized.add(Map.of("xuid", xuid, "name", required(player, "name", 128), "backend", required(player, "backend", 64), "node", sender));
            }
            database.put(scope, "cluster-presence", id(sender), null, Map.of("node", sender, "players", sanitized, "expiresAt", now + 45000));
            database.put(scope, "cluster-occupancy", id(sender), null, Map.of("node", sender, "players", sanitized));
            return Map.of("presence", views(database.list(scope, "cluster-presence", 1000)), "policies", views(database.list(scope, "cluster-policy", 1000)), "expiresAt", now + 45000);
        }
        String requestId = required(request, "requestId", 128);
        String leaseId = id(sender + ':' + requestId);
        if (operation.equals("release")) {
            database.get(scope, "cluster-lease", leaseId).ifPresent(r -> database.delete(scope, r.kind(), r.id(), r.revision()));
            return Map.of("released", true);
        }
        if (!operation.equals("lease")) throw new IllegalArgumentException("unknown node operation");
        String backend = required(request, "backend", 64);
        if (!(request.get("members") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 8) throw new IllegalArgumentException("invalid lease group");
        Set<String> members = new HashSet<>(raw.stream().map(String::valueOf).toList());
        var policy = database.get(scope, "cluster-policy", "quota:" + backend);
        if (policy.isEmpty()) return Map.of("granted", false, "reason", "quota not configured");
        Map<String, String> occupied = new HashMap<>();
        Set<String> owned = new HashSet<>();
        for (var presence : database.list(scope, "cluster-occupancy", 1000)) for (Object item : (List<?>) presence.value().get("players")) {
            var player = (Map<?, ?>) item; String xuid = String.valueOf(player.get("xuid"));
            occupied.putIfAbsent(xuid, String.valueOf(player.get("backend")));
            if (sender.equals(presence.id()) && database.get(scope, "cluster-presence", sender).isPresent()) owned.add(xuid);
        }
        if (!owned.containsAll(members)) return Map.of("granted", false, "reason", "presence expired or belongs to another node");
        Set<String> claimed = new HashSet<>();
        for (var lease : database.list(scope, "cluster-lease", 10000)) {
            var users = (List<?>) lease.value().get("members");
            if (!lease.id().equals(leaseId) && users.stream().anyMatch(members::contains)) return Map.of("granted", false, "reason", "player already has a capacity lease");
            if (backend.equals(lease.value().get("backend"))) users.forEach(x -> claimed.add(String.valueOf(x)));
        }
        occupied.forEach((xuid, target) -> { if (backend.equals(target)) claimed.add(xuid); });
        claimed.addAll(members);
        long capacity = longValue(policy.get().value().get("capacity"), 0);
        if (claimed.size() > capacity) return Map.of("granted", false, "reason", "capacity exhausted");
        if (database.list(scope, "cluster-lease", 10000).size() >= 10000 && database.get(scope, "cluster-lease", leaseId).isEmpty()) return Map.of("granted", false, "reason", "lease limit reached");
        database.put(scope, "cluster-lease", leaseId, null, Map.of("node", sender, "backend", backend, "members", members.stream().sorted().toList(), "expiresAt", now + 150000));
        return Map.of("granted", true, "expiresAt", now + 150000);
    }
    private static byte[] secret(String name, java.util.function.Function<String, String> environment) throws IOException {
        if (!name.matches("[A-Z_][A-Z0-9_]{0,127}")) throw new IOException("invalid cluster secret environment name");
        String value = environment.apply(name);
        if (value == null || value.length() < 32) throw new IOException("cluster secret must contain at least 32 characters");
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String sign(byte[] key, String prefix, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update((prefix + "\n").getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
    private static boolean constantEquals(String left, String right) { return right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII)); }
    private static boolean loopback(String host) { return "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host); }
    @Override public void close() { if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }
}
