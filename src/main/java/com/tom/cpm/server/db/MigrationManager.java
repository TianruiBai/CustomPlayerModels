package com.tom.cpm.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.shared.util.Log;

/**
 * Schema versioning and migration for the CPM model database.
 * 
 * Each migration is a numbered step. The current schema version
 * is stored in the server_config table.
 */
public class MigrationManager {

    private static final String VERSION_KEY = "schema_version";
    private int currentVersion = -1;

    private final List<Migration> migrations = new ArrayList<>();

    public MigrationManager() {
        registerMigrations();
    }

    private void registerMigrations() {
        migrations.add(new Migration(1, "Initial schema", this::migrateV1));
        migrations.add(new Migration(2, "Stage 2 Security Enhancements", this::migrateV2));
    }

    /**
     * Run all pending migrations.
     */
    public void migrate(Connection conn) throws SQLException {
        // Ensure server_config table exists (created by v1 or later)
        ensureConfigTable(conn);

        // Read current version
        currentVersion = readVersion(conn);
        Log.info("Current DB schema version: " + currentVersion);

        // Run pending migrations
        for (Migration m : migrations) {
            if (m.version > currentVersion) {
                Log.info("Running migration " + m.version + ": " + m.description);
                conn.setAutoCommit(false);
                try {
                    m.runner.run(conn);
                    setVersion(conn, m.version);
                    conn.commit();
                    currentVersion = m.version;
                } catch (SQLException e) {
                    conn.rollback();
                    throw new SQLException("Migration " + m.version + " failed: " + m.description, e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }

        Log.info("Schema migration complete. Version: " + currentVersion);
    }

    private void migrateV1(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Player registry
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid        VARCHAR(36)  PRIMARY KEY,
                    username    VARCHAR(16)  NOT NULL,
                    first_seen  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    is_blocked  BOOLEAN      NOT NULL DEFAULT FALSE
                )
                """);

            // Model storage
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS models (
                    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
                    player_uuid VARCHAR(36)  NOT NULL,
                    name        VARCHAR(128) NOT NULL,
                    description VARCHAR(512),
                    data_enc    BLOB         NOT NULL,
                    data_iv     BINARY(12)   NOT NULL,
                    data_tag    BINARY(16)   NOT NULL,
                    icon_enc    BLOB,
                    icon_iv     BINARY(12),
                    icon_tag    BINARY(16),
                    size_bytes  INT          NOT NULL,
                    sha256      BINARY(32),
                    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
                    is_forced   BOOLEAN      NOT NULL DEFAULT FALSE,
                    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
                )
                """);

            // Upload sessions (for chunked transfer tracking)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS upload_sessions (
                    id              VARCHAR(36)  PRIMARY KEY,
                    player_uuid     VARCHAR(36)  NOT NULL,
                    model_name      VARCHAR(128) NOT NULL,
                    model_desc      VARCHAR(512),
                    total_chunks    INT          NOT NULL,
                    received_chunks INT          NOT NULL DEFAULT 0,
                    last_chunk_idx  INT          NOT NULL DEFAULT -1,
                    total_size      INT          NOT NULL,
                    full_sha256     BINARY(32),
                    chunk_data      BLOB,
                    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at      TIMESTAMP    NOT NULL,
                    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                )
                """);

            // Server configuration
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS server_config (
                    cfg_key     VARCHAR(128) PRIMARY KEY,
                    cfg_value   TEXT         NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // Audit log
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
                    actor       VARCHAR(36)  NOT NULL,
                    action      VARCHAR(64)  NOT NULL,
                    target      VARCHAR(36),
                    model_id    BIGINT,
                    details     TEXT,
                    ip_address  VARCHAR(45),
                    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // Indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_models_player ON models(player_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_models_default ON models(player_uuid, is_default)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_upload_player ON upload_sessions(player_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_upload_expires ON upload_sessions(expires_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log(actor)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_target ON audit_log(target)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(created_at DESC)");
        }
    }

    private void ensureConfigTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS server_config (
                    cfg_key     VARCHAR(128) PRIMARY KEY,
                    cfg_value   TEXT         NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        }
    }

    private void migrateV2(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 2.1+2.4: Add is_cloneable column to models table
            stmt.execute("ALTER TABLE models ADD COLUMN IF NOT EXISTS is_cloneable BOOLEAN NOT NULL DEFAULT FALSE");

            // 2.4: Add signature column for model attestation
            stmt.execute("ALTER TABLE models ADD COLUMN IF NOT EXISTS signature BINARY(32)");

            // 2.5: Add chain_hash column for audit integrity
            stmt.execute("ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS chain_hash BINARY(32)");

            // Create genesis audit entry if table is empty
            stmt.execute("""
                INSERT INTO audit_log (actor, action, target, model_id, details, ip_address, chain_hash, created_at)
                SELECT 'SYSTEM', 'GENESIS', NULL, NULL, 'Audit log initialized — Stage 2 Security', '127.0.0.1',
                       HASH('SHA256', 'CPM_AUDIT_GENESIS'), CURRENT_TIMESTAMP
                WHERE NOT EXISTS (SELECT 1 FROM audit_log)
                """);

            Log.info("Migration V2 complete: Stage 2 Security Enhancements applied");
        }
    }

    private int readVersion(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cfg_value FROM server_config WHERE cfg_key = ?")) {
            ps.setString(1, VERSION_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString(1));
                }
            }
        } catch (NumberFormatException e) {
            Log.warn("Invalid schema version in config, starting from 0");
        }
        return 0;
    }

    private void setVersion(Connection conn, int version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO server_config (cfg_key, cfg_value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, VERSION_KEY);
            ps.setString(2, String.valueOf(version));
            ps.executeUpdate();
        }
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    @FunctionalInterface
    private interface MigrationRunner {
        void run(Connection conn) throws SQLException;
    }

    private static class Migration {
        final int version;
        final String description;
        final MigrationRunner runner;

        Migration(int version, String description, MigrationRunner runner) {
            this.version = version;
            this.description = description;
            this.runner = runner;
        }
    }
}
