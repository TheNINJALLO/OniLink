package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, content-addressed uploads. Inspecting an archive never runs its contents. */
public final class ArtifactStore extends ScopedRecords {
    private final Path root;
    private final long maximumBytes;
    private final long quotaBytes;
    public ArtifactStore(PlatformDatabase db, Path data, long maximumBytes, long quotaBytes) throws IOException {
        super(db);
        if (maximumBytes < 1024 || quotaBytes < maximumBytes) throw new IllegalArgumentException("invalid artifact limits");
        this.root = data.resolve("platform/artifacts").toAbsolutePath().normalize();
        this.maximumBytes = maximumBytes;
        this.quotaBytes = quotaBytes;
        Files.createDirectories(root);
    }
    public List<Map<String, Object>> list(PlatformDatabase.Scope scope) { return views(database.list(scope, "artifact", 1000)); }
    public synchronized Map<String, Object> upload(PlatformDatabase.Scope scope, String kind, String version,
                                                 String platform, InputStream input) throws IOException {
        if (!Set.of("server", "pack", "protocol", "fixtures").contains(kind)) throw new IllegalArgumentException("invalid artifact kind");
        if (version == null || !version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:-[a-zA-Z0-9.-]+)?"))
            throw new IllegalArgumentException("a release version is required");
        if (!Set.of("linux", "windows", "any").contains(platform) || (kind.equals("server") && platform.equals("any")))
            throw new IllegalArgumentException("invalid artifact platform");
        var existing = database.list(scope, "artifact", 1000);
        if (existing.size() >= 1000) throw new IllegalStateException("artifact count limit reached");
        long used = existing.stream().mapToLong(r -> longValue(r.value().get("bytes"), 0)).sum();
        Path directory = directory(scope);
        Path temporary = Files.createTempFile(directory, "upload-", ".tmp");
        try {
            MessageDigest hash = digest();
            long bytes = 0;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1;) {
                    bytes += count;
                    if (bytes > maximumBytes || used + bytes > quotaBytes) throw new IllegalArgumentException("artifact storage limit exceeded");
                    hash.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            String sha = HexFormat.of().formatHex(hash.digest());
            var report = inspect(temporary, kind, platform);
            var previous = database.get(scope, "artifact", sha);
            if (previous.isPresent()) {
                if (!kind.equals(previous.get().value().get("kind")) || !version.equals(previous.get().value().get("version"))
                        || !platform.equals(previous.get().value().get("platform")))
                    throw new IllegalArgumentException("these bytes were already registered with different release metadata");
                return view(previous.get());
            }
            Path target = directory.resolve(sha + ".zip");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> value = new LinkedHashMap<>(report);
            value.putAll(Map.of("kind", kind, "version", version, "platform", platform, "sha256", sha,
                    "bytes", bytes, "state", "STAGED", "versionSource", "operator-declared"));
            if (kind.equals("server")) value.put("nativeRuntime", "Endstone");
            return view(database.put(scope, "artifact", sha, 0L, value));
        } finally { Files.deleteIfExists(temporary); }
    }
    public Path path(PlatformDatabase.Scope scope, String artifactId) throws IOException {
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid artifact ID");
        var record = database.get(scope, "artifact", artifactId).orElseThrow(() -> new IllegalArgumentException("unknown artifact"));
        Path path = directory(scope).resolve(artifactId + ".zip");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !sha256(path).equals(record.value().get("sha256")))
            throw new IllegalStateException("artifact integrity check failed");
        return path;
    }
    public Map<String, Object> get(PlatformDatabase.Scope scope, String artifactId) {
        return view(database.get(scope, "artifact", artifactId).orElseThrow(() -> new IllegalArgumentException("unknown artifact")));
    }
    private Path directory(PlatformDatabase.Scope scope) throws IOException {
        String key = HexFormat.of().formatHex(digest().digest((scope.tenantId() + "\0" + scope.proxyId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path directory = root.resolve(key);
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(root.toRealPath())) throw new IOException("artifact directory escapes its root");
        return directory;
    }
    public static Map<String, Object> inspect(Path archive, String kind, String platform) throws IOException {
        NavigableSet<String> names = new TreeSet<>();
        Set<String> files = new HashSet<>();
        List<Map<String, Object>> executables = new ArrayList<>();
        long expanded = 0;
        int count = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = safeEntry(entry.getName());
                String canonical = name.toLowerCase(Locale.ROOT);
                if (++count > 50000 || !names.add(canonical)) throw new IOException("duplicate ZIP entry or entry limit exceeded");
                for (int slash = canonical.indexOf('/'); slash >= 0; slash = canonical.indexOf('/', slash + 1))
                    if (files.contains(canonical.substring(0, slash))) throw new IOException("ZIP file conflicts with a parent directory");
                String child = names.ceiling(canonical + '/');
                if (!entry.isDirectory() && child != null && child.startsWith(canonical + '/')) throw new IOException("ZIP file conflicts with a child path");
                if (entry.isDirectory()) continue;
                files.add(canonical);
                CRC32 crc = new CRC32();
                MessageDigest hash = digest();
                long size = 0;
                byte[] header = new byte[4096];
                int headerSize = 0;
                try (InputStream stream = zip.getInputStream(entry)) {
                    for (int read; (read = stream.read(buffer)) != -1;) {
                        size += read;
                        expanded += read;
                        if (expanded > 4L * 1024 * 1024 * 1024) throw new IOException("expanded ZIP limit exceeded");
                        int copy = Math.min(read, header.length - headerSize);
                        System.arraycopy(buffer, 0, header, headerSize, copy);
                        headerSize += copy;
                        crc.update(buffer, 0, read);
                        hash.update(buffer, 0, read);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("ZIP size or CRC mismatch");
                if (name.equals("bedrock_server") || name.equals("bedrock_server.exe")) {
                    String architecture = architecture(header, headerSize);
                    executables.add(Map.of("name", name, "sha256", HexFormat.of().formatHex(hash.digest()), "bytes", size, "architecture", architecture));
                }
            }
        }
        if (count == 0) throw new IOException("empty archive");
        if (kind.equals("server")) {
            String expected = platform.equals("windows") ? "bedrock_server.exe" : "bedrock_server";
            String architecture = platform.equals("windows") ? "pe-amd64" : "elf-amd64";
            if (executables.size() != 1 || !expected.equals(executables.getFirst().get("name"))
                    || !architecture.equals(executables.getFirst().get("architecture")))
                throw new IOException("archive must contain the platform's amd64 Bedrock executable at its root");
        }
        return Map.of("archiveValidation", "PASS", "entries", count, "expandedBytes", expanded, "executables", executables);
    }
    public static String safeEntry(String value) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.startsWith("/") || value.indexOf(':') >= 0)
            throw new IOException("unsafe archive path");
        String name = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        for (String part : name.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?")
                    || part.chars().anyMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0)) throw new IOException("unsafe archive path");
        }
        return name;
    }
    private static String architecture(byte[] h, int size) {
        if (size >= 20 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F'
                && h[4] == 2 && h[5] == 1 && h[18] == 62 && h[19] == 0) return "elf-amd64";
        if (size >= 64 && h[0] == 'M' && h[1] == 'Z') {
            int offset = java.nio.ByteBuffer.wrap(h, 60, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            if (offset >= 64 && offset <= size - 6 && h[offset] == 'P' && h[offset + 1] == 'E'
                    && h[offset + 2] == 0 && h[offset + 3] == 0 && (h[offset + 4] & 255) == 0x64 && (h[offset + 5] & 255) == 0x86)
                return "pe-amd64";
        }
        return "unknown";
    }
    public static String sha256(Path path) throws IOException {
        MessageDigest hash = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
