package dev.onistone.onilink.modules.packs;

import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackScannerServiceTest {
    @TempDir Path directory;

    @Test
    void rejectsTraversalCaseDuplicateAssetsAndExecutableContent() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest("11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", ""));
        files.put("../escape.txt", "bad".getBytes(StandardCharsets.UTF_8));
        files.put("textures/A.png", new byte[]{1});
        files.put("textures/a.png", new byte[]{2});
        files.put("scripts/payload.sh", new byte[]{3});

        Map<String, Object> report = scan(files, 1_000_000);
        assertEquals("FAIL", report.get("outcome"));
        Set<String> rules = rules(report);
        assertTrue(rules.contains("ZIP_PATH_TRAVERSAL"));
        assertTrue(rules.contains("DUPLICATE_ASSET_PATH"));
        assertTrue(rules.contains("EXECUTABLE_CONTENT"));
    }

    @Test
    void rejectsExpandedArchiveBombsWithSpecificEvidence() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest("11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", ""));
        files.put("textures/filler.bin", new byte[8_192]);

        Map<String, Object> report = scan(files, 700);
        assertEquals("FAIL", report.get("outcome"));
        assertTrue(rules(report).contains("ZIP_EXPANDED_LIMIT"));
    }

    @Test
    void reportsSelfDependencies() throws Exception {
        String uuid = "11111111-1111-4111-8111-111111111111";
        Map<String, Object> report = scan(Map.of("manifest.json",
                manifest(uuid, "22222222-2222-4222-8222-222222222222", uuid)), 1_000_000);
        assertEquals("FAIL", report.get("outcome"));
        assertTrue(rules(report).contains("CIRCULAR_DEPENDENCY"));
    }

    private Map<String, Object> scan(Map<String, byte[]> files, long expandedLimit) throws Exception {
        try (PlatformDatabase database = new PlatformDatabase(directory);
             BoundedEventBus events = new BoundedEventBus(16, Runnable::run)) {
            PackScannerService scanner = new PackScannerService(database, events, 1_000_000, 100, expandedLimit);
            return scanner.scan(PlatformDatabase.Scope.of("tenant-a", "proxy-a"), "candidate.mcpack", zip(files));
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> rules(Map<String, Object> report) {
        return ((java.util.List<Map<String, Object>>) report.get("findings")).stream()
                .map(item -> String.valueOf(item.get("ruleId"))).collect(java.util.stream.Collectors.toSet());
    }

    private static byte[] manifest(String headerUuid, String moduleUuid, String dependencyUuid) {
        String dependencies = dependencyUuid.isBlank() ? "[]" : "[{\"uuid\":\"" + dependencyUuid + "\",\"version\":[1,0,0]}]";
        return ("{\"format_version\":2,\"header\":{\"name\":\"Candidate\",\"description\":\"test\","
                + "\"uuid\":\"" + headerUuid + "\",\"version\":[1,0,0],\"min_engine_version\":[1,20,0]},"
                + "\"modules\":[{\"type\":\"resources\",\"uuid\":\"" + moduleUuid
                + "\",\"version\":[1,0,0]}],\"dependencies\":" + dependencies + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
