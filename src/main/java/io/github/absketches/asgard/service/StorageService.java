package io.github.absketches.asgard.service;

import io.github.absketches.asgard.AsgardChannels;
import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.UserBlock;
import berlin.yuna.typemap.model.TypeMapI;
import org.nanonative.nano.core.model.Service;
import org.nanonative.nano.helper.event.model.Event;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.nanonative.nano.helper.config.ConfigRegister.registerConfig;

/**
 * StorageService — the ONLY service that touches SQLite.
 * <p>
 * Fully independent — communicates only through Nano events.
 * No other service calls StorageService directly.
 * <p>
 * On startup:   fires USER_BLOCKS_LOADED with the full user block host set
 * so ClassifierService can seed its in-memory blocklist.
 * <p>
 * Listens for:
 * REQUEST_CLASSIFIED  — persist to requests table
 * REQUEST_BLOCKED     — persist to requests table
 * USER_BLOCK_ADD      — insert into user_blocks, fire USER_BLOCK_CONFIRMED with updated set
 * USER_BLOCK_REMOVE   — delete from user_blocks, fire USER_BLOCK_CONFIRMED with updated set
 */
public class StorageService extends Service {

    public static final String CONFIG_MAX_REQUESTS = registerConfig("asgard_max_requests", "Max requests to retain in SQLite (FIFO, default 50000)");
    public static final String CONFIG_DB_PATH = registerConfig("asgard_db_path", "SQLite database path (default asgard.db, use :memory: for tests)");

    private static final int DEFAULT_MAX = 10_000;

    static volatile Connection connection;  // volatile — written by start(), read by event threads
    private static int maxRows = DEFAULT_MAX;
    private static String dbPath = "asgard.db";

    @Override
    public void start() {
        initDatabase();
        // Fire user blocks to ClassifierService — it will seed its in-memory blocklist
        final Set<String> hosts = loadUserBlockHosts();
        context.newEvent(AsgardChannels.USER_BLOCKS_LOADED, () -> hosts).async(true).send();
        context.info(() -> "[Asgard] StorageService started — DB: {}, cap: {} rows", dbPath, maxRows);
    }

    @Override
    public void stop() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (final SQLException e) {
            context.warn(() -> "[Asgard] StorageService — failed to close DB: {}", e.getMessage());
        }
        context.info(() -> "[Asgard] StorageService stopped");
    }

    @Override
    public void onEvent(final Event<?, ?> event) {
        event.channel(AsgardChannels.REQUEST_CLASSIFIED).ifPresent(ev -> persist(ev.payload()));
        event.channel(AsgardChannels.REQUEST_BLOCKED).ifPresent(ev -> persist(ev.payload()));
        event.channel(AsgardChannels.USER_BLOCK_ADD).ifPresent(ev -> {
            final String[] parts = ev.payload();
            if (parts != null && parts.length >= 1) {
                final String host = parts[0];
                final String note = parts.length > 1 ? parts[1] : null;
                insertUserBlock(host, note);
                fireBlockConfirmed();
            }
        });
        event.channel(AsgardChannels.USER_BLOCK_REMOVE).ifPresent(ev -> {
            if (ev.payload() != null) {
                deleteUserBlock(ev.payload());
                fireBlockConfirmed();
            }
        });
        event.channel(AsgardChannels.CLEAR_REQUESTS).ifPresent(ev -> {
            if (ev.payload() != null) clearRequests(ev.payload());
        });
    }

    @Override
    public void configure(final TypeMapI<?> config, final TypeMapI<?> merged) {
        maxRows = merged.asIntOpt(CONFIG_MAX_REQUESTS).orElse(DEFAULT_MAX);
        dbPath = merged.asStringOpt(CONFIG_DB_PATH).orElse("asgard.db");
    }


    @Override
    public Object onFailure(final Event<?, ?> event) {
        context.warn(() -> "[Asgard] StorageService error: {}", event.error());
        return null;
    }

    public static List<RequestRecord> getPage(final int page, final int pageSize) {
        return query("""
            SELECT id, timestamp, source_ip, destination, method, data_size, classification, blocked
            FROM requests ORDER BY timestamp DESC LIMIT ? OFFSET ?
            """, stmt -> {
            stmt.setInt(1, pageSize);
            stmt.setInt(2, page * pageSize);
        });
    }

    public static List<RequestRecord> getRequestsSince(final String isoTimestamp, final int limit) {
        return query("""
            SELECT id, timestamp, source_ip, destination, method, data_size, classification, blocked
            FROM requests WHERE timestamp > ? ORDER BY timestamp DESC LIMIT ?
            """, stmt -> {
            stmt.setString(1, isoTimestamp);
            stmt.setInt(2, limit);
        });
    }

    public static List<UserBlock> getUserBlocks() {
        final List<UserBlock> results = new ArrayList<>();
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

    // ─────────────────────────────────────────────────────────────────────────
    // Private write helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void persist(final RequestRecord record) {
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
        } catch (final SQLException e) {
            context.warn(() -> "[Asgard] Failed to persist request: {}", e.getMessage());
        }
    }

    private static void insertUserBlock(final String host, final String note) {
        try (final PreparedStatement stmt = connection.prepareStatement(
            "INSERT OR IGNORE INTO user_blocks (host, created_at, note) VALUES (?, ?, ?)")) {
            stmt.setString(1, host.toLowerCase().trim());
            stmt.setString(2, Instant.now().toString());
            stmt.setString(3, note);
            stmt.executeUpdate();
        } catch (final SQLException ignored) {}
    }

    private static void deleteUserBlock(final String host) {
        try (final PreparedStatement stmt = connection.prepareStatement(
            "DELETE FROM user_blocks WHERE host = ?")) {
            stmt.setString(1, host.toLowerCase().trim());
            stmt.executeUpdate();
        } catch (final SQLException ignored) {}
    }

    /**
     * Re-read user blocks from DB and fire USER_BLOCK_CONFIRMED so ClassifierService stays in sync.
     */
    private void fireBlockConfirmed() {
        final Set<String> updated = loadUserBlockHosts();
        context.newEvent(AsgardChannels.USER_BLOCK_CONFIRMED, () -> updated).async(true).send();
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

    private void clearRequests(final String classification) {
        if (connection == null) return;
        try (final Statement stmt = connection.createStatement()) {
            if ("ALL".equalsIgnoreCase(classification)) {
                stmt.execute("DELETE FROM requests");
                context.info(() -> "[Asgard] Cleared all requests");
            } else {
                stmt.execute("DELETE FROM requests WHERE classification = '" + classification + "'");
                context.info(() -> "[Asgard] Cleared requests: {}", classification);
            }
        } catch (final SQLException e) {
            context.warn(() -> "[Asgard] Failed to clear requests: {}", e.getMessage());
        }
    }

    private static void enforceCap() throws SQLException {
        try (final PreparedStatement stmt = connection.prepareStatement("""
            DELETE FROM requests WHERE id NOT IN (
                SELECT id FROM requests ORDER BY timestamp DESC LIMIT ?
            )
            """)) {
            stmt.setInt(1, maxRows);
            stmt.executeUpdate();
        }
    }

    static void initDatabase() {
        tryInit();
    }

    private static void tryInit() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (final Statement stmt = connection.createStatement()) {
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
                    )
                    """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ts ON requests (timestamp DESC)");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS user_blocks (
                        host       TEXT PRIMARY KEY,
                        created_at TEXT NOT NULL,
                        note       TEXT
                    )
                    """);
            }
        } catch (final Exception e) {
            // DB corrupted — delete and start fresh (in-memory DBs are never deleted)
            if (!":memory:".equals(dbPath)) {
                try {
                    if (connection != null) {
                        connection.close();
                        connection = null;
                    }
                } catch (final Exception ignored) {}
                final java.io.File dbFile = new java.io.File(dbPath);
                if (dbFile.exists() && dbFile.delete()) {
                    // retry once with a clean file
                    try {
                        Class.forName("org.sqlite.JDBC");
                        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                        try (final Statement stmt = connection.createStatement()) {
                            stmt.execute("PRAGMA journal_mode=WAL");
                            stmt.execute("""
                                CREATE TABLE IF NOT EXISTS requests (
                                    id TEXT PRIMARY KEY, timestamp TEXT NOT NULL, source_ip TEXT,
                                    destination TEXT NOT NULL, method TEXT NOT NULL,
                                    data_size INTEGER DEFAULT 0, classification TEXT NOT NULL,
                                    blocked INTEGER DEFAULT 0
                                )""");
                            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ts ON requests (timestamp DESC)");
                            stmt.execute("""
                                CREATE TABLE IF NOT EXISTS user_blocks (
                                    host TEXT PRIMARY KEY, created_at TEXT NOT NULL, note TEXT
                                )""");
                        }
                        return; // recovered
                    } catch (final Exception retry) {
                        throw new RuntimeException("[Asgard] Failed to recreate SQLite after corruption: " + retry.getMessage(), retry);
                    }
                }
            }
            throw new RuntimeException("[Asgard] Failed to init SQLite: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    interface StatementBinder {
        void bind(PreparedStatement stmt) throws SQLException;
    }

    private static List<RequestRecord> query(final String sql, final StatementBinder binder) {
        final List<RequestRecord> results = new ArrayList<>();
        try (final PreparedStatement stmt = connection.prepareStatement(sql)) {
            binder.bind(stmt);
            final ResultSet rs = stmt.executeQuery();
            while (rs.next()) results.add(mapRow(rs));
        } catch (final SQLException ignored) {}
        return results;
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
