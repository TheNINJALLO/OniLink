package dev.onistone.onilink.dashboard;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent dashboard accounts and process-local expiring browser sessions. */
final class DashboardAccounts {
    static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_KEY_BITS = 256;
    private static final int PASSWORD_SALT_BYTES = 24;
    private static final int TOKEN_BYTES = 32;
    private static final int TOTP_SECRET_BYTES = 20;
    private static final int STORAGE_VERSION = 1;
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    enum Role {
        TENANT(-1), VIEWER(0), OPERATOR(1), ADMIN(2), OWNER(3);

        private final int rank;

        Role(int rank) {
            this.rank = rank;
        }

        boolean allows(Role required) {
            return this != TENANT && rank >= required.rank;
        }

        static Role parse(String value) {
            if (value == null) throw new IllegalArgumentException("role is required");
            try {
                return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("role must be tenant, viewer, operator, admin, or owner");
            }
        }

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Principal(String username, Role role, String tenantId) {
        Principal {
            tenantId = tenantId == null ? "" : tenantId;
        }

        boolean tenantScoped() {
            return role == Role.TENANT;
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("username", username);
            result.put("role", role.wireName());
            result.put("tenantId", tenantId);
            return result;
        }
    }

    record BrowserSession(String token, long expiresAt, Principal principal) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("expiresAt", expiresAt);
            result.put("username", principal.username());
            result.put("role", principal.role().wireName());
            result.put("tenantId", principal.tenantId());
            return result;
        }
    }

    record LoginResult(boolean success, boolean totpRequired, BrowserSession session, String error) {
    }

    record UserView(String username, Role role, String tenantId, boolean enabled, boolean totpEnabled) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("username", username);
            result.put("role", role.wireName());
            result.put("tenantId", tenantId);
            result.put("enabled", enabled);
            result.put("totpEnabled", totpEnabled);
            return result;
        }
    }

    record TotpEnrollment(String secret, String uri) {
        Map<String, Object> asMap() {
            return Map.of("secret", secret, "uri", uri);
        }
    }

    private record User(
            String username,
            Role role,
            String tenantId,
            boolean enabled,
            byte[] salt,
            byte[] passwordHash,
            int iterations,
            String totpSecret
    ) {
        UserView view() {
            return new UserView(username, role, tenantId, enabled, totpSecret != null && !totpSecret.isBlank());
        }
    }

    private record StoredSession(String username, long expiresAt) {
    }

    private final Path accountsPath;
    private final Path setupPath;
    private final Duration sessionLifetime;
    private final SecureRandom random = new SecureRandom();
    private final byte[] dummyPasswordSalt = new byte[PASSWORD_SALT_BYTES];
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, User> users = new LinkedHashMap<>();
    private String setupCode = "";

    DashboardAccounts(Path dataDirectory, int sessionMinutes, String publicAddress) throws IOException {
        this.accountsPath = dataDirectory.resolve("accounts.properties");
        this.setupPath = dataDirectory.resolve("FIRST_RUN_SETUP.txt");
        this.sessionLifetime = Duration.ofMinutes(sessionMinutes);
        random.nextBytes(dummyPasswordSalt);
        Files.createDirectories(dataDirectory);
        load();
        ensureFirstRunSetup(publicAddress);
    }

    synchronized boolean setupRequired() {
        return users.values().stream().noneMatch(user -> user.enabled() && user.role() == Role.OWNER);
    }

    Path setupPath() {
        return setupPath;
    }

    synchronized BrowserSession setupOwner(String providedCode, String username, String password)
            throws IOException {
        if (!setupRequired()) throw new IllegalStateException("Owner setup is already complete");
        validateUsername(username);
        validatePassword(username, password);
        if (setupCode.isEmpty() || !constantTimeText(setupCode, providedCode)) {
            throw new SecurityException("Invalid first-run setup code");
        }
        User owner = newUser(username, Role.OWNER, "", password);
        users.clear();
        users.put(key(username), owner);
        save();
        setupCode = "";
        Files.deleteIfExists(setupPath);
        return createSession(owner);
    }

    synchronized LoginResult login(String username, String password, String totp) {
        User user = users.get(key(username));
        boolean passwordValid;
        if (user == null) {
            derive(password == null ? "" : password,
                    dummyPasswordSalt, PASSWORD_ITERATIONS, PASSWORD_KEY_BITS);
            passwordValid = false;
        } else {
            passwordValid = verifyPassword(user, password);
        }
        if (user == null || !user.enabled() || !passwordValid) {
            return new LoginResult(false, false, null, "Invalid username or password");
        }
        if (user.totpSecret() != null && !user.totpSecret().isBlank()) {
            if (totp == null || totp.isBlank()) {
                return new LoginResult(false, true, null, "Authenticator code required");
            }
            if (!verifyTotp(user.totpSecret(), totp, Instant.now())) {
                return new LoginResult(false, true, null, "Invalid username, password, or authenticator code");
            }
        }
        return new LoginResult(true, false, createSession(user), "");
    }

    Optional<Principal> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String tokenHash = sha256(token);
        StoredSession session = sessions.get(tokenHash);
        if (session == null) return Optional.empty();
        if (session.expiresAt() <= System.currentTimeMillis()) {
            sessions.remove(tokenHash, session);
            return Optional.empty();
        }
        synchronized (this) {
            User user = users.get(key(session.username()));
            if (user == null || !user.enabled()) {
                sessions.remove(tokenHash, session);
                return Optional.empty();
            }
            return Optional.of(new Principal(user.username(), user.role(), user.tenantId()));
        }
    }

    void logout(String token) {
        if (token != null && !token.isBlank()) sessions.remove(sha256(token));
    }

    void cleanupSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    synchronized List<UserView> users() {
        return users.values().stream()
                .map(User::view)
                .sorted(Comparator.comparing(UserView::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized void createUser(String username, Role role, String password) throws IOException {
        createUser(username, role, "", password);
    }

    synchronized void createTenantUser(String username, String tenantId, String password) throws IOException {
        createUser(username, Role.TENANT, tenantId, password);
    }

    private synchronized void createUser(String username, Role role, String tenantId, String password)
            throws IOException {
        validateUsername(username);
        validatePassword(username, password);
        if (role == Role.OWNER) throw new IllegalArgumentException("Additional owner accounts are not allowed");
        String normalizedTenant = tenantId == null ? "" : tenantId.trim().toLowerCase(Locale.ROOT);
        if (role == Role.TENANT) {
            validateTenantId(normalizedTenant);
        } else if (!normalizedTenant.isBlank()) {
            throw new IllegalArgumentException("Only tenant accounts may have a tenant ID");
        }
        if (users.containsKey(key(username))) throw new IllegalArgumentException("Username already exists");
        users.put(key(username), newUser(username, role, normalizedTenant, password));
        save();
    }

    synchronized boolean hasUser(String username) {
        return users.containsKey(key(username));
    }

    synchronized List<UserView> tenantUsers(String tenantId) {
        String normalized = tenantId == null ? "" : tenantId.trim().toLowerCase(Locale.ROOT);
        return users.values().stream()
                .filter(user -> user.role() == Role.TENANT && user.tenantId().equals(normalized))
                .map(User::view)
                .sorted(Comparator.comparing(UserView::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized void deleteUser(String actor, String username) throws IOException {
        User user = users.get(key(username));
        if (user == null) throw new IllegalArgumentException("Unknown dashboard account");
        if (user.role() == Role.OWNER) throw new IllegalArgumentException("The owner account cannot be deleted");
        if (key(actor).equals(key(username))) throw new IllegalArgumentException("You cannot delete your own account");
        users.remove(key(username));
        revokeSessions(username);
        save();
    }

    synchronized void changePassword(String username, String currentPassword, String newPassword) throws IOException {
        User existing = users.get(key(username));
        if (existing == null || !verifyPassword(existing, currentPassword)) {
            throw new SecurityException("Current password is incorrect");
        }
        validatePassword(existing.username(), newPassword);
        User replacement = newUser(existing.username(), existing.role(), existing.tenantId(), newPassword);
        replacement = new User(
                replacement.username(), replacement.role(), replacement.tenantId(), replacement.enabled(), replacement.salt(),
                replacement.passwordHash(), replacement.iterations(), existing.totpSecret());
        users.put(key(username), replacement);
        revokeSessions(username);
        save();
    }

    TotpEnrollment beginTotp(String username) {
        byte[] secret = new byte[TOTP_SECRET_BYTES];
        random.nextBytes(secret);
        String encoded = base32(secret);
        String label = "OniLink:" + username;
        String uri = "otpauth://totp/" + URLEncoder.encode(label, StandardCharsets.UTF_8)
                + "?secret=" + encoded + "&issuer=OniLink&algorithm=SHA1&digits=6&period=30";
        return new TotpEnrollment(encoded, uri);
    }

    synchronized void enableTotp(String username, String secret, String code) throws IOException {
        User user = requireUser(username);
        String normalized = secret == null ? "" : secret.replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized.length() < 16 || !verifyTotp(normalized, code, Instant.now())) {
            throw new SecurityException("Authenticator code did not match the enrollment secret");
        }
        users.put(key(username), withTotp(user, normalized));
        revokeSessions(username);
        save();
    }

    synchronized void disableTotp(String username, String password, String code) throws IOException {
        User user = requireUser(username);
        if (!verifyPassword(user, password)) throw new SecurityException("Current password is incorrect");
        if (user.totpSecret() == null || user.totpSecret().isBlank()) return;
        if (!verifyTotp(user.totpSecret(), code, Instant.now())) {
            throw new SecurityException("Authenticator code is incorrect");
        }
        users.put(key(username), withTotp(user, ""));
        revokeSessions(username);
        save();
    }

    private User requireUser(String username) {
        User user = users.get(key(username));
        if (user == null) throw new IllegalArgumentException("Unknown dashboard account");
        return user;
    }

    private static User withTotp(User user, String secret) {
        return new User(user.username(), user.role(), user.tenantId(), user.enabled(), user.salt(), user.passwordHash(),
                user.iterations(), secret);
    }

    private void revokeSessions(String username) {
        sessions.entrySet().removeIf(entry -> key(entry.getValue().username()).equals(key(username)));
    }

    private BrowserSession createSession(User user) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        random.nextBytes(tokenBytes);
        String token = BASE64.encodeToString(tokenBytes);
        long expiresAt = System.currentTimeMillis() + sessionLifetime.toMillis();
        sessions.put(sha256(token), new StoredSession(user.username(), expiresAt));
        return new BrowserSession(token, expiresAt, new Principal(user.username(), user.role(), user.tenantId()));
    }

    private User newUser(String username, Role role, String tenantId, String password) {
        byte[] salt = new byte[PASSWORD_SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, PASSWORD_ITERATIONS, PASSWORD_KEY_BITS);
        return new User(username.trim(), role, tenantId, true, salt, hash, PASSWORD_ITERATIONS, "");
    }

    private static boolean verifyPassword(User user, String password) {
        if (password == null || user.iterations() < 50_000) return false;
        byte[] actual = derive(password, user.salt(), user.iterations(), user.passwordHash().length * 8);
        return MessageDigest.isEqual(user.passwordHash(), actual);
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int bits) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    static void validateUsername(String username) {
        if (username == null || !username.matches("[A-Za-z0-9._-]{3,32}")) {
            throw new IllegalArgumentException(
                    "Username must contain 3 to 32 letters, numbers, periods, underscores, or hyphens");
        }
    }

    static void validatePassword(String username, String password) {
        if (password == null || password.length() < 12 || password.length() > 256) {
            throw new IllegalArgumentException("Password must contain 12 to 256 characters");
        }
        if (password.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("Password cannot match the username");
        }
    }

    private static void validateTenantId(String tenantId) {
        if (tenantId == null || !tenantId.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException(
                    "Tenant ID must be 2 to 32 lowercase letters, numbers, or hyphens and start with a letter");
        }
    }

    private synchronized void load() throws IOException {
        if (Files.notExists(accountsPath)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(accountsPath)) {
            properties.load(input);
        }
        int version;
        try {
            version = Integer.parseInt(properties.getProperty("version", "0"));
        } catch (NumberFormatException exception) {
            throw new IOException("Dashboard account storage has an invalid version", exception);
        }
        if (version != STORAGE_VERSION) {
            throw new IOException("Unsupported dashboard account storage version: " + version);
        }
        for (String id : properties.getProperty("users", "").split(",")) {
            if (id.isBlank()) continue;
            String prefix = "user." + id + ".";
            try {
                User user = new User(
                        properties.getProperty(prefix + "username"),
                        Role.parse(properties.getProperty(prefix + "role")),
                        properties.getProperty(prefix + "tenantId", ""),
                        Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "true")),
                        BASE64_DECODER.decode(properties.getProperty(prefix + "salt")),
                        BASE64_DECODER.decode(properties.getProperty(prefix + "passwordHash")),
                        Integer.parseInt(properties.getProperty(prefix + "iterations")),
                        properties.getProperty(prefix + "totpSecret", "")
                );
                validateUsername(user.username());
                if (user.role() == Role.TENANT) validateTenantId(user.tenantId());
                if (user.role() != Role.TENANT && !user.tenantId().isBlank()) {
                    throw new IllegalArgumentException("Only tenant accounts may have a tenant ID");
                }
                users.put(key(user.username()), user);
            } catch (RuntimeException exception) {
                throw new IOException("Dashboard account storage contains an invalid user record", exception);
            }
        }
    }

    private synchronized void save() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(STORAGE_VERSION));
        List<String> ids = new ArrayList<>();
        for (User user : users.values()) {
            String id = BASE64.encodeToString(user.username().getBytes(StandardCharsets.UTF_8));
            ids.add(id);
            String prefix = "user." + id + ".";
            properties.setProperty(prefix + "username", user.username());
            properties.setProperty(prefix + "role", user.role().wireName());
            if (!user.tenantId().isBlank()) properties.setProperty(prefix + "tenantId", user.tenantId());
            properties.setProperty(prefix + "enabled", Boolean.toString(user.enabled()));
            properties.setProperty(prefix + "salt", BASE64.encodeToString(user.salt()));
            properties.setProperty(prefix + "passwordHash", BASE64.encodeToString(user.passwordHash()));
            properties.setProperty(prefix + "iterations", Integer.toString(user.iterations()));
            if (user.totpSecret() != null && !user.totpSecret().isBlank()) {
                properties.setProperty(prefix + "totpSecret", user.totpSecret());
            }
        }
        properties.setProperty("users", String.join(",", ids));
        Path temporary = accountsPath.resolveSibling(accountsPath.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "OniLink dashboard accounts — password hashes, not plaintext passwords");
        }
        ownerOnly(temporary);
        try {
            Files.move(temporary, accountsPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, accountsPath, StandardCopyOption.REPLACE_EXISTING);
        }
        ownerOnly(accountsPath);
    }

    private synchronized void ensureFirstRunSetup(String publicAddress) throws IOException {
        if (!setupRequired()) {
            setupCode = "";
            Files.deleteIfExists(setupPath);
            return;
        }
        String configured = System.getenv("ONILINK_DASHBOARD_SETUP_CODE");
        setupCode = configured == null ? "" : configured.trim();
        if (setupCode.length() < 16 && Files.isRegularFile(setupPath)) {
            for (String line : Files.readAllLines(setupPath, StandardCharsets.UTF_8)) {
                if (line.startsWith("Setup code: ")) setupCode = line.substring("Setup code: ".length()).trim();
            }
        }
        if (setupCode.length() < 16) {
            byte[] randomBytes = new byte[TOKEN_BYTES];
            random.nextBytes(randomBytes);
            setupCode = BASE64.encodeToString(randomBytes);
        }
        String contents = "OniLink dashboard first-run owner setup\n"
                + "Dashboard: " + publicAddress + "\n"
                + "Setup code: " + setupCode + "\n\n"
                + "Create the owner account in the browser. This file is deleted after setup.\n";
        Files.writeString(setupPath, contents, StandardCharsets.UTF_8);
        ownerOnly(setupPath);
        System.out.printf("OniLink dashboard first-run setup: %s%n", publicAddress);
        System.out.printf("OniLink dashboard setup code: %s%n", setupCode);
    }

    private static void ownerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs inherit from the server directory; POSIX deployments get an explicit 0600.
        }
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean constantTimeText(String left, String right) {
        return MessageDigest.isEqual(
                sha256Bytes(left == null ? "" : left),
                sha256Bytes(right == null ? "" : right));
    }

    private static String sha256(String value) {
        return java.util.HexFormat.of().formatHex(sha256Bytes(value));
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static boolean verifyTotp(String secret, String rawCode, Instant now) {
        if (secret == null || rawCode == null) return false;
        String code = rawCode.replace(" ", "").trim();
        if (!code.matches("\\d{6}")) return false;
        byte[] key;
        try {
            key = base32Decode(secret);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        long counter = now.getEpochSecond() / 30L;
        for (long offset = -1; offset <= 1; offset++) {
            if (constantTimeText(totp(key, counter + offset), code)) return true;
        }
        return false;
    }

    private static String totp(byte[] key, long counter) {
        byte[] message = new byte[8];
        for (int index = 7; index >= 0; index--) {
            message[index] = (byte) counter;
            counter >>>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(message);
            int offset = digest[digest.length - 1] & 0x0f;
            int value = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA1 is unavailable", exception);
        }
    }

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private static String base32(byte[] input) {
        StringBuilder output = new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                output.append(BASE32[(buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) output.append(BASE32[(buffer << (5 - bits)) & 31]);
        return output.toString();
    }

    private static byte[] base32Decode(String input) {
        String normalized = input.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        byte[] output = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int cursor = 0;
        for (int index = 0; index < normalized.length(); index++) {
            int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(normalized.charAt(index));
            if (value < 0) throw new IllegalArgumentException("Invalid Base32 character");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                output[cursor++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        return cursor == output.length ? output : java.util.Arrays.copyOf(output, cursor);
    }
}
