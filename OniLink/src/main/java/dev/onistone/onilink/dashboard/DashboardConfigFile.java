package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.config.ProxyConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Safe dashboard access to config.properties with secret redaction, validation, backup, and rollback. */
final class DashboardConfigFile {
    static final String REDACTED = "<managed-outside-dashboard>";
    private static final long MAX_CONFIG_BYTES = 1_048_576;
    private final Path path;
    private final Path backupPath;

    DashboardConfigFile(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.backupPath = this.path.resolveSibling(this.path.getFileName() + ".dashboard.bak");
    }

    synchronized Map<String, Object> read() throws IOException {
        String original = readConfig(path);
        return Map.of(
                "path", path.toString(),
                "content", redact(original),
                "revision", revision(original),
                "backupAvailable", Files.isRegularFile(backupPath),
                "redactedPlaceholder", REDACTED
        );
    }

    synchronized Map<String, Object> save(String expectedRevision, String edited) throws IOException {
        if (edited == null || edited.isBlank()) throw new IllegalArgumentException("Configuration cannot be empty");
        if (edited.indexOf('\0') >= 0) throw new IllegalArgumentException("Configuration contains a NUL byte");
        String original = readConfig(path);
        if (expectedRevision == null || !MessageDigest.isEqual(
                revision(original).getBytes(StandardCharsets.US_ASCII),
                expectedRevision.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Configuration changed on disk; reload before saving");
        }
        String merged = mergeSecrets(original, edited);
        Path temporary = path.resolveSibling(path.getFileName() + ".dashboard.tmp");
        Files.writeString(temporary, merged, StandardCharsets.UTF_8);
        try {
            ProxyConfig.loadOrCreate(temporary);
            Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            replace(temporary, path);
        } catch (RuntimeException | IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        Map<String, Object> result = new LinkedHashMap<>(read());
        result.put("saved", true);
        result.put("restartRequired", true);
        result.put("message", "Configuration saved and validated. Restart OniLink to apply it.");
        return Map.copyOf(result);
    }

    synchronized Map<String, Object> rollback() throws IOException {
        if (!Files.isRegularFile(backupPath)) throw new IllegalStateException("No dashboard backup is available");
        Path validation = path.resolveSibling(path.getFileName() + ".dashboard.rollback.tmp");
        Files.copy(backupPath, validation, StandardCopyOption.REPLACE_EXISTING);
        try {
            ProxyConfig.loadOrCreate(validation);
            Files.copy(path, path.resolveSibling(path.getFileName() + ".dashboard.pre-rollback"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            replace(validation, path);
            Map<String, Object> result = new LinkedHashMap<>(read());
            result.put("rolledBack", true);
            result.put("restartRequired", true);
            result.put("message", "Dashboard backup restored. Restart OniLink to apply it.");
            return Map.copyOf(result);
        } catch (RuntimeException | IOException exception) {
            Files.deleteIfExists(validation);
            throw exception;
        }
    }

    Path path() {
        return path;
    }

    Path backupPath() {
        return backupPath;
    }

    private static String redact(String content) {
        List<String> output = new ArrayList<>();
        for (LineBlock block : blocks(content)) {
            ParsedLine parsed = block.parsed();
            if (parsed != null && sensitive(parsed.key())) {
                output.add(parsed.key() + "=" + REDACTED);
            } else {
                output.addAll(block.lines());
            }
        }
        return String.join(System.lineSeparator(), output);
    }

    private static String mergeSecrets(String original, String edited) {
        Map<String, List<String>> protectedLines = new LinkedHashMap<>();
        for (LineBlock block : blocks(original)) {
            ParsedLine parsed = block.parsed();
            if (parsed != null && sensitive(parsed.key())) {
                protectedLines.put(parsed.key(), block.lines());
            }
        }
        List<String> output = new ArrayList<>();
        Set<String> restoredKeys = new java.util.HashSet<>();
        for (LineBlock block : blocks(edited)) {
            ParsedLine parsed = block.parsed();
            if (parsed == null || !sensitive(parsed.key())) {
                output.addAll(block.lines());
                continue;
            }
            if (!REDACTED.equals(parsed.value()) || block.lines().size() != 1) {
                throw new IllegalArgumentException(
                        "Secrets, tokens, passwords, and webhooks cannot be changed in the browser");
            }
            List<String> protectedBlock = protectedLines.get(parsed.key());
            if (protectedBlock == null) {
                throw new IllegalArgumentException("Unknown protected setting: " + parsed.key());
            }
            output.addAll(protectedBlock);
            restoredKeys.add(parsed.key());
        }
        for (String protectedKey : protectedLines.keySet()) {
            if (!restoredKeys.contains(protectedKey)) {
                throw new IllegalArgumentException("Protected setting cannot be removed: " + protectedKey);
            }
        }
        return String.join(System.lineSeparator(), output);
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("tokenlifetimemillis") || normalized.endsWith("maximumtokensize")) {
            return false;
        }
        return normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("webhook")
                || normalized.contains("privatekey");
    }

    private static ParsedLine parse(String line) {
        int start = 0;
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) start++;
        if (start == line.length() || line.charAt(start) == '#' || line.charAt(start) == '!') return null;
        int separator = -1;
        boolean escaped = false;
        for (int index = start; index < line.length(); index++) {
            char character = line.charAt(index);
            if (!escaped && (character == '=' || character == ':' || Character.isWhitespace(character))) {
                separator = index;
                break;
            }
            if (character == '\\') escaped = !escaped;
            else escaped = false;
        }
        String key = (separator < 0 ? line.substring(start) : line.substring(start, separator)).trim();
        if (key.isEmpty()) return null;
        if (separator < 0) return new ParsedLine(key, "");
        int valueStart = separator;
        while (valueStart < line.length() && Character.isWhitespace(line.charAt(valueStart))) valueStart++;
        if (valueStart < line.length() && (line.charAt(valueStart) == '=' || line.charAt(valueStart) == ':')) {
            valueStart++;
        }
        while (valueStart < line.length() && Character.isWhitespace(line.charAt(valueStart))) valueStart++;
        return new ParsedLine(key, line.substring(valueStart).trim());
    }

    private static List<LineBlock> blocks(String content) {
        String[] lines = content.split("\\R", -1);
        List<LineBlock> blocks = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            List<String> blockLines = new ArrayList<>();
            String first = lines[index];
            blockLines.add(first);
            while (continues(lines[index]) && index + 1 < lines.length) {
                blockLines.add(lines[++index]);
            }
            blocks.add(new LineBlock(List.copyOf(blockLines), parse(first)));
        }
        return blocks;
    }

    private static boolean continues(String line) {
        int backslashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static String revision(String content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String readConfig(Path source) throws IOException {
        if (Files.size(source) > MAX_CONFIG_BYTES) {
            throw new IOException("Configuration exceeds the 1 MiB dashboard safety limit");
        }
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ParsedLine(String key, String value) {
    }

    private record LineBlock(List<String> lines, ParsedLine parsed) {
    }
}
