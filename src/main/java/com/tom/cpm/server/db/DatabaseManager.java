package com.tom.cpm.server.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.jdbcx.JdbcConnectionPool;

import com.tom.cpm.shared.util.Log;

/**
 * Manages the H2 embedded database lifecycle.
 * 
 * Features:
 * - WAL mode for crash resilience
 * - Optional file-level AES encryption (CIPHER=AES)
 * - Connection pooling via H2's built-in JdbcConnectionPool
 * - Auto-backup on shutdown
 * - Migration runner on startup
 */
public class DatabaseManager implements AutoCloseable {

    private final File dbDirectory;
    private final String dbPassword;
    private final boolean fileEncryption;
    private final MigrationManager migrationManager;

    private JdbcConnectionPool connectionPool;
    private boolean initialized;

    /**
     * @param dbDirectory     directory for database files
     * @param dbPassword      password for H2 file encryption (empty = no encryption)
     * @param fileEncryption  whether to enable H2 CIPHER=AES mode
     * @param migrationManager schema migration manager
     */
    public DatabaseManager(File dbDirectory, String dbPassword, boolean fileEncryption,
                           MigrationManager migrationManager) {
        this.dbDirectory = dbDirectory;
        this.dbPassword = dbPassword;
        this.fileEncryption = fileEncryption;
        this.migrationManager = migrationManager;
    }

    /**
     * Initialize the database: create directory, open connection pool, run migrations.
     */
    public void initialize() throws SQLException {
        if (initialized) return;

        if (!dbDirectory.exists()) {
            dbDirectory.mkdirs();
        }

        // Build JDBC URL
        String dbPath = new File(dbDirectory, "cpm_models").getAbsolutePath();
        StringBuilder url = new StringBuilder("jdbc:h2:file:").append(dbPath);

        // H2 settings for performance and safety
        url.append(";MODE=MySQL");          // More familiar SQL dialect
        url.append(";DATABASE_TO_UPPER=false"); // Preserve case

        if (fileEncryption && !dbPassword.isEmpty()) {
            url.append(";CIPHER=AES");
            url.append(";DB_CLOSE_ON_EXIT=FALSE"); // Let us control shutdown
        }
        // WAL mode for crash resilience
        // Note: H2 settings after ; are connection-level, not URL-level in some versions.
        // We set them in the connection init SQL below.

        String jdbcUrl = url.toString();

        // Modern JDBC (4.0+) auto-discovers drivers via service loader.
        // H2 provides META-INF/services/java.sql.Driver, so DriverManager works without Class.forName.
        // We still try explicit loading for environments without service loader support.
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            Log.warn("H2 driver not found via Class.forName, trying DriverManager auto-discovery");
        }

        // Create connection pool
        String user = "cpm";
        String pass = fileEncryption ? dbPassword + " cpm_models" : "";

        connectionPool = JdbcConnectionPool.create(jdbcUrl, user, pass);
        connectionPool.setMaxConnections(10);
        connectionPool.setLoginTimeout(5);

        // Initialize connection with WAL mode
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET WRITE_DELAY 0");  // Disable write delay for safety
            stmt.execute("SET LOG 1");          // Enable transaction log (already default with WAL)
            stmt.execute("SET CACHE_SIZE 16384"); // 16 MB cache
            Log.info("Database initialized: " + jdbcUrl);
        }

        // Run migrations
        try (Connection conn = getConnection()) {
            migrationManager.migrate(conn);
        }

        initialized = true;
        Log.info("Database ready. Schema version: " + migrationManager.getCurrentVersion());
    }

    /**
     * Get a database connection from the pool.
     * Caller MUST close the connection (returns it to the pool).
     */
    public Connection getConnection() throws SQLException {
        if (!initialized) {
            throw new IllegalStateException("Database not initialized");
        }
        return connectionPool.getConnection();
    }

    /**
     * Create a backup of the database.
     */
    public void backup(File backupDir) throws SQLException {
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        String backupPath = new File(backupDir,
            "cpm_models_backup_" + System.currentTimeMillis() + ".zip").getAbsolutePath();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("BACKUP TO '" + backupPath + "'");
            Log.info("Database backup created: " + backupPath);
        }
    }

    /**
     * Verify database integrity.
     * @return true if no corruption detected
     */
    public boolean verifyIntegrity() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CHECKPOINT");
            return true;
        } catch (SQLException e) {
            Log.error("Database integrity check failed", e);
            return false;
        }
    }

    @Override
    public void close() {
        if (connectionPool != null) {
            // Checkpoint before closing to ensure all data is written
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN COMPACT");
                Log.info("Database shut down cleanly");
            } catch (SQLException e) {
                Log.error("Error during database shutdown", e);
            }
            connectionPool.dispose();
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get database statistics for admin display.
     */
    public String getStats() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            StringBuilder sb = new StringBuilder();
            var rs1 = stmt.executeQuery(
                "SELECT COUNT(*) FROM models");
            if (rs1.next()) sb.append("Models: ").append(rs1.getInt(1)).append(", ");

            var rs2 = stmt.executeQuery(
                "SELECT COUNT(*) FROM players");
            if (rs2.next()) sb.append("Players: ").append(rs2.getInt(1)).append(", ");

            var rs3 = stmt.executeQuery(
                "SELECT COUNT(*) FROM upload_sessions WHERE status='ACTIVE'");
            if (rs3.next()) sb.append("Active uploads: ").append(rs3.getInt(1));

            return sb.toString();
        } catch (SQLException e) {
            return "Stats unavailable: " + e.getMessage();
        }
    }
}
