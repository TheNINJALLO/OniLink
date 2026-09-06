package dev.onistone.onilink.modules.packs;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.modules.*;
import dev.onistone.onilink.modules.operations.ArtifactStore;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import dev.onistone.onilink.resourcepack.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Immutable resource-pack sets with exact dependency versions and atomic activation for new joins. */
public final class PackReleases extends ScopedRecords {
    private final ArtifactStore artifacts;
    private final PackScannerService scanner;
    private final ProxyOperations proxy;
    public PackReleases(PlatformDatabase db, ArtifactStore artifacts, PackScannerService scanner, ProxyOperations proxy) {
        super(db); this.artifacts = artifacts; this.scanner = scanner; this.proxy = proxy;
    }
    public Map<String, Object> status(PlatformDatabase.Scope scope) {
        return Map.of("releases", views(database.list(scope, "pack-release", 1000)), "history", views(database.list(scope, "pack-activation", 100)),
                "active", database.get(scope, "pack-current", "current").map(ScopedRecords::view).orElse(Map.of("revision", 0)));
    }
    public synchronized Map<String, Object> stage(PlatformDatabase.Scope scope, String name, List<String> ids) throws IOException {
        if (name == null || name.isBlank() || name.length() > 100 || ids.isEmpty() || ids.size() > 32 || new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("a named set must contain 1 to 32 distinct packs");
        Map<String, Map<String, Object>> manifests = new LinkedHashMap<>();
        List<Map<String, Object>> scans = new ArrayList<>();
        long bytes = 0;
        for (String artifact : ids) {
            var metadata = artifacts.get(scope, artifact);
            if (!"pack".equals(metadata.get("kind"))) throw new IllegalArgumentException("expected pack artifact");
            bytes += longValue(metadata.get("bytes"), 0);
            if (bytes > 268435456) throw new IllegalArgumentException("combined pack set exceeds 256 MiB");
            Path path = artifacts.path(scope, artifact);
            Map<String, Object> manifest = readManifest(path);
            var header = (Map<?, ?>) manifest.get("header");
            String uuid = UUID.fromString(String.valueOf(header.get("uuid"))).toString();
            if (manifests.put(uuid, manifest) != null) throw new IllegalArgumentException("duplicate pack UUID in release");
            var report = scanner.scan(scope, uuid + ".mcpack", Files.readAllBytes(path));
            if (!"PASS".equals(report.get("outcome"))) throw new IllegalArgumentException("pack scanner rejected " + uuid);
            if (!(manifest.get("modules") instanceof List<?> modules) || modules.isEmpty()
                    || modules.stream().anyMatch(m -> !(m instanceof Map<?, ?> module) || !"resources".equals(module.get("type"))))
                throw new IllegalArgumentException("proxy pack releases require resource modules; behavior and script packs belong on Endstone backends");
            scans.add(Map.of("artifact", artifact, "scanId", report.get("id"), "outcome", report.get("outcome")));
        }
        validateDependencies(manifests);
        return view(database.put(scope, "pack-release", UUID.randomUUID().toString(), 0L, Map.of("name", name,
                "artifacts", List.copyOf(ids), "state", "VALIDATED", "scans", scans, "packUuids", manifests.keySet().stream().toList())));
    }
    public synchronized Map<String, Object> activate(PlatformDatabase.Scope scope, String releaseId, long revision) throws IOException {
        var release = database.get(scope, "pack-release", releaseId).orElseThrow(() -> new IllegalArgumentException("unknown pack release"));
        var previous = database.get(scope, "pack-current", "current");
        if (previous.map(PlatformDatabase.StoredRecord::revision).orElse(0L) != revision) throw new IllegalStateException("pack activation changed; refresh first");
        var entries = load(scope, release.value());
        var value = Map.<String, Object>of("release", releaseId, "previousRelease", previous.map(r -> r.value().get("release")).orElse(""), "state", "ACTIVE");
        var saved = database.put(scope, "pack-current", "current", revision, value);
        try { proxy.installPacks(entries); }
        catch (RuntimeException failure) {
            if (previous.isPresent()) database.put(scope, "pack-current", "current", saved.revision(), previous.get().value());
            else database.delete(scope, "pack-current", "current", saved.revision());
            throw failure;
        }
        database.put(scope, "pack-activation", UUID.randomUUID().toString(), 0L, value);
        return view(saved);
    }
    public void restore(PlatformDatabase.Scope scope) throws IOException {
        var active = database.get(scope, "pack-current", "current");
        if (active.isPresent()) proxy.installPacks(load(scope, database.get(scope, "pack-release", String.valueOf(active.get().value().get("release"))).orElseThrow().value()));
    }
    private List<ProxyResourcePackEntry> load(PlatformDatabase.Scope scope, Map<String, Object> release) throws IOException {
        List<ProxyResourcePackEntry> entries = new ArrayList<>();
        for (Object artifact : (List<?>) release.get("artifacts")) {
            var entry = ProxyResourcePackRegistry.entryFrom(Files.readAllBytes(artifacts.path(scope, String.valueOf(artifact))));
            if (entry == null) throw new IOException("pack manifest could not be loaded");
            entries.add(entry);
        }
        return List.copyOf(entries);
    }
    private static Map<String, Object> readManifest(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("manifest.json");
            if (entry == null || entry.getSize() > 1048576) throw new IOException("manifest missing or too large");
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(1048577);
                if (bytes.length > 1048576) throw new IOException("manifest too large");
                return ControlJson.parseObject(new String(bytes, StandardCharsets.UTF_8), 1048576);
            }
        }
    }
    private static void validateDependencies(Map<String, Map<String, Object>> manifests) {
        Map<String, Set<String>> remaining = new LinkedHashMap<>();
        manifests.forEach((uuid, manifest) -> {
            Set<String> dependencies = new HashSet<>();
            if (manifest.get("dependencies") instanceof List<?> list) for (Object raw : list) {
                var dependency = (Map<?, ?>) raw;
                String target = String.valueOf(dependency.get("uuid"));
                var provider = manifests.get(target);
                if (provider == null || !Objects.equals(((Map<?, ?>) provider.get("header")).get("version"), dependency.get("version")))
                    throw new IllegalArgumentException("pack dependency missing or version mismatch: " + target);
                dependencies.add(target);
            }
            remaining.put(uuid, dependencies);
        });
        Set<String> resolved = new HashSet<>();
        while (!remaining.isEmpty()) {
            String next = remaining.entrySet().stream().filter(e -> resolved.containsAll(e.getValue())).map(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("cyclic pack dependencies"));
            remaining.remove(next); resolved.add(next);
        }
    }
}
