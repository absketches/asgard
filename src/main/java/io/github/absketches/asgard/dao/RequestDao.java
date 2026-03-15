package io.github.absketches.asgard.dao;

import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.UserBlock;
import io.github.absketches.asgard.util.ClassifierHelper;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StorageService — pure static SQLite utility. No lifecycle, no events.
 * init() must be called once before Nano starts (in Main or @BeforeEach in tests).
 */
public final class RequestDao {

    private static final int DEFAULT_MAX = 10_000;

    static volatile Connection connection;
    private static String dbPath  = "asgard.db";

    private RequestDao() {}

    /**
     * Opens (or creates) the SQLite database and creates tables if needed.
     * On corruption, deletes the file and retries once.
     * For tests, pass ":memory:".
     */
    public static void init(final String path) {
        dbPath = path;
        tryInit();
        // Seed ClassifierHelper with persisted user blocks
        ClassifierHelper.updateUserBlocklist(loadUserBlockHosts());
    }

    public static List<RequestRecord> getPage(final int page, final int pageSize) {
        final List<RequestRecord> results = new ArrayList<>();
        if (connection == null) return results;
        try (final PreparedStatement stmt = connection.prepareStatement("""
            SELECT id, timestamp, source_ip, destination, method, data_size, classification, blocked
            FROM requests ORDER BY timestamp DESC LIMIT ? OFFSET ?
            """)) {
            stmt.setInt(1, pageSize);
            stmt.setInt(2, page * pageSize);
            final ResultSet rs = stmt.executeQuery();
            while (rs.next()) results.add(mapRow(rs));
        } catch (final SQLException ignored) {}
        return results;
    }

    public static List<RequestRecord> getRequestsSince(final String isoTimestamp, final int limit) {
        final List<RequestRecord> results = new ArrayList<>();
        if (connection == null) return results;
        try (final PreparedStatement stmt = connection.prepareStatement("""
            SELECT id, timestamp, source_ip, destination, method, data_size, classification, blocked
            FROM requests WHERE timestamp > ? ORDER BY timestamp DESC LIMIT ?
            """)) {
            stmt.setString(1, isoTimestamp);
            stmt.setInt(2, limit);
            final ResultSet rs = stmt.executeQuery();
            while (rs.next()) results.add(mapRow(rs));
        } catch (final SQLException ignored) {}
        return results;
    }

    public static List<UserBlock> getUserBlocks() {
        final List<UserBlock> results = new ArrayList<>();
        if (connection == null) return results;
        try (final PreparedStatement stmt = connection.prepareStatement(
            "SELECT host, created_at, note FROM user_blocks ORDER BY created_at DESC")) {
            final ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                results.add(new UserBlock(
                    rs.getString("host"),
                    Instant.parse(rs.getString("created_at")),
                    rs.getString("note")
                ));
            }
        } catch (final SQLException ignored) {}
        return results;
    }

    public static void persist(final RequestRecord record) {
        if (record == null || connection == null) return;
        try (final PreparedStatement stmt = connection.prepareStatement("""
            INSERT OR IGNORE INTO requests
              (id, timestamp, source_ip, destination, method, data_size, classification, blocked)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            stmt.setString(1, record.id());
            stmt.setString(2, record.timestamp().toString());
            stmt.setString(3, record.sourceIp());
            stmt.setString(4, record.destination());
            stmt.setString(5, record.method());
            stmt.setLong(6, record.dataSize());
            stmt.setString(7, record.classification().name());
            stmt.setBoolean(8, record.blocked());
            stmt.executeUpdate();
            enforceCap();
        } catch (final SQLException ignored) {}
    }

    public static void insertUserBlock(final String host, final String note) {
        if (connection == null) return;
        try (final PreparedStatement stmt = connection.prepareStatement(
            "INSERT OR IGNORE INTO user_blocks (host, created_at, note) VALUES (?, ?, ?)")) {
            stmt.setString(1, host.toLowerCase().trim());
            stmt.setString(2, Instant.now().toString());
            stmt.setString(3, note);
            stmt.executeUpdate();
        } catch (final SQLException ignored) {}
        ClassifierHelper.updateUserBlocklist(loadUserBlockHosts());
    }

    public static void deleteUserBlock(final String host) {
        if (connection == null) return;
        try (final PreparedStatement stmt = connection.prepareStatement(
            "DELETE FROM user_blocks WHERE host = ?")) {
            stmt.setString(1, host.toLowerCase().trim());
            stmt.executeUpdate();
        } catch (final SQLException ignored) {}
        ClassifierHelper.updateUserBlocklist(loadUserBlockHosts());
    }

    public static void clearRequests(final String classification) {
        if (connection == null) return;
        try (final Statement stmt = connection.createStatement()) {
            if ("ALL".equalsIgnoreCase(classification)) {
                stmt.execute("DELETE FROM requests");
            } else {
                stmt.execute("DELETE FROM requests WHERE classification = '" + classification + "'");
            }
        } catch (final SQLException ignored) {}
    }

    static Set<String> loadUserBlockHosts() {
        final Set<String> hosts = ConcurrentHashMap.newKeySet();
        if (connection == null) return hosts;
        try (final PreparedStatement stmt = connection.prepareStatement(
            "SELECT host FROM user_blocks")) {
            final ResultSet rs = stmt.executeQuery();
            while (rs.next()) hosts.add(rs.getString("host"));
        } catch (final SQLException ignored) {}
        return hosts;
    }

    private static void enforceCap() throws SQLException {
        try (final PreparedStatement stmt = connection.prepareStatement("""
            DELETE FROM requests WHERE id NOT IN (
                SELECT id FROM requests ORDER BY timestamp DESC LIMIT ?
            )
            """)) {
            stmt.setInt(1, DEFAULT_MAX);
            stmt.executeUpdate();
        }
    }

    private static void tryInit() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            createSchema(connection);
        } catch (final Exception e) {
            if (!":memory:".equals(dbPath)) {
                closeQuietly();
                final java.io.File dbFile = new java.io.File(dbPath);
                if (dbFile.exists() && dbFile.delete()) {
                    try {
                        Class.forName("org.sqlite.JDBC");
                        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                        createSchema(connection);
                        return;
                    } catch (final Exception retry) {
                        throw new RuntimeException("[Asgard] Failed to recreate SQLite after corruption: " + retry.getMessage(), retry);
                    }
                }
            }
            throw new RuntimeException("[Asgard] Failed to init SQLite: " + e.getMessage(), e);
        }
    }

    private static void createSchema(final Connection conn) throws SQLException {
        try (final Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS requests (
                    id             TEXT PRIMARY KEY,
                    timestamp      TEXT NOT NULL,
                    source_ip      TEXT,
                    destination    TEXT NOT NULL,
                    method         TEXT NOT NULL,
                    data_size      INTEGER DEFAULT 0,
                    classification TEXT NOT NULL,
                    blocked        INTEGER DEFAULT 0
                )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ts ON requests (timestamp DESC)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_blocks (
                    host       TEXT PRIMARY KEY,
                    created_at TEXT NOT NULL,
                    note       TEXT
                )""");
        }
    }

    private static void closeQuietly() {
        try {
            if (connection != null) { connection.close(); connection = null; }
        } catch (final Exception ignored) {}
    }

    private static RequestRecord mapRow(final ResultSet rs) throws SQLException {
        return new RequestRecord(
            rs.getString("id"),
            Instant.parse(rs.getString("timestamp")),
            rs.getString("source_ip"),
            rs.getString("destination"),
            rs.getString("method"),
            rs.getLong("data_size"),
            RequestRecord.Classification.valueOf(rs.getString("classification")),
            rs.getBoolean("blocked")
        );
    }
}
