package dev.onistone.onilink.dashboard;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only administrative audit trail with bounded tail reads. */
final class DashboardAuditLog {
    private static final int MAX_TAIL_BYTES = 1_048_576;
    private final Path path;

    DashboardAuditLog(Path dataDirectory) throws IOException {
        this.path = dataDirectory.resolve("audit.jsonl");
        Files.createDirectories(dataDirectory);
        if (Files.notExists(path)) Files.createFile(path);
        ownerOnly(path);
    }

    Path path() {
        return path;
    }

    synchronized void record(
            DashboardAccounts.Principal actor,
            String remoteAddress,
            String action,
            String result,
            Object details
    ) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("actor", actor == null ? "anonymous" : actor.username());
        event.put("role", actor == null ? "none" : actor.role().wireName());
        event.put("remoteAddress", remoteAddress == null ? "unknown" : remoteAddress);
        event.put("action", action);
        event.put("result", result);
        event.put("details", details == null ? Map.of() : details);
        try {
            Files.writeString(path, DashboardJson.encode(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.printf("Unable to append OniLink dashboard audit event: %s%n", exception.getMessage());
        }
    }

    synchronized List<String> recent(int limit) throws IOException {
        return tail(path, Math.max(1, Math.min(limit, 1_000)), MAX_TAIL_BYTES);
    }

    static List<String> tail(Path file, int lineLimit, int byteLimit) throws IOException {
        if (file == null || Files.notExists(file)) return List.of();
        int boundedLines = Math.max(1, lineLimit);
        int boundedBytes = Math.max(1, byteLimit);
        byte[] bytes;
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            long length = input.length();
            int readLength = (int) Math.min(length, boundedBytes);
            bytes = new byte[readLength];
            input.seek(length - readLength);
            input.readFully(bytes);
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] split = content.split("\\R");
        int start = Math.max(0, split.length - boundedLines);
        List<String> result = new ArrayList<>(split.length - start);
        for (int index = start; index < split.length; index++) {
            if (!split[index].isBlank()) result.add(split[index]);
        }
        return List.copyOf(result);
    }

    private static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows inherits ACLs from the containing server directory.
        }
    }
}
