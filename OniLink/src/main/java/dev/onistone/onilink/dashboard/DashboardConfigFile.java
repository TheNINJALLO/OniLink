package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.config.ProxyConfig;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Safe dashboard access to config.properties with secret redaction, validation, backup, and rollback. */
final class DashboardConfigFile {
    static final String REDACTED = "<managed-outside-dashboard>";
    private static final long MAX_CONFIG_BYTES = 1_048_576;
    private static final String LINUX_PROFILE = "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae";
    private static final Pattern BACKEND_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:-]{1,253}");
    private static final SecureRandom RANDOM = new SecureRandom();
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

    synchronized Map<String, Object> addBackend(
            String expectedRevision,
            Map<String, String> fields
    ) throws IOException {
        String original = readConfig(path);
        requireRevision(original, expectedRevision);

        String name = required(fields, "name").toLowerCase(Locale.ROOT);
        if (!BACKEND_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Backend name must start with a letter and contain only lowercase letters, numbers, _ or -");
        }
        String host = required(fields, "host");
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Backend host must be a hostname or numeric IP address without spaces");
        }
        int port = integer(fields.get("port"), "Backend port", 1, 65_535);
        String trustedProxyCidr = cidr(required(fields, "trustedProxyCidr"));
        String bridgeId = optionalIdentifier(fields.get("bridgeId"), name + "-main", "Bridge ID");
        String keyId = optionalIdentifier(fields.get("activeKeyId"), "key-1", "Active key ID");

        Properties properties = new Properties();
        properties.load(new StringReader(original));
        LinkedHashSet<String> backends = new LinkedHashSet<>();
        String configuredBackends = properties.getProperty(
                "backends", properties.getProperty("backend.name", "default"));
        for (String configured : configuredBackends.split(",")) {
            if (!configured.isBlank()) backends.add(configured.trim());
        }
        if (backends.stream().anyMatch(configured -> configured.equalsIgnoreCase(name))) {
            throw new IllegalStateException("A backend named '" + name + "' already exists");
        }
        String prefix = "backend." + name + ".";
        if (properties.stringPropertyNames().stream().anyMatch(key -> key.startsWith(prefix))) {
            throw new IllegalStateException("Configuration already contains settings for backend '" + name + "'");
        }
        backends.add(name);

        String secretRelativePath = "secrets/" + name + ".key";
        String secretFileName = name + ".key";
        String line = System.lineSeparator();
        String candidate = replaceProperty(original, "backends", String.join(",", backends));
        if (!candidate.endsWith("\n") && !candidate.endsWith("\r")) candidate += line;
        String backendProperties = prefix + "host=" + host + line
                + prefix + "port=" + port + line
                + prefix + "forwarding.enabled=true" + line
                + prefix + "forwarding.bridgeId=" + bridgeId + line
                + prefix + "forwarding.activeKeyId=" + keyId + line
                + prefix + "forwarding.activeSecretEnv=" + line
                + prefix + "forwarding.activeSecretFile=" + secretRelativePath + line
                + prefix + "forwarding.tokenLifetimeMillis=5000" + line;
        candidate += line
                + "# Added by the OniLink dashboard backend wizard." + line
                + backendProperties;
        String onilinkProperties = "backends=" + String.join(",", backends) + line + line
                + backendProperties;

        Path temporary = path.resolveSibling(path.getFileName() + ".dashboard.backend.tmp");
        Files.writeString(temporary, candidate, StandardCharsets.UTF_8);
        try {
            ProxyConfig.loadOrCreate(temporary);
        } catch (RuntimeException | IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }

        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = Base64.getEncoder().encodeToString(secretBytes);
        java.util.Arrays.fill(secretBytes, (byte) 0);
        Path secretsDirectory = path.getParent().resolve("secrets");
        Path secretPath = secretsDirectory.resolve(secretFileName).normalize();
        if (!secretPath.startsWith(secretsDirectory) || Files.exists(secretPath)) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("Secret file already exists for backend '" + name + "'");
        }
        Path secretTemporary = secretsDirectory.resolve("." + secretFileName + ".setup.tmp");
        boolean secretInstalled = false;
        try {
            Files.createDirectories(secretsDirectory);
            setPosixPermissions(secretsDirectory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            Files.writeString(secretTemporary, secret + line, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setPosixPermissions(secretTemporary, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            moveNew(secretTemporary, secretPath);
            secretInstalled = true;
            replace(temporary, path);
        } catch (RuntimeException | IOException exception) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(secretTemporary);
            if (secretInstalled) Files.deleteIfExists(secretPath);
            throw exception;
        }

        Map<String, Object> result = new LinkedHashMap<>(read());
        result.put("added", true);
        result.put("backendName", name);
        result.put("secret", secret);
        result.put("secretFileName", secretFileName);
        result.put("onilinkSecretFile", secretRelativePath);
        result.put("onilinkProperties", onilinkProperties);
        result.put("onibridgeToml", onibridgeToml(
                name, bridgeId, keyId, trustedProxyCidr, secretFileName));
        result.put("restartRequired", true);
        result.put("message", "Backend added. Install the generated files on Endstone, then restart OniLink.");
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

    private static void requireRevision(String original, String expectedRevision) {
        if (expectedRevision == null || !MessageDigest.isEqual(
                revision(original).getBytes(StandardCharsets.US_ASCII),
                expectedRevision.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Configuration changed on disk; reload before adding a backend");
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(key + " contains an invalid character");
        }
        return value.trim();
    }

    private static int integer(String value, String label, int minimum, int maximum) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a number", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(label + " must be " + minimum + ".." + maximum);
        }
        return parsed;
    }

    private static String optionalIdentifier(String value, String fallback, String label) {
        String result = value == null || value.isBlank() ? fallback : value.trim();
        if (!IDENTIFIER.matcher(result).matches()) {
            throw new IllegalArgumentException(label + " may contain only letters, numbers, ., _ or -");
        }
        return result;
    }

    private static String cidr(String value) {
        int slash = value.lastIndexOf('/');
        if (slash < 1 || slash == value.length() - 1) {
            throw new IllegalArgumentException("Trusted proxy must be one IPv4 or IPv6 CIDR");
        }
        String address = value.substring(0, slash);
        if (!address.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("Trusted proxy CIDR must use a numeric IP address");
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            int prefix = integer(value.substring(slash + 1), "Trusted proxy prefix", 0,
                    parsed.getAddress().length * 8);
            return address + "/" + prefix;
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Trusted proxy CIDR contains an invalid IP address", exception);
        }
    }

    private static String replaceProperty(String content, String key, String value) {
        List<String> output = new ArrayList<>();
        boolean replaced = false;
        for (LineBlock block : blocks(content)) {
            ParsedLine parsed = block.parsed();
            if (parsed != null && parsed.key().equals(key)) {
                if (replaced) throw new IllegalStateException("Configuration contains duplicate '" + key + "' keys");
                output.add(key + "=" + value);
                replaced = true;
            } else {
                output.addAll(block.lines());
            }
        }
        if (!replaced) output.add(key + "=" + value);
        return String.join(System.lineSeparator(), output);
    }

    private static String onibridgeToml(
            String name,
            String bridgeId,
            String keyId,
            String trustedProxyCidr,
            String secretFileName
    ) {
        return """
                # Generated by the OniLink dashboard backend wizard.
                # Install as: /home/container/plugins/onibridge/onibridge.toml

                bridge_id = "%s"
                backend_name = "%s"
                trusted_proxy_cidrs = ["%s"]
                shutdown_on_hook_failure = true
                reject_direct_joins = true

                [forwarding]
                protocol = 2
                active_key_id = "%s"
                active_secret_env = ""
                active_secret_file = "%s"
                previous_key_id = ""
                previous_secret_env = ""
                previous_secret_file = ""
                maximum_token_size = 4096
                maximum_lifetime_ms = 10000
                allowed_clock_skew_ms = 2000
                replay_cache_max_entries = 10000

                [identity]
                uuid_mode = "preserve_backend"
                verify_post_login_xuid = true
                store_verified_identities = true

                [commands]
                register_native_commands = true
                command_namespace = "onibridge"
                interfere_with_backend_commands = false

                [compatibility]
                required_profile = "%s"
                allow_unreviewed_profile = false
                allow_unknown_bds = false
                allow_unknown_endstone = false

                [legacy_verification]
                enabled = false
                """.formatted(bridgeId, name, trustedProxyCidr, keyId, secretFileName, LINUX_PROFILE);
    }

    private static void setPosixPermissions(Path target, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(target, permissions);
        }
    }

    private static void moveNew(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private record ParsedLine(String key, String value) {
    }

    private record LineBlock(List<String> lines, ParsedLine parsed) {
    }
}
