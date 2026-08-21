package dev.onistone.onilink.platform.persistence;

import dev.onistone.onilink.control.ControlJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Transactional tenant/proxy scoped storage for expansion-module records. */
public final class PlatformDatabase implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;

    public record Scope(String tenantId, String proxyId) {
        public Scope {
            tenantId = normalize(tenantId);
            proxyId = normalize(proxyId);
        }

        public static Scope of(String tenantId, String proxyId) {
            return new Scope(tenantId, proxyId);
        }
    }

    public record StoredRecord(
            Scope scope, String kind, String id, long revision, Map<String, Object> value,
            Instant createdAt, Instant updatedAt
    ) {}

    private final Path path;
    private final Connection connection;

    public PlatformDatabase(Path dashboardDataDirectory) throws IOException {
        try {
            Path directory = dashboardDataDirectory.resolve("platform");
            Files.createDirectories(directory);
            this.path = directory.resolve("onilink-platform.db").toAbsolutePath().normalize();
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            migrate();
            ownerOnly(path);
            ownerOnly(path.resolveSibling(path.getFileName() + "-wal"));
            ownerOnly(path.resolveSibling(path.getFileName() + "-shm"));
        } catch (SQLException exception) {
            throw new IOException("Could not open OniLink platform database", exception);
        }
    }

    public Path path() {
        return path;
    }

    public synchronized StoredRecord put(
            Scope scope, String kind, String id, Long expectedRevision, Map<String, Object> value
    ) {
        validateKey(kind, "record kind");
        validateKey(id, "record ID");
        Map<String, Object> normalizedValue = value == null ? Map.of() : Map.copyOf(value);
        String payload = ControlJson.encode(normalizedValue);
        try {
            connection.setAutoCommit(false);
            StoredRecord existing = getInternal(scope, kind, id).orElse(null);
            if (expectedRevision != null) {
                long actual = existing == null ? 0 : existing.revision();
                if (expectedRevision != actual) throw new RevisionConflict(expectedRevision, actual);
            }
            long revision = existing == null ? 1 : existing.revision() + 1;
            String now = Instant.now().toString();
            String created = existing == null ? now : existing.createdAt().toString();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO platform_records(tenant_id, proxy_id, kind, record_id, revision, payload, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(tenant_id, proxy_id, kind, record_id) DO UPDATE SET
                      revision=excluded.revision, payload=excluded.payload, updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, scope.tenantId());
                statement.setString(2, scope.proxyId());
                statement.setString(3, kind);
                statement.setString(4, id);
                statement.setLong(5, revision);
                statement.setString(6, payload);
                statement.setString(7, created);
                statement.setString(8, now);
                statement.executeUpdate();
            }
            connection.commit();
            return new StoredRecord(scope, kind, id, revision, normalizedValue,
                    Instant.parse(created), Instant.parse(now));
        } catch (SQLException failure) {
            rollbackQuietly();
            throw new IllegalStateException("Could not save " + kind + " record", failure);
        } finally {
            autoCommitQuietly();
        }
    }

    public synchronized Optional<StoredRecord> get(Scope scope, String kind, String id) {
        validateKey(kind, "record kind");
        validateKey(id, "record ID");
        try {
            return getInternal(scope, kind, id);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not load " + kind + " record", failure);
        }
    }

    public synchronized List<StoredRecord> list(Scope scope, String kind, int maximum) {
        validateKey(kind, "record kind");
        int limit = Math.max(1, Math.min(maximum, 10_000));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT record_id, revision, payload, created_at, updated_at
                FROM platform_records WHERE tenant_id=? AND proxy_id=? AND kind=?
                ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, scope.tenantId());
            statement.setString(2, scope.proxyId());
            statement.setString(3, kind);
            statement.setInt(4, limit);
            List<StoredRecord> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(read(scope, kind, rows));
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not list " + kind + " records", failure);
        }
    }

    /** Lists a bounded record kind across scopes for startup reconciliation and scheduled work. */
    public synchronized List<StoredRecord> listAll(String kind, int maximum) {
        validateKey(kind, "record kind");
        int limit = Math.max(1, Math.min(maximum, 50_000));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tenant_id, proxy_id, record_id, revision, payload, created_at, updated_at
                FROM platform_records WHERE kind=? ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, kind);
            statement.setInt(2, limit);
            List<StoredRecord> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Scope scope = Scope.of(rows.getString("tenant_id"), rows.getString("proxy_id"));
                    result.add(read(scope, kind, rows));
                }
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not list " + kind + " records", failure);
        }
    }

    public synchronized boolean delete(Scope scope, String kind, String id, Long expectedRevision) {
        validateKey(kind, "record kind");
        validateKey(id, "record ID");
        try {
            StoredRecord existing = getInternal(scope, kind, id).orElse(null);
            if (existing == null) return false;
            if (expectedRevision != null && expectedRevision != existing.revision()) {
                throw new RevisionConflict(expectedRevision, existing.revision());
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM platform_records
                    WHERE tenant_id=? AND proxy_id=? AND kind=? AND record_id=?
                    """)) {
                statement.setString(1, scope.tenantId());
                statement.setString(2, scope.proxyId());
                statement.setString(3, kind);
                statement.setString(4, id);
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not delete " + kind + " record", failure);
        }
    }

    private Optional<StoredRecord> getInternal(Scope scope, String kind, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT record_id, revision, payload, created_at, updated_at
                FROM platform_records WHERE tenant_id=? AND proxy_id=? AND kind=? AND record_id=?
                """)) {
            statement.setString(1, scope.tenantId());
            statement.setString(2, scope.proxyId());
            statement.setString(3, kind);
            statement.setString(4, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(scope, kind, rows)) : Optional.empty();
            }
        }
    }

    private static StoredRecord read(Scope scope, String kind, ResultSet rows) throws SQLException {
        String payload = rows.getString("payload");
        Map<String, Object> value = ControlJson.parseObject(payload, 1_048_576);
        return new StoredRecord(scope, kind, rows.getString("record_id"), rows.getLong("revision"), value,
                Instant.parse(rows.getString("created_at")), Instant.parse(rows.getString("updated_at")));
    }

    private void migrate() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS platform_migrations(
                      version INTEGER PRIMARY KEY,
                      applied_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS platform_records(
                      tenant_id TEXT NOT NULL,
                      proxy_id TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      record_id TEXT NOT NULL,
                      revision INTEGER NOT NULL CHECK(revision > 0),
                      payload TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(tenant_id, proxy_id, kind, record_id)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS platform_records_scope_kind_updated
                    ON platform_records(tenant_id, proxy_id, kind, updated_at DESC)
                    """);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO platform_migrations(version, applied_at) VALUES(?, ?)")) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        }
        connection.commit();
        connection.setAutoCommit(true);
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original database failure.
        }
    }

    private void autoCommitQuietly() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // The next operation will surface a broken connection.
        }
    }

    private static void validateKey(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static String normalize(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("scope is invalid");
        }
        return normalized;
    }

    private static void ownerOnly(Path file) {
        if (Files.notExists(file)) return;
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows inherits the dashboard data-directory ACL.
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not close platform database", failure);
        }
    }

    public static final class RevisionConflict extends IllegalStateException {
        private final long expected;
        private final long actual;

        public RevisionConflict(long expected, long actual) {
            super("revision conflict: expected " + expected + " but current revision is " + actual);
            this.expected = expected;
            this.actual = actual;
        }

        public long expected() { return expected; }
        public long actual() { return actual; }
    }
}
