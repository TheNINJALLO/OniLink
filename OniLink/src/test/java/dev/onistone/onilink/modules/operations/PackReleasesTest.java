package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.modules.FakeProxy;
import dev.onistone.onilink.modules.packs.*;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackReleasesTest {
    @TempDir Path directory;
    @Test void dependencyValidationActivationAndRollbackRetainExactPackVersions() throws Exception {
        try (var db = new PlatformDatabase(directory); var events = new BoundedEventBus(64, Runnable::run)) {
            var artifacts = new ArtifactStore(db, directory, 1048576, 4194304); var proxy = new FakeProxy();
            var releases = new PackReleases(db, artifacts, new PackScannerService(db, events, 1048576, 1000, 4194304), proxy);
            String uuid = UUID.randomUUID().toString(); String first = pack(artifacts, uuid, 1, List.of()); String second = pack(artifacts, uuid, 2, List.of());
            String firstRelease = String.valueOf(releases.stage(ArtifactStoreTest.SCOPE, "first", List.of(first)).get("id"));
            releases.activate(ArtifactStoreTest.SCOPE, firstRelease, 0); assertEquals("1.0.0", proxy.packs.getFirst().versionString());
            var snapshot = proxy.packs;
            String secondRelease = String.valueOf(releases.stage(ArtifactStoreTest.SCOPE, "second", List.of(second)).get("id"));
            releases.activate(ArtifactStoreTest.SCOPE, secondRelease, 1); assertEquals("2.0.0", proxy.packs.getFirst().versionString());
            assertEquals("1.0.0", snapshot.getFirst().versionString());
            releases.activate(ArtifactStoreTest.SCOPE, firstRelease, 2); assertEquals("1.0.0", proxy.packs.getFirst().versionString());
            assertThrows(IllegalStateException.class, () -> releases.activate(ArtifactStoreTest.SCOPE, secondRelease, 2));
            String invalid = pack(artifacts, UUID.randomUUID().toString(), 1, List.of(Map.of("uuid", UUID.randomUUID().toString(), "version", List.of(1, 0, 0))));
            assertThrows(IllegalArgumentException.class, () -> releases.stage(ArtifactStoreTest.SCOPE, "missing dependency", List.of(invalid)));
        }
    }
    private String pack(ArtifactStore artifacts, String uuid, int major, List<?> dependencies) throws Exception {
        Map<String, Object> manifest = Map.of("format_version", 2, "header", Map.of("uuid", uuid, "version", List.of(major, 0, 0), "name", "Test pack", "description", "Test"),
                "modules", List.of(Map.of("uuid", UUID.randomUUID().toString(), "version", List.of(major, 0, 0), "type", "resources")), "dependencies", dependencies);
        return String.valueOf(artifacts.upload(ArtifactStoreTest.SCOPE, "pack", major + ".0.0", "any", new ByteArrayInputStream(ArtifactStoreTest.zip(
                Map.of("manifest.json", ControlJson.encode(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8))))).get("id"));
    }
}
