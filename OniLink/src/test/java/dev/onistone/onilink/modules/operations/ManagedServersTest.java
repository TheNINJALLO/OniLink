package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedServersTest {
    @TempDir Path directory;
    static List<String> command(String action) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", ArtifactStoreTest.platform().equals("windows") ? "java.exe" : "java").toString();
        String classes = Path.of(ManagedProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java, "-cp", classes, ManagedProcessFixture.class.getName(), action);
    }
    static Path configuration(Path directory, Path server) throws Exception {
        Path config = directory.resolve("managed.json");
        Files.writeString(config, ControlJson.encode(Map.of("servers", List.of(Map.of("tenant", "tenant", "proxy", "main", "backend", "game",
                "runtime", "Endstone", "endstoneVersion", "0.11.10", "eulaAccepted", true, "directory", server.toString(),
                "startCommand", command("start"), "stopCommand", command("stop"), "healthCommand", command("health"))))));
        return config;
    }
    @Test void stopBackupInstallRestorePreservesWorldsAndEndstonePlugins() throws Exception {
        Path root = Files.createDirectory(directory.resolve("server"));
        Files.writeString(root.resolve("server.properties"), "operator settings");
        Files.createDirectory(root.resolve("worlds")); Files.writeString(root.resolve("worlds/world.dat"), "world state");
        Files.createDirectory(root.resolve("plugins")); Files.writeString(root.resolve("plugins/OniBridge.dll"), "Endstone plugin");
        Files.writeString(root.resolve(ArtifactStoreTest.executableName()), "old server");
        var manager = new ManagedServers(configuration(directory, root), directory.resolve("data"));
        var server = manager.server(ArtifactStoreTest.SCOPE, "game");
        manager.start(server); assertTrue(manager.healthy(server));
        manager.stop(server); assertFalse(manager.healthy(server));
        String snapshot = manager.backup(server, UUID.randomUUID().toString());
        Path archive = directory.resolve("server.zip");
        Files.write(archive, ArtifactStoreTest.zip(Map.of(ArtifactStoreTest.executableName(), ArtifactStoreTest.executable(),
                "server.properties", "new defaults".getBytes(), "plugins/OniBridge.dll", "bad replacement".getBytes(), "new-vendor-file", new byte[]{1})));
        manager.install(server, archive);
        assertEquals("operator settings", Files.readString(root.resolve("server.properties")));
        assertEquals("Endstone plugin", Files.readString(root.resolve("plugins/OniBridge.dll")));
        assertTrue(Files.exists(root.resolve("new-vendor-file")));
        manager.restore(server, snapshot);
        assertEquals("old server", Files.readString(root.resolve(ArtifactStoreTest.executableName())));
        assertEquals("world state", Files.readString(root.resolve("worlds/world.dat")));
        assertFalse(Files.exists(root.resolve("new-vendor-file")));
        manager.start(server); assertTrue(manager.healthy(server)); manager.stop(server);
    }
    @Test void overlappingRootsAndUnknownBackendsAreRejected() throws Exception {
        Path root = Files.createDirectory(directory.resolve("server"));
        var manager = new ManagedServers(configuration(directory, root), directory.resolve("data"));
        assertThrows(IllegalArgumentException.class, () -> manager.server(PlatformDatabase.Scope.of("other", "main"), "game"));
        assertThrows(java.io.IOException.class, () -> new ManagedServers(configuration(directory, root), root));
    }
}
