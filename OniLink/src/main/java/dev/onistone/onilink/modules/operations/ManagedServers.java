package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;

/** Operator-configured process-manager commands; HTTP requests cannot supply commands or paths. */
public final class ManagedServers {
    public record Server(String backend, PlatformDatabase.Scope scope, Path directory, List<String> start,
                         List<String> stop, List<String> health, String endstoneVersion, boolean eulaAccepted) { }
    private final Map<String, Server> servers;
    private final Path snapshots;
    public ManagedServers(Path configuration, Path dataDirectory) throws IOException {
        snapshots = dataDirectory.resolve("platform/server-snapshots").toAbsolutePath().normalize();
        Files.createDirectories(snapshots);
        Map<String, Server> configured = new LinkedHashMap<>();
        if (configuration != null) {
            var config = ControlJson.parseObject(Files.readString(configuration), 262144);
            if (!(config.get("servers") instanceof List<?> list) || list.size() > 100) throw new IOException("expected at most 100 managed servers");
            for (Object raw : list) {
                var item = (Map<?, ?>) raw;
                if (!"Endstone".equals(item.get("runtime"))) throw new IOException("managed native servers require Endstone");
                String backend = String.valueOf(item.get("backend"));
                if (!backend.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IOException("invalid managed backend name");
                var scope = PlatformDatabase.Scope.of(String.valueOf(item.get("tenant")), String.valueOf(item.get("proxy")));
                Path directory = Path.of(String.valueOf(item.get("directory"))).toAbsolutePath().normalize();
                if (directory.getNameCount() < 3 || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("managed server directory must already exist");
                directory = directory.toRealPath();
                if (directory.startsWith(snapshots) || snapshots.startsWith(directory)) throw new IOException("server and snapshot directories must be separate");
                for (Server previous : configured.values()) if (directory.startsWith(previous.directory()) || previous.directory().startsWith(directory))
                    throw new IOException("managed server directories overlap");
                Server server = new Server(backend, scope, directory, command(item.get("startCommand")), command(item.get("stopCommand")),
                        command(item.get("healthCommand")), String.valueOf(item.get("endstoneVersion")), Boolean.TRUE.equals(item.get("eulaAccepted")));
                if (server.endstoneVersion().isBlank() || server.endstoneVersion().equals("null")) throw new IOException("Endstone version must be pinned");
                if (configured.put(key(scope, backend), server) != null) throw new IOException("duplicate managed backend");
            }
        }
        servers = Map.copyOf(configured);
    }
    public Server server(PlatformDatabase.Scope scope, String backend) {
        var server = servers.get(key(scope, backend));
        if (server == null) throw new IllegalArgumentException("backend has no configured server-management connection");
        return server;
    }
    public List<Map<String, Object>> list(PlatformDatabase.Scope scope) {
        return servers.values().stream().filter(s -> s.scope().equals(scope)).map(s -> Map.<String, Object>of("backend", s.backend(),
                "runtime", "Endstone", "endstoneVersion", s.endstoneVersion(), "eulaAccepted", s.eulaAccepted())).toList();
    }
    public void stop(Server server) throws Exception { run(server, server.stop(), Duration.ofSeconds(60), true); }
    public void start(Server server) throws Exception { run(server, server.start(), Duration.ofSeconds(60), true); }
    public boolean healthy(Server server) throws Exception { return run(server, server.health(), Duration.ofSeconds(10), false); }
    public String backup(Server server, String operation) throws IOException {
        Path destination = snapshot(operation);
        if (Files.exists(destination)) throw new IOException("snapshot already exists; recovery review is required");
        Files.createDirectory(destination);
        copyTree(server.directory(), destination);
        // A completed manifest is required before restoration; interrupted copies cannot be used.
        Map<String, String> hashes = hashes(destination);
        Files.writeString(snapshots.resolve(operation + ".json"), ControlJson.encode(Map.of("backend", key(server.scope(), server.backend()), "files", hashes)), StandardOpenOption.CREATE_NEW);
        return operation;
    }
    public void install(Server server, Path archive) throws IOException {
        ArtifactStore.inspect(archive, "server", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "windows" : "linux");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = ArtifactStore.safeEntry(entry.getName());
                String first = name.split("/", 2)[0].toLowerCase(Locale.ROOT);
                // New server defaults must never replace worlds, credentials, plugins or operator configuration.
                if (Set.of("worlds", "plugins", "config", "logs", "permissions.json", "allowlist.json", "server.properties", "valid_known_packs.json").contains(first)) continue;
                Path target = child(server.directory(), name);
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry)) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
                if (name.equals("bedrock_server")) target.toFile().setExecutable(true, true);
            }
        }
    }
    public void restore(Server server, String operation) throws IOException {
        Path source = snapshot(operation);
        Path manifest = snapshots.resolve(operation + ".json");
        if (!Files.isRegularFile(manifest)) throw new IOException("snapshot did not complete");
        var expected = ControlJson.parseObject(Files.readString(manifest), 16_777_216);
        if (!key(server.scope(), server.backend()).equals(expected.get("backend")) || !hashes(source).equals(expected.get("files")))
            throw new IOException("snapshot identity or integrity check failed");
        // Validate every existing path before removing only files within the configured server root.
        var existing = files(server.directory());
        for (Path path : existing.reversed()) Files.delete(path);
        copyTree(source, server.directory());
    }
    private Path snapshot(String operation) {
        if (operation == null || !operation.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("invalid snapshot ID");
        return snapshots.resolve(operation);
    }
    private static boolean run(Server server, List<String> command, Duration timeout, boolean required) throws Exception {
        var process = new ProcessBuilder(command).directory(server.directory().toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroy();
            throw new IOException("server manager command timed out; process state requires review");
        }
        if (required && process.exitValue() != 0) throw new IOException("server manager command failed with exit " + process.exitValue());
        return process.exitValue() == 0;
    }
    private static List<String> command(Object raw) throws IOException {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 64 || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || s.length() > 4096))
            throw new IOException("manager commands must be nonempty argument arrays");
        return list.stream().map(String.class::cast).toList();
    }
    private static String key(PlatformDatabase.Scope scope, String backend) { return scope.tenantId() + "/" + scope.proxyId() + "/" + backend; }
    private static Path child(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IOException("server path escapes its root");
        for (Path path = target; path != null && path.startsWith(root); path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IOException("managed server paths may not contain symlinks");
        return target;
    }
    private static List<Path> files(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            var files = stream.filter(p -> !p.equals(root)).sorted(Comparator.comparingInt(Path::getNameCount)).toList();
            long bytes = 0;
            if (files.size() > 200000) throw new IOException("snapshot file limit exceeded");
            for (Path path : files) {
                child(root, root.relativize(path).toString());
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsupported server file type");
                if (Files.isRegularFile(path)) bytes += Files.size(path);
            }
            if (bytes > 100L * 1024 * 1024 * 1024) throw new IOException("snapshot size exceeds 100 GiB");
            return files;
        }
    }
    private static void copyTree(Path source, Path target) throws IOException {
        for (Path path : files(source)) {
            Path output = child(target, source.relativize(path).toString());
            if (Files.isDirectory(path)) Files.createDirectories(output);
            else { Files.createDirectories(output.getParent()); Files.copy(path, output, StandardCopyOption.COPY_ATTRIBUTES); }
        }
    }
    private static Map<String, String> hashes(Path directory) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (Path path : files(directory)) if (Files.isRegularFile(path)) result.put(directory.relativize(path).toString().replace('\\', '/'), ArtifactStore.sha256(path));
        return result;
    }
}
