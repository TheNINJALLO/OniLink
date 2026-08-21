package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.config.ProxyConfig;

import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        BackendEndpoint endpoint = backendEndpoint(fields);
        String host = endpoint.host();
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Backend host must be a hostname or numeric IP address without spaces");
        }
        int port = endpoint.port();
        String trustedProxyCidr = trustedProxyCidr(fields);
        String bridgeId = optionalIdentifier(fields.get("bridgeId"), name + "-main", "Bridge ID");
        String keyId = optionalIdentifier(fields.get("activeKeyId"), "key-1", "Active key ID");
        boolean controlEnabled = optionalBoolean(fields.get("controlEnabled"), false, "Enable OniControl");
        String controlHost = "";
        int controlPort = 19_132;
        String controlMode = "advisor";
        if (controlEnabled) {
            controlHost = safeValue(fields.getOrDefault("controlHost", host), "OniControl host");
            if (!HOST.matcher(controlHost).matches() || !privateLiteral(controlHost)) {
                throw new IllegalArgumentException(
                        "OniControl host must be a loopback or private numeric IP; use a private network or tunnel");
            }
            controlPort = integer(fields.get("controlPort"), "OniControl TCP port", 1, 65_535);
            controlMode = safeValue(fields.getOrDefault("controlMode", "advisor"), "OniControl mode")
                    .toLowerCase(Locale.ROOT);
            if (!controlMode.equals("advisor") && !controlMode.equals("enforce")) {
                throw new IllegalArgumentException("OniControl mode must be advisor or enforce");
            }
        }

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
        String controlSecretRelativePath = "secrets/" + name + ".control.key";
        String controlSecretFileName = name + ".control.key";
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
        if (controlEnabled) {
            backendProperties += prefix + "control.enabled=true" + line
                    + prefix + "control.mode=" + controlMode + line
                    + prefix + "control.connectHost=" + controlHost + line
                    + prefix + "control.connectPort=" + controlPort + line
                    + prefix + "control.bridgeId=" + bridgeId + line
                    + prefix + "control.backendName=" + name + line
                    + prefix + "control.keyId=control-key-1" + line
                    + prefix + "control.secretEnvironment=" + line
                    + prefix + "control.secretFile=" + controlSecretRelativePath + line
                    + prefix + "control.allowInsecurePrivateNetwork="
                    + !isLoopback(controlHost) + line
                    + prefix + "control.allowPublicAddress=false" + line;
        }
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
        String controlSecret = "";
        if (controlEnabled) {
            RANDOM.nextBytes(secretBytes);
            controlSecret = Base64.getEncoder().encodeToString(secretBytes);
            java.util.Arrays.fill(secretBytes, (byte) 0);
        }
        String bridgeToml = onibridgeToml(name, bridgeId, keyId, trustedProxyCidr, secretFileName,
                controlEnabled, controlHost, controlPort, controlSecretFileName);
        String setupBundleFileName = name + "-onibridge-setup.zip";
        byte[] setupBundle = setupBundle(
                name, secretFileName, secret, controlSecretFileName, controlSecret, bridgeToml,
                host, port, trustedProxyCidr, controlEnabled, controlHost, controlPort);
        Path secretsDirectory = path.getParent().resolve("secrets");
        Path secretPath = secretsDirectory.resolve(secretFileName).normalize();
        if (!secretPath.startsWith(secretsDirectory) || Files.exists(secretPath)) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("Secret file already exists for backend '" + name + "'");
        }
        Path secretTemporary = secretsDirectory.resolve("." + secretFileName + ".setup.tmp");
        Path controlSecretPath = secretsDirectory.resolve(controlSecretFileName).normalize();
        Path controlSecretTemporary = secretsDirectory.resolve("." + controlSecretFileName + ".setup.tmp");
        if (controlEnabled && (!controlSecretPath.startsWith(secretsDirectory) || Files.exists(controlSecretPath))) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("Control secret file already exists for backend '" + name + "'");
        }
        boolean secretInstalled = false;
        boolean controlSecretInstalled = false;
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
            if (controlEnabled) {
                Files.writeString(controlSecretTemporary, controlSecret + line, StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                setPosixPermissions(controlSecretTemporary, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            }
            Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            moveNew(secretTemporary, secretPath);
            secretInstalled = true;
            if (controlEnabled) {
                moveNew(controlSecretTemporary, controlSecretPath);
                controlSecretInstalled = true;
            }
            replace(temporary, path);
        } catch (RuntimeException | IOException exception) {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(secretTemporary);
            Files.deleteIfExists(controlSecretTemporary);
            if (secretInstalled) Files.deleteIfExists(secretPath);
            if (controlSecretInstalled) Files.deleteIfExists(controlSecretPath);
            throw exception;
        }

        Map<String, Object> result = new LinkedHashMap<>(read());
        result.put("added", true);
        result.put("backendName", name);
        result.put("secret", secret);
        result.put("controlEnabled", controlEnabled);
        result.put("controlSecret", controlSecret);
        result.put("controlSecretFileName", controlEnabled ? controlSecretFileName : "");
        result.put("onilinkControlSecretFile", controlEnabled ? controlSecretRelativePath : "");
        result.put("controlEndpoint", controlEnabled ? displayEndpoint(controlHost, controlPort) : "disabled");
        result.put("secretFileName", secretFileName);
        result.put("onilinkSecretFile", secretRelativePath);
        result.put("onilinkProperties", onilinkProperties);
        result.put("onibridgeToml", bridgeToml);
        result.put("backendEndpoint", displayEndpoint(host, port));
        result.put("trustedProxyCidr", trustedProxyCidr);
        result.put("setupBundleFileName", setupBundleFileName);
        result.put("setupBundleBase64", Base64.getEncoder().encodeToString(setupBundle));
        result.put("restartRequired", true);
        result.put("message", "Backend added. Install the generated files on Endstone, then restart OniLink.");
        return Map.copyOf(result);
    }

    synchronized Map<String, Object> routing() throws IOException {
        ProxyConfig config = ProxyConfig.loadOrCreate(path);
        List<Map<String, Object>> configuredBackends = new ArrayList<>();
        for (var backend : config.backends().values()) {
            configuredBackends.add(Map.of(
                    "name", backend.name(),
                    "address", displayEndpoint(
                            backend.address().getHostString(), backend.address().getPort())));
        }
        return Map.of(
                "primaryBackend", config.backend().name(),
                "primaryBackendAddress", displayEndpoint(
                        config.backend().address().getHostString(), config.backend().address().getPort()),
                "configuredBackends", List.copyOf(configuredBackends));
    }

    synchronized Map<String, Object> setPrimaryBackend(
            String expectedRevision,
            String requestedBackend
    ) throws IOException {
        String original = readConfig(path);
        requireRevision(original, expectedRevision);

        String requested = safeValue(requestedBackend, "Primary backend").toLowerCase(Locale.ROOT);
        if (!BACKEND_NAME.matcher(requested).matches()) {
            throw new IllegalArgumentException("Primary backend name is invalid");
        }

        ProxyConfig current = ProxyConfig.loadOrCreate(path);
        var selected = current.backends().values().stream()
                .filter(backend -> backend.name().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Backend '" + requested + "' is not configured for this proxy"));
        String selectedName = selected.name();
        String selectedAddress = displayEndpoint(
                selected.address().getHostString(), selected.address().getPort());
        if (current.backend().name().equalsIgnoreCase(selectedName)) {
            Map<String, Object> result = new LinkedHashMap<>(read());
            result.put("changed", false);
            result.put("primaryBackend", selectedName);
            result.put("primaryBackendAddress", selectedAddress);
            result.put("restartRequired", false);
            result.put("message", selectedName + " is already the primary server.");
            return Map.copyOf(result);
        }

        String candidate = replaceProperty(original, "backend.name", selectedName);
        candidate = replaceProperty(candidate, "backend.host", selected.address().getHostString());
        candidate = replaceProperty(candidate, "backend.port", Integer.toString(selected.address().getPort()));
        Path temporary = path.resolveSibling(path.getFileName() + ".dashboard.primary.tmp");
        Files.writeString(temporary, candidate, StandardCharsets.UTF_8);
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
        result.put("changed", true);
        result.put("primaryBackend", selectedName);
        result.put("primaryBackendAddress", selectedAddress);
        result.put("restartRequired", true);
        result.put("message", "Primary server changed to " + selectedName + ".");
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
            throw new IllegalStateException("Configuration changed on disk; reload before saving");
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

    private static BackendEndpoint backendEndpoint(Map<String, String> fields) {
        String combined = fields.get("address");
        if (combined == null || combined.isBlank()) {
            return new BackendEndpoint(
                    required(fields, "host"),
                    integer(fields.get("port"), "Backend port", 1, 65_535));
        }
        String value = safeValue(combined, "BDS address");
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing < 2 || closing + 2 >= value.length() || value.charAt(closing + 1) != ':') {
                throw new IllegalArgumentException("IPv6 BDS addresses must use [address]:port");
            }
            return new BackendEndpoint(
                    value.substring(1, closing),
                    integer(value.substring(closing + 2), "BDS port", 1, 65_535));
        }
        int colon = value.lastIndexOf(':');
        if (colon < 1 || colon == value.length() - 1) {
            throw new IllegalArgumentException("BDS address must include a port, for example 198.51.100.20:25571");
        }
        if (value.indexOf(':') != colon) {
            throw new IllegalArgumentException("IPv6 BDS addresses must use [address]:port");
        }
        return new BackendEndpoint(
                value.substring(0, colon),
                integer(value.substring(colon + 1), "BDS port", 1, 65_535));
    }

    private static String trustedProxyCidr(Map<String, String> fields) {
        String configured = fields.get("trustedProxyCidr");
        if (configured != null && !configured.isBlank()) return cidr(safeValue(configured, "Trusted proxy CIDR"));
        String publicIp = safeValue(fields.get("proxyPublicIp"), "OniLink public IP");
        if (!publicIp.matches("[0-9A-Fa-f:.]+") || (!publicIp.contains(".") && !publicIp.contains(":"))) {
            throw new IllegalArgumentException("OniLink public IP must be a numeric IPv4 or IPv6 address");
        }
        try {
            InetAddress parsed = InetAddress.getByName(publicIp);
            return parsed.getHostAddress() + "/" + (parsed.getAddress().length * 8);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("OniLink public IP is invalid", exception);
        }
    }

    private static String safeValue(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " contains an invalid character");
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
            String secretFileName,
            boolean controlEnabled,
            String controlHost,
            int controlPort,
            String controlSecretFileName
    ) {
        String base = """
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
        if (!controlEnabled) {
            return base + """

                    # OniControl remains disabled until a separate private TCP route and key are configured.
                    [control]
                    enabled = false
                    """;
        }
        return base + """

                # Dedicated authenticated semantic-control channel. This key is intentionally different
                # from the OniForward identity key above.
                [control]
                enabled = true
                listen_host = "%s"
                listen_port = %d
                bridge_id = "%s"
                backend_name = "%s"
                key_id = "control-key-1"
                secret_environment = ""
                secret_file = "%s"
                trusted_proxy_cidrs = ["%s"]
                max_frame_bytes = 262144
                max_connections = 4
                max_in_flight = 32
                clock_skew_seconds = 30
                replay_retention_seconds = 120
                allow_insecure_private_network = %s
                allow_public_address = false
                [control.tls]
                enabled = false
                certificate_file = ""
                private_key_file = ""
                client_ca_file = ""
                require_client_certificate = true
                """.formatted(controlHost, controlPort, bridgeId, name, controlSecretFileName,
                trustedProxyCidr, !isLoopback(controlHost));
    }

    private static byte[] setupBundle(
            String name,
            String secretFileName,
            String secret,
            String controlSecretFileName,
            String controlSecret,
            String bridgeToml,
            String host,
            int port,
            String trustedProxyCidr,
            boolean controlEnabled,
            String controlHost,
            int controlPort
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zipText(zip, secretFileName, secret + System.lineSeparator());
            if (controlEnabled) {
                zipText(zip, controlSecretFileName, controlSecret + System.lineSeparator());
            }
            zipText(zip, "onibridge.toml", bridgeToml);
            zipText(zip, "INSTALL.txt", """
                    ONILINK BACKEND SETUP: %s

                    PORTS
                    - OniLink needs one primary allocation. Bedrock uses UDP and the dashboard uses TCP
                      on that same numeric port.
                    - This backend uses its existing BDS UDP allocation: %s
                    - OniBridge does not need another port.
                    %s

                    INSTALL
                    1. Stop this BDS/Endstone server in Pterodactyl.
                    2. Install the matching OniBridge Linux .so from the same OniLink release in:
                       /home/container/plugins/
                    3. Create this directory if it does not exist:
                       /home/container/plugins/onibridge/
                    4. Upload %s%s and onibridge.toml from this ZIP into that directory.
                    5. Start BDS and confirm the native identity hook reports active.
                    6. Restart OniLink so it loads the new route.
                    7. Join through OniLink and run: /server %s

                    TRUST
                    BDS address: %s
                    Trusted OniLink source: %s

                    Keep both keys private. Do not paste their contents into an *_env setting; the generated
                    TOML intentionally loads each key from an owner-only file beside onibridge.toml.
                    """.formatted(name, displayEndpoint(host, port),
                            controlEnabled
                                    ? "- OniControl additionally uses private TCP "
                                            + displayEndpoint(controlHost, controlPort) + "."
                                    : "- OniControl is disabled; no control TCP allocation is needed.",
                            secretFileName, controlEnabled ? " and " + controlSecretFileName : "", name,
                            displayEndpoint(host, port), trustedProxyCidr));
        }
        return bytes.toByteArray();
    }

    private static void zipText(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String displayEndpoint(String host, int port) {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    private static boolean optionalBoolean(String value, boolean fallback, String label) {
        if (value == null || value.isBlank()) return fallback;
        if ("true".equalsIgnoreCase(value.trim())) return true;
        if ("false".equalsIgnoreCase(value.trim())) return false;
        throw new IllegalArgumentException(label + " must be true or false");
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private static boolean privateLiteral(String host) {
        if (isLoopback(host)) return true;
        if (!host.matches("[0-9A-Fa-f:.]+")) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
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

    private record BackendEndpoint(String host, int port) {
    }
}
