package dev.onistone.onilink.modules.packs;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.events.OniEvent;
import dev.onistone.onilink.platform.events.OniEventType;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Non-activating bounded ZIP/manifest conflict scanner for candidate Bedrock packs. */
public final class PackScannerService extends ScopedRecords {
    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            ".exe", ".dll", ".so", ".dylib", ".jar", ".class", ".ps1", ".bat", ".cmd", ".sh");
    private final BoundedEventBus events;
    private final int maxArchiveBytes;
    private final int maxEntries;
    private final long maxExpandedBytes;

    public PackScannerService(
            PlatformDatabase database, BoundedEventBus events,
            int maxArchiveBytes, int maxEntries, long maxExpandedBytes
    ) {
        super(database);
        this.events = events;
        this.maxArchiveBytes = maxArchiveBytes;
        this.maxEntries = maxEntries;
        this.maxExpandedBytes = maxExpandedBytes;
    }

    public List<Map<String, Object>> history(PlatformDatabase.Scope scope) {
        return views(database.list(scope, "pack-scan", 1_000));
    }

    public Map<String, Object> scanBase64(
            PlatformDatabase.Scope scope, String fileName, String encoded
    ) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?i:mcpack|zip)")) {
            throw new IllegalArgumentException("candidate filename must end in .mcpack or .zip");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded == null ? "" : encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("candidate archive is not valid Base64");
        }
        return scan(scope, fileName, bytes);
    }

    public Map<String, Object> scanFile(PlatformDatabase.Scope scope, Path allowedRoot, Path candidate)
            throws IOException {
        Path root = allowedRoot.toRealPath();
        Path file = candidate.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
            throw new SecurityException("candidate file is outside the configured pack directory");
        }
        if (Files.size(file) > maxArchiveBytes) throw new IllegalArgumentException("candidate archive is too large");
        return scan(scope, file.getFileName().toString(), Files.readAllBytes(file));
    }

    public Map<String, Object> scan(PlatformDatabase.Scope scope, String fileName, byte[] archive) {
        String scanId = UUID.randomUUID().toString();
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> manifest = Map.of();
        Set<String> paths = new LinkedHashSet<>();
        long expanded = 0;
        int entries = 0;
        if (archive == null || archive.length == 0 || archive.length > maxArchiveBytes) {
            finding(findings, "ERROR", "ARCHIVE_SIZE", fileName, "", "Archive is empty or exceeds the size limit",
                    "Upload a complete archive within the configured maximum.");
        } else {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries++;
                    if (entries > maxEntries) {
                        finding(findings, "ERROR", "ZIP_ENTRY_LIMIT", fileName, entry.getName(),
                                "Archive has too many entries", "Remove generated or unnecessary files.");
                        break;
                    }
                    String path = normalizedEntry(entry.getName());
                    if (path == null) {
                        finding(findings, "ERROR", "ZIP_PATH_TRAVERSAL", fileName, entry.getName(),
                                "Archive entry escapes the pack root", "Use relative paths without '..' or absolute prefixes.");
                        drain(zip, 1_048_576);
                        continue;
                    }
                    if (!paths.add(path.toLowerCase(Locale.ROOT))) {
                        finding(findings, "ERROR", "DUPLICATE_ASSET_PATH", fileName, path,
                                "The same case-insensitive asset path appears more than once", "Keep one canonical asset path.");
                    }
                    if (EXECUTABLE_EXTENSIONS.stream().anyMatch(path.toLowerCase(Locale.ROOT)::endsWith)) {
                        finding(findings, "ERROR", "EXECUTABLE_CONTENT", fileName, path,
                                "Executable content is not valid pack material", "Remove executable files from the pack.");
                    }
                    byte[] content;
                    try {
                        content = readBounded(zip, Math.min(maxExpandedBytes - expanded, 4_194_304));
                    } catch (ExpandedLimit failure) {
                        finding(findings, "ERROR", "ZIP_EXPANDED_LIMIT", fileName, path,
                                "Expanded archive exceeds the configured limit", "Reduce uncompressed pack size.");
                        break;
                    }
                    expanded += content.length;
                    if (expanded > maxExpandedBytes) {
                        finding(findings, "ERROR", "ZIP_EXPANDED_LIMIT", fileName, path,
                                "Expanded archive exceeds the configured limit", "Reduce uncompressed pack size.");
                        break;
                    }
                    long compressed = entry.getCompressedSize();
                    if (compressed > 0 && content.length / Math.max(1, compressed) > 200) {
                        finding(findings, "ERROR", "ZIP_COMPRESSION_RATIO", fileName, path,
                                "Entry has a suspicious compression ratio", "Rebuild the archive without highly repetitive filler.");
                    }
                    if ("manifest.json".equalsIgnoreCase(path)) {
                        try {
                            manifest = ControlJson.parseObject(new String(content, java.nio.charset.StandardCharsets.UTF_8),
                                    1_048_576);
                        } catch (IllegalArgumentException failure) {
                            finding(findings, "ERROR", "MANIFEST_JSON", fileName, path,
                                    "manifest.json is invalid JSON", "Correct the JSON syntax and upload again.");
                        }
                    }
                }
            } catch (IOException failure) {
                finding(findings, "ERROR", "INVALID_ZIP", fileName, "", "Archive structure is invalid",
                        "Create a standard ZIP or MCPACK archive.");
            }
        }
        if (manifest.isEmpty()) {
            finding(findings, "ERROR", "MANIFEST_MISSING", fileName, "manifest.json",
                    "A valid manifest.json was not found", "Add a supported Bedrock pack manifest.");
        }
        ManifestEvidence evidence = validateManifest(fileName, manifest, findings);
        compareHistory(scope, fileName, evidence, sha256(archive), paths, findings);
        String outcome = findings.stream().anyMatch(item -> "ERROR".equals(item.get("severity"))) ? "FAIL" : "PASS";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fileName", fileName);
        report.put("outcome", outcome);
        report.put("fingerprint", sha256(archive));
        report.put("archiveBytes", archive == null ? 0 : archive.length);
        report.put("expandedBytes", expanded);
        report.put("entryCount", entries);
        report.put("manifestVersion", evidence.version());
        report.put("packUuid", evidence.packUuid());
        report.put("moduleUuids", evidence.moduleUuids());
        report.put("dependencies", evidence.dependencies());
        report.put("dependencyGraph", Map.of(evidence.packUuid(), evidence.dependencies()));
        report.put("assetPaths", List.copyOf(paths));
        report.put("conflictGroups", conflictGroups(findings));
        report.put("findings", List.copyOf(findings));
        report.put("scannedAt", Instant.now().toString());
        Map<String, Object> stored = view(database.put(scope, "pack-scan", scanId, null, report));
        events.publish(OniEvent.of("PASS".equals(outcome) ? OniEventType.PACK_SCAN_COMPLETED : OniEventType.PACK_SCAN_FAILED,
                scope.tenantId(), scope.proxyId(), Map.of("scanId", scanId, "outcome", outcome)));
        return stored;
    }

    private ManifestEvidence validateManifest(
            String fileName, Map<String, Object> manifest, List<Map<String, Object>> findings
    ) {
        if (manifest.isEmpty()) return new ManifestEvidence("", "", List.of(), List.of());
        Map<String, Object> header = object(manifest.get("header"));
        String packUuid = string(header.get("uuid"));
        String version = version(header.get("version"));
        if (!uuid(packUuid)) finding(findings, "ERROR", "PACK_UUID", fileName, "manifest.json",
                "Header UUID is missing or invalid", "Generate a unique RFC 4122 UUID.");
        if (version.isBlank()) finding(findings, "ERROR", "SEMANTIC_VERSION", fileName, "manifest.json",
                "Header version must be a three-integer Bedrock version", "Use a version such as [1, 0, 0].");
        List<String> modules = new ArrayList<>();
        for (Object raw : array(manifest.get("modules"))) {
            Map<String, Object> module = object(raw);
            String moduleUuid = string(module.get("uuid"));
            if (!uuid(moduleUuid)) finding(findings, "ERROR", "MODULE_UUID", fileName, "manifest.json",
                    "A module UUID is missing or invalid", "Give every module a unique RFC 4122 UUID.");
            else if (!modules.add(moduleUuid)) finding(findings, "ERROR", "DUPLICATE_MODULE_UUID", fileName,
                    "manifest.json", "A module UUID is duplicated", "Generate a distinct UUID for each module.");
        }
        List<String> dependencies = new ArrayList<>();
        for (Object raw : array(manifest.get("dependencies"))) {
            String dependency = string(object(raw).get("uuid"));
            if (uuid(dependency)) dependencies.add(dependency);
        }
        if (dependencies.contains(packUuid)) finding(findings, "ERROR", "CIRCULAR_DEPENDENCY", fileName,
                "manifest.json", "Pack depends on itself", "Remove the self-reference.");
        return new ManifestEvidence(packUuid, version, List.copyOf(modules), List.copyOf(dependencies));
    }

    private void compareHistory(
            PlatformDatabase.Scope scope, String fileName, ManifestEvidence evidence, String fingerprint,
            Set<String> paths,
            List<Map<String, Object>> findings
    ) {
        List<PlatformDatabase.StoredRecord> history = database.list(scope, "pack-scan", 1_000);
        Set<String> knownUuids = new HashSet<>();
        int pathConflicts = 0;
        for (PlatformDatabase.StoredRecord scan : history) {
            String priorUuid = String.valueOf(scan.value().getOrDefault("packUuid", ""));
            if (!priorUuid.isBlank()) knownUuids.add(priorUuid);
            if (!evidence.packUuid().isBlank() && evidence.packUuid().equals(priorUuid)) {
                String priorFile = String.valueOf(scan.value().getOrDefault("fileName", ""));
                if (!priorFile.equalsIgnoreCase(fileName)) {
                    finding(findings, "ERROR", "DUPLICATE_PACK_UUID", fileName, "manifest.json",
                            "Another scanned pack uses this header UUID", "Generate a unique header UUID.");
                }
                if (evidence.version().equals(scan.value().get("manifestVersion"))
                        && !fingerprint.equals(scan.value().get("fingerprint"))) {
                    finding(findings, "ERROR", "CONTENT_CHANGED_WITHOUT_VERSION", priorFile,
                            "manifest.json", "Pack content changed without a manifest version increase",
                            "Increase the manifest version before deployment.");
                }
            }
            for (Object priorPath : array(scan.value().get("assetPaths"))) {
                String path = String.valueOf(priorPath).toLowerCase(Locale.ROOT);
                if (!"manifest.json".equals(path) && paths.contains(path) && pathConflicts++ < 100) {
                    finding(findings, "WARNING", "ASSET_PATH_CONFLICT", fileName, path,
                            "Another scanned pack contains the same asset path",
                            "Review pack stack order or rename one asset.");
                }
            }
        }
        for (String dependency : evidence.dependencies()) {
            if (!knownUuids.contains(dependency)) {
                finding(findings, "WARNING", "MISSING_DEPENDENCY_EVIDENCE", fileName, "manifest.json",
                        "No previously scanned pack provides dependency " + dependency,
                        "Scan the dependency pack in this tenant scope before deployment.");
            }
        }
    }

    private record ManifestEvidence(String packUuid, String version, List<String> moduleUuids, List<String> dependencies) {}

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        if (maximum < 0) return new byte[0];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) throw new ExpandedLimit();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static final class ExpandedLimit extends IOException {}

    private static void drain(InputStream input, long maximum) throws IOException {
        byte[] buffer = new byte[8_192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0 && (total += read) <= maximum) { }
    }

    private static String normalizedEntry(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0 || raw.startsWith("/") || raw.startsWith("\\")) return null;
        String normalized = raw.replace('\\', '/');
        if (normalized.matches("^[A-Za-z]:.*")) return null;
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute() || path.startsWith("..")) return null;
        return path.toString().replace('\\', '/');
    }

    private static void finding(
            List<Map<String, Object>> findings, String severity, String rule, String pack,
            String path, String description, String correction
    ) {
        findings.add(Map.of("severity", severity, "ruleId", rule, "pack", pack, "filePath", path,
                "description", description, "suggestedCorrection", correction));
    }

    private static List<String> conflictGroups(List<Map<String, Object>> findings) {
        return findings.stream().filter(item -> String.valueOf(item.get("ruleId")).contains("DUPLICATE")
                        || String.valueOf(item.get("ruleId")).contains("CONFLICT"))
                .map(item -> String.valueOf(item.get("ruleId"))).distinct().toList();
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes == null ? new byte[0] : bytes));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String version(Object raw) {
        List<Object> values = array(raw);
        if (values.size() != 3 || values.stream().anyMatch(value -> !(value instanceof Number))) return "";
        return values.get(0) + "." + values.get(1) + "." + values.get(2);
    }
    private static boolean uuid(String value) {
        try { UUID.fromString(value); return true; } catch (RuntimeException failure) { return false; }
    }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
