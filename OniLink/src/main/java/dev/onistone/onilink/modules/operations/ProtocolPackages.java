package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.modules.*;
import dev.onistone.onilink.modules.forge.CompatibilityLab;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import dev.onistone.onilink.plugin.ProtocolPackage;
import dev.onistone.onilink.protocol.ProtocolRegistry;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.jar.JarFile;

/** Only explicitly trusted Ed25519 publishers may supply executable protocol code. */
public final class ProtocolPackages extends ScopedRecords implements AutoCloseable {
    private final ArtifactStore artifacts;
    private final ProxyOperations proxy;
    private final Map<String, PublicKey> keys;
    private final Map<PlatformDatabase.Scope, ProtocolRegistry> bases = new HashMap<>();
    private final List<URLClassLoader> retained = new ArrayList<>();
    public ProtocolPackages(PlatformDatabase db, ArtifactStore artifacts, ProxyOperations proxy, Map<String, PublicKey> keys) {
        super(db); this.artifacts = artifacts; this.proxy = proxy; this.keys = Map.copyOf(keys);
    }
    public static Map<String, PublicKey> readKeys(Path path) throws IOException {
        if (path == null) return Map.of();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (String id : properties.stringPropertyNames()) {
            try { keys.put(id, KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(properties.getProperty(id).trim())))); }
            catch (GeneralSecurityException | IllegalArgumentException failure) { throw new IOException("invalid protocol publisher key " + id, failure); }
        }
        return keys;
    }
    public Map<String, Object> status(PlatformDatabase.Scope scope) {
        return Map.of("trustedPublishers", keys.keySet().stream().sorted().toList(), "packages", views(database.list(scope, "protocol-package", 100)),
                "active", database.get(scope, "protocol-generation", "current").map(ScopedRecords::view).orElse(Map.of("revision", 0, "packages", List.of())),
                "retainedLoaders", retained.size(), "existingSessionsPinned", true);
    }
    public synchronized Map<String, Object> trust(PlatformDatabase.Scope scope, String artifact, String publisher, String signature) throws Exception {
        var metadata = artifacts.get(scope, artifact);
        if (!"protocol".equals(metadata.get("kind"))) throw new IllegalArgumentException("expected protocol artifact");
        Path path = artifacts.path(scope, artifact);
        verify(artifact, publisher, signature);
        Map<String, Object> manifest = manifest(path);
        if (!metadata.get("version").equals(manifest.get("version"))) throw new IllegalArgumentException("package version differs from upload metadata");
        var value = new LinkedHashMap<>(manifest);
        value.putAll(Map.of("artifact", artifact, "publisher", publisher, "signature", signature, "state", "VERIFIED_SIGNATURE"));
        return view(database.put(scope, "protocol-package", artifact, null, value));
    }
    public synchronized Map<String, Object> activate(PlatformDatabase.Scope scope, List<String> requested, long revision) throws Exception {
        return activate(scope, requested, revision, false);
    }
    private synchronized Map<String, Object> activate(PlatformDatabase.Scope scope, List<String> requested, long revision, boolean restoring) throws Exception {
        if (requested.size() > 16 || new HashSet<>(requested).size() != requested.size()) throw new IllegalArgumentException("invalid package set");
        var previous = database.get(scope, "protocol-generation", "current");
        if (previous.map(PlatformDatabase.StoredRecord::revision).orElse(0L) != revision) throw new IllegalStateException("protocol generation changed; refresh first");
        if (!restoring && previous.isPresent() && requested.equals(previous.get().value().get("packages"))) return view(previous.get());
        if (retained.size() + requested.size() > 128) throw new IllegalStateException("protocol loader limit reached; restart to release retired generations");
        ProtocolRegistry base = bases.computeIfAbsent(scope, ignored -> proxy.protocols());
        var builder = base.toBuilder();
        List<URLClassLoader> loading = new ArrayList<>();
        try {
            Map<String, PlatformDatabase.StoredRecord> pending = new LinkedHashMap<>();
            for (String artifact : requested) {
                var record = database.get(scope, "protocol-package", artifact).orElseThrow(() -> new IllegalArgumentException("package signature has not been verified"));
                verify(artifact, String.valueOf(record.value().get("publisher")), String.valueOf(record.value().get("signature")));
                if (pending.put(String.valueOf(record.value().get("packageId")), record) != null) throw new IllegalArgumentException("duplicate package ID");
            }
            Map<String, String> enabled = new HashMap<>();
            List<java.net.URL> urls = new ArrayList<>();
            Set<String> classes = new HashSet<>();
            for (String artifact : requested) {
                Path path = artifacts.path(scope, artifact); urls.add(path.toUri().toURL());
                try (JarFile jar = new JarFile(path.toFile())) {
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.endsWith(".class") && !name.equals("module-info.class") && !classes.add(name))
                            throw new IllegalArgumentException("protocol package set contains duplicate classes");
                    }
                }
            }
            var loader = new URLClassLoader(urls.toArray(java.net.URL[]::new), ProtocolPackage.class.getClassLoader());
            loading.add(loader);
            while (!pending.isEmpty()) {
                var ready = pending.values().stream().filter(r -> dependencies(r.value()).entrySet().stream()
                        .allMatch(e -> Objects.equals(enabled.get(e.getKey()), e.getValue()))).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("missing, incompatible or cyclic protocol dependency"));
                Object instance = Class.forName(String.valueOf(ready.value().get("provider")), true, loader).getDeclaredConstructor().newInstance();
                if (!(instance instanceof ProtocolPackage plugin) || instance.getClass().getClassLoader() != loader)
                    throw new IllegalArgumentException("provider must be supplied by the signed package and implement ProtocolPackage API 1");
                plugin.contribute(builder);
                enabled.put(String.valueOf(ready.value().get("packageId")), String.valueOf(ready.value().get("version")));
                pending.remove(String.valueOf(ready.value().get("packageId")));
            }
            ProtocolRegistry registry = builder.build();
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (String artifact : requested) {
                var report = new CompatibilityLab().runArchive(registry, artifacts.path(scope, artifact));
                if (!"PASS".equals(report.get("status")) || !Boolean.TRUE.equals(report.get("coverageComplete")))
                    throw new IllegalStateException("protocol package fixture coverage failed or is incomplete");
                evidence.add(Map.of("artifact", artifact, "report", report));
            }
            validateChangedRoutes(base, registry, evidence);
            Map<String, Object> value = Map.of("packages", List.copyOf(requested), "previousPackages",
                    previous.map(r -> r.value().get("packages")).orElse(List.of()), "evidence", evidence, "state", "ACTIVE");
            // Persist first: after a crash startup revalidates this exact generation before installing it.
            var saved = restoring ? previous.orElseThrow() : database.put(scope, "protocol-generation", "current", revision, value);
            try { proxy.installProtocols(registry); }
            catch (RuntimeException failure) {
                if (!restoring) {
                    if (previous.isPresent()) database.put(scope, "protocol-generation", "current", saved.revision(), previous.get().value());
                    else database.delete(scope, "protocol-generation", "current", saved.revision());
                }
                throw failure;
            }
            retained.addAll(loading); loading.clear();
            if (!restoring) database.put(scope, "protocol-history", UUID.randomUUID().toString(), 0L, value);
            return view(saved);
        } finally { for (var loader : loading) loader.close(); }
    }
    public void restore(PlatformDatabase.Scope scope) throws Exception {
        var record = database.get(scope, "protocol-generation", "current");
        if (record.isPresent()) activate(scope, strings(record.get().value().get("packages")), record.get().revision(), true);
    }
    private static void validateChangedRoutes(ProtocolRegistry base, ProtocolRegistry candidate, List<Map<String, Object>> evidence) {
        if (candidate.supportedDialects().size() > 64) throw new IllegalArgumentException("protocol package generation exceeds 64 dialects");
        Map<String, Set<String>> covered = new HashMap<>();
        for (var entry : evidence) {
            var report = (Map<?, ?>) entry.get("report");
            for (Object raw : (List<?>) report.get("fixtures")) {
                var fixture = (Map<?, ?>) raw;
                if ("PASS".equals(fixture.get("status"))) covered.computeIfAbsent(fixture.get("clientVersion") + "->" + fixture.get("backendVersion"), ignored -> new HashSet<>())
                        .add(String.valueOf(fixture.get("category")));
            }
        }
        Map<String, org.cloudburstmc.protocol.bedrock.codec.BedrockCodec> previous = new HashMap<>();
        base.supportedDialects().forEach(codec -> previous.put(codec.getMinecraftVersion(), codec));
        for (var client : candidate.supportedDialects()) for (var backend : candidate.supportedDialects()) {
            var path = candidate.findPath(client.getProtocolVersion(), backend.getProtocolVersion());
            if (path.isEmpty()) continue;
            boolean changed = previous.get(client.getMinecraftVersion()) != client || previous.get(backend.getMinecraftVersion()) != backend
                    || !path.equals(base.findPath(client.getProtocolVersion(), backend.getProtocolVersion()));
            String route = client.getMinecraftVersion() + "->" + backend.getMinecraftVersion();
            if (changed && !covered.getOrDefault(route, Set.of()).containsAll(CompatibilityLab.CATEGORIES))
                throw new IllegalStateException("changed protocol route requires complete category fixtures: " + route);
        }
    }
    public static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String))) throw new IllegalArgumentException("expected string list");
        return list.stream().map(String.class::cast).toList();
    }
    private void verify(String artifact, String publisher, String signature) throws GeneralSecurityException {
        PublicKey key = keys.get(publisher);
        if (key == null) throw new GeneralSecurityException("protocol publisher is not trusted");
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(("OniLink-protocol-v1\n" + artifact + "\n").getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getDecoder().decode(signature))) throw new GeneralSecurityException("protocol signature verification failed");
    }
    private static Map<String, String> dependencies(Map<String, Object> manifest) {
        if (!(manifest.get("dependencies") instanceof Map<?, ?> raw)) throw new IllegalArgumentException("package dependencies must be a version map");
        Map<String, String> map = new HashMap<>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), String.valueOf(value)));
        return map;
    }
    private static Map<String, Object> manifest(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry("onilink-protocol.json");
            if (entry == null || entry.getSize() > 65536) throw new IOException("protocol manifest missing or too large");
            Map<String, Object> manifest;
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(65537);
                if (bytes.length > 65536) throw new IOException("manifest too large");
                manifest = ControlJson.parseObject(new String(bytes, StandardCharsets.UTF_8), 65536);
            }
            if (longValue(manifest.get("apiVersion"), 0) != ProtocolPackage.API_VERSION) throw new IOException("unsupported protocol package API");
            if (!required(manifest, "packageId", 64).matches("[a-z][a-z0-9-]*")) throw new IOException("invalid protocol package ID");
            required(manifest, "provider", 256); required(manifest, "version", 64); dependencies(manifest);
            return manifest;
        }
    }
    @Override public void close() { for (var loader : retained) try { loader.close(); } catch (IOException ignored) { } }
}
