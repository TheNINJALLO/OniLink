package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactStoreTest {
    @TempDir Path directory;
    static final PlatformDatabase.Scope SCOPE = PlatformDatabase.Scope.of("tenant", "main");
    static byte[] zip(Map<String, byte[]> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { for (var entry : files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); } }
        return bytes.toByteArray();
    }
    static byte[] executable() {
        byte[] header = new byte[128];
        if (System.getProperty("os.name").toLowerCase().contains("win")) { header[0] = 'M'; header[1] = 'Z'; header[60] = 64; header[64] = 'P'; header[65] = 'E'; header[68] = 0x64; header[69] = (byte) 0x86; }
        else { header[0] = 0x7f; header[1] = 'E'; header[2] = 'L'; header[3] = 'F'; header[4] = 2; header[5] = 1; header[18] = 62; }
        return header;
    }
    static String platform() { return System.getProperty("os.name").toLowerCase().contains("win") ? "windows" : "linux"; }
    static String executableName() { return platform().equals("windows") ? "bedrock_server.exe" : "bedrock_server"; }
    @Test void uploadsAreBoundedVerifiedScopedAndMetadataImmutable() throws Exception {
        try (var db = new PlatformDatabase(directory)) {
            var store = new ArtifactStore(db, directory, 1048576, 2097152);
            byte[] archive = zip(Map.of(executableName(), executable(), "server.properties", "default".getBytes()));
            var report = store.upload(SCOPE, "server", "1.26.45.1", platform(), new ByteArrayInputStream(archive));
            assertEquals("PASS", report.get("archiveValidation"));
            String id = String.valueOf(report.get("id"));
            assertEquals(id, ArtifactStore.sha256(store.path(SCOPE, id)));
            assertThrows(IllegalArgumentException.class, () -> store.path(PlatformDatabase.Scope.of("another", "main"), id));
            assertThrows(IllegalArgumentException.class, () -> store.upload(SCOPE, "server", "1.26.50.1", platform(), new ByteArrayInputStream(archive)));
            var duplicate = store.upload(SCOPE, "server", "1.26.45.1", platform(), new ByteArrayInputStream(archive));
            assertEquals(report.get("id"), duplicate.get("id"));
            assertEquals(report.get("revision"), duplicate.get("revision"));
            Files.writeString(store.path(SCOPE, id), "tampered");
            assertThrows(IllegalStateException.class, () -> store.path(SCOPE, id));
            assertThrows(IllegalArgumentException.class, () -> store.upload(SCOPE, "fixtures", "1.0", "any", new ByteArrayInputStream(new byte[1048577])));
        }
    }
    @Test void unsafeZipPathsAliasesAndWrongExecutablesAreRejected() throws Exception {
        for (String path : List.of("../escape", "a/../escape", "C:/escape", "a\\b", "/absolute", "CON.txt", "foo.", "a//b", "a/./b", "a:b"))
            assertThrows(IOException.class, () -> ArtifactStore.safeEntry(path), path);
        Path file = directory.resolve("duplicate.zip");
        Files.write(file, zip(Map.of("One.txt", new byte[0], "one.TXT", new byte[0])));
        assertThrows(IOException.class, () -> ArtifactStore.inspect(file, "fixtures", "any"));
        Files.write(file, zip(Map.of(executableName(), "wrong".getBytes())));
        assertThrows(IOException.class, () -> ArtifactStore.inspect(file, "server", platform()));
    }
}
