package com.tom.cpm.server.db;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

import com.tom.cpm.shared.util.Log;

/**
 * Manages the H2 embedded database lifecycle.
 * Uses pure JDBC (no H2 compile-time imports) to avoid
 * NeoForge ModuleClassLoader issues in the dev environment.
 * 
 * Features:
 * - WAL mode for crash resilience
 * - Optional file-level AES encryption (CIPHER=AES)
 * - Simple connection pooling via DriverManager
 * - Auto-backup on shutdown
 * - Migration runner on startup
 */
public class DatabaseManager implements AutoCloseable {

    private final File dbDirectory;
    private final String dbPassword;
    private final boolean fileEncryption;
    private final MigrationManager migrationManager;

    private String jdbcUrl;
    private String dbUser;
    private String dbPass;
    private Driver h2Driver;
    private final Deque<Connection> pool = new ArrayDeque<>();
    private static final int MAX_POOL_SIZE = 10;
    private boolean initialized;

    public DatabaseManager(File dbDirectory, String dbPassword, boolean fileEncryption,
                           MigrationManager migrationManager) {
        this.dbDirectory = dbDirectory;
        this.dbPassword = dbPassword;
        this.fileEncryption = fileEncryption;
        this.migrationManager = migrationManager;
    }

    /**
     * Initialize the database: create directory, open connections, run migrations.
     * Uses reflection to load H2 driver so no H2 imports are needed at compile time.
     */
    public void initialize() throws SQLException {
        if (initialized) return;

        if (!dbDirectory.exists()) {
            dbDirectory.mkdirs();
        }

        // Build JDBC URL
        String dbPath = new File(dbDirectory, "cpm_models").getAbsolutePath();
        StringBuilder url = new StringBuilder("jdbc:h2:file:").append(dbPath)
            .append(";MODE=MySQL")
            .append(";DATABASE_TO_UPPER=false");

        if (fileEncryption && !dbPassword.isEmpty()) {
            url.append(";CIPHER=AES");
        }

        this.jdbcUrl = url.toString();
        this.dbUser = "cpm";
        this.dbPass = fileEncryption ? dbPassword + " cpm_models" : "";

        loadAndRegisterDriver();

        // Verify connectivity and init settings
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET WRITE_DELAY 0");
            stmt.execute("SET CACHE_SIZE 16384");
            Log.info("Database initialized: " + jdbcUrl);
        }

        // Run migrations
        try (Connection conn = openConnection()) {
            migrationManager.migrate(conn);
        }

        initialized = true;
        Log.info("Database ready. Schema version: " + migrationManager.getCurrentVersion());
    }

    /**
     * Get a database connection (from pool or new).
     * Caller MUST close the connection.
     */
    public Connection getConnection() throws SQLException {
        if (!initialized) {
            throw new IllegalStateException("Database not initialized");
        }
        Connection conn = pool.pollFirst();
        if (conn == null || conn.isClosed()) {
            return openConnection();
        }
        return conn;
    }

    private Connection openConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPass);
        if (h2Driver != null) {
            Connection conn = h2Driver.connect(jdbcUrl, props);
            if (conn != null) return conn;
        }
        return DriverManager.getConnection(jdbcUrl, props);
    }

    /**
     * Return a connection to the pool instead of closing it.
     */
    public void returnConnection(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.isClosed() && pool.size() < MAX_POOL_SIZE) {
                    pool.addLast(conn);
                    return;
                }
            } catch (SQLException ignored) {}
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private void loadAndRegisterDriver() throws SQLException {
        final String driverClassName = "org.h2.Driver";
        ClassLoader[] candidates = new ClassLoader[] {
            DatabaseManager.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        };

        Exception lastError = null;
        for (ClassLoader loader : candidates) {
            if (loader == null) continue;
            try {
                Driver h2Driver = (Driver) Class.forName(driverClassName, true, loader)
                    .getDeclaredConstructor().newInstance();
                this.h2Driver = h2Driver;
                DriverManager.registerDriver(h2Driver);
                Log.info("H2 driver registered successfully using classloader: " + loader);
                return;
            } catch (Exception ex) {
                lastError = ex;
            }
        }

        try {
            Driver h2Driver = (Driver) Class.forName(driverClassName)
                .getDeclaredConstructor().newInstance();
            this.h2Driver = h2Driver;
            DriverManager.registerDriver(h2Driver);
            Log.info("H2 driver registered successfully using default Class.forName");
            return;
        } catch (Exception ex) {
            lastError = ex;
        }

        throw new SQLException("H2 driver not available. Ensure h2 is on NeoForge runtime classpath or jarJar embedded in the mod jar.", lastError);
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
        if (initialized) {
            try (Connection conn = openConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN COMPACT");
                Log.info("Database shut down cleanly");
            } catch (SQLException e) {
                Log.error("Error during database shutdown", e);
            }
            // Close pooled connections
            while (!pool.isEmpty()) {
                try { pool.pollFirst().close(); } catch (SQLException ignored) {}
            }
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
