package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.modules.FakeProxy;
import dev.onistone.onilink.modules.continuity.ContinuityService;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import dev.onistone.onilink.protocol.ProtocolFixtureSuites;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateCenterTest {
    @TempDir Path directory;
    @Test void deploymentNeedsNativeEvidenceThenRunsEveryPhaseAndWaitsForReturns() throws Exception {
        Path root = Files.createDirectory(directory.resolve("server"));
        Files.writeString(root.resolve(ArtifactStoreTest.executableName()), "old");
        var proxy = new FakeProxy(); proxy.player("1", "game", false);
        try (var db = new PlatformDatabase(directory.resolve("data")); var events = new BoundedEventBus(100, Runnable::run)) {
            var artifacts = new ArtifactStore(db, directory, 1048576, 4194304);
            var scope = ArtifactStoreTest.SCOPE;
            String candidate = String.valueOf(artifacts.upload(scope, "server", "1.26.45.1", ArtifactStoreTest.platform(),
                    new ByteArrayInputStream(ArtifactStoreTest.zip(Map.of(ArtifactStoreTest.executableName(), ArtifactStoreTest.executable())))).get("id"));
            String fixtures = String.valueOf(artifacts.upload(scope, "fixtures", "1.0.0", "any", new ByteArrayInputStream(ProtocolFixtureSuites.archive())).get("id"));
            var manager = new ManagedServers(ManagedServersTest.configuration(directory, root), directory.resolve("data"));
            var continuity = new ContinuityService(db, proxy, events, "limbo", 100);
            var updates = new UpdateCenter(db, artifacts, manager, proxy, continuity);
            updates.lab(scope, candidate, fixtures);
            assertEquals(false, updates.preview(scope, candidate, "game").get("ready"));
            assertThrows(IllegalStateException.class, () -> updates.deploy(scope, candidate, "game", UUID.randomUUID().toString(), "owner"));
            var acceptance = new LinkedHashMap<String, Object>();
            acceptance.putAll(Map.of("artifact", candidate, "endstoneVersion", "0.11.10", "evidence", "test fixture attestation"));
            for (String check : List.of("startup", "pluginLoaded", "clientJoin", "movement", "inventory", "crafting", "commands", "packs", "transfers", "shutdown")) acceptance.put(check, true);
            updates.acceptNative(scope, acceptance, "owner");
            assertEquals(true, updates.preview(scope, candidate, "game").get("ready"));
            String request = UUID.randomUUID().toString();
            var job = updates.deploy(scope, candidate, "game", request, "owner");
            assertEquals(job, updates.deploy(scope, candidate, "game", request, "owner"));
            updates.tick(scope); assertEquals("DRAINING", db.get(scope, "maintenance-job", request).orElseThrow().value().get("state"));
            assertEquals("old", Files.readString(root.resolve(ArtifactStoreTest.executableName())));
            proxy.player("1", "limbo", false);
            updates.tick(scope); updates.tick(scope); updates.tick(scope);
            assertEquals("WAIT_STOPPED", db.get(scope, "maintenance-job", request).orElseThrow().value().get("state"));
            assertEquals("old", Files.readString(root.resolve(ArtifactStoreTest.executableName())));
            proxy.offline.add("game");
            for (int tick = 0; tick < 4; tick++) updates.tick(scope);
            proxy.offline.clear();
            for (int tick = 0; tick < 3; tick++) updates.tick(scope);
            assertEquals("RETURNING", db.get(scope, "maintenance-job", request).orElseThrow().value().get("state"));
            assertTrue(proxy.draining.get("game"));
            proxy.player("1", "game", false); updates.tick(scope);
            assertEquals("COMPLETED", db.get(scope, "maintenance-job", request).orElseThrow().value().get("state"));
            assertFalse(proxy.draining.get("game"));
            var completed = db.get(scope, "maintenance-job", request).orElseThrow();
            var conflict = db.put(scope, "maintenance-job", UUID.randomUUID().toString(), 0L,
                    Map.of("backend", "game", "state", "DRAINING"));
            assertThrows(IllegalStateException.class, () -> updates.rollback(scope, request, completed.revision()));
            db.delete(scope, conflict.kind(), conflict.id(), conflict.revision());
            updates.rollback(scope, request, completed.revision());
            assertThrows(IllegalStateException.class, () -> updates.rollback(scope, request,
                    db.get(scope, "maintenance-job", request).orElseThrow().revision()));
            proxy.player("1", "limbo", false);
            updates.tick(scope); updates.tick(scope);
            proxy.offline.add("game");
            updates.tick(scope); updates.tick(scope); updates.tick(scope);
            assertEquals("old", Files.readString(root.resolve(ArtifactStoreTest.executableName())));
            proxy.offline.clear(); updates.tick(scope);
            assertEquals("RETURNING", db.get(scope, "maintenance-job", request).orElseThrow().value().get("state"));
            proxy.player("1", "game", false); updates.tick(scope);
            assertEquals("ROLLED_BACK", db.get(scope, "maintenance-job", request).orElseThrow().value().get("state"));
            manager.stop(manager.server(scope, "game"));
        }
    }
    @Test void interruptedMaintenanceRequiresRecoveryWithoutRepeatingItsCommand() throws Exception {
        try (var db = new PlatformDatabase(directory); var events = new BoundedEventBus(8, Runnable::run)) {
            var proxy = new FakeProxy(); var artifacts = new ArtifactStore(db, directory, 1024, 2048);
            String job = UUID.randomUUID().toString();
            db.put(ArtifactStoreTest.SCOPE, "maintenance-job", job, 0L, Map.of("state", "INSTALL_IN_PROGRESS", "phase", "INSTALL_IN_PROGRESS", "backend", "game"));
            new UpdateCenter(db, artifacts, new ManagedServers(null, directory), proxy, new ContinuityService(db, proxy, events, "limbo", 10));
            assertEquals("RECOVERY_REQUIRED", db.get(ArtifactStoreTest.SCOPE, "maintenance-job", job).orElseThrow().value().get("state"));
            assertTrue(proxy.transfers.isEmpty());
        }
    }
    @Test void manualRecoveryRequiresHealthyManagerFreshMatchingProbeAndConfirmedReturns() throws Exception {
        Path root = Files.createDirectory(directory.resolve("server"));
        var scope = ArtifactStoreTest.SCOPE;
        try (var db = new PlatformDatabase(directory.resolve("data")); var events = new BoundedEventBus(100, Runnable::run)) {
            var proxy = new FakeProxy(); proxy.player("1", "game", false);
            var continuity = new ContinuityService(db, proxy, events, "limbo", 100);
            var manager = new ManagedServers(ManagedServersTest.configuration(directory, root), directory.resolve("data"));
            var updates = new UpdateCenter(db, new ArtifactStore(db, directory, 1024, 2048), manager, proxy, continuity);
            continuity.drain(scope, "game", "owner"); proxy.player("1", "limbo", false); continuity.reconcile(scope);
            String jobId = UUID.randomUUID().toString();
            var job = db.put(scope, "maintenance-job", jobId, 0L,
                    Map.of("backend", "game", "actor", "owner", "state", "RECOVERY_REQUIRED", "snapshot", ""));
            var input = new LinkedHashMap<String, Object>(Map.of("job", jobId, "revision", job.revision(),
                    "version", "1.26.45.1", "endstoneVersion", "0.11.10", "evidence", "manual repair fixture"));
            assertThrows(IllegalStateException.class, () -> updates.recover(scope, input, "owner"));
            manager.start(manager.server(scope, "game"));
            input.put("endstoneVersion", "wrong");
            assertThrows(IllegalArgumentException.class, () -> updates.recover(scope, input, "owner"));
            input.put("endstoneVersion", "0.11.10");
            updates.recover(scope, input, "owner");
            assertThrows(IllegalStateException.class, () -> updates.recover(scope, input, "owner"));
            proxy.offline.add("game"); updates.tick(scope);
            assertEquals("HEALTH_CHECK", db.get(scope, "maintenance-job", jobId).orElseThrow().value().get("state"));
            proxy.offline.clear(); updates.tick(scope); updates.tick(scope);
            assertEquals("RETURNING", db.get(scope, "maintenance-job", jobId).orElseThrow().value().get("state"));
            proxy.player("1", "game", false); updates.tick(scope);
            assertEquals("RECOVERED", db.get(scope, "maintenance-job", jobId).orElseThrow().value().get("state"));
            assertFalse(proxy.draining.get("game"));
            manager.stop(manager.server(scope, "game"));
        }
    }
}
