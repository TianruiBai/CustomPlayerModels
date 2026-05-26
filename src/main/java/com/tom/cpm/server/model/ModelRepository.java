package com.tom.cpm.server.model;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.server.crypto.EncryptedModelBlob;
import com.tom.cpm.server.db.DatabaseManager;
import com.tom.cpm.shared.util.Log;

/**
 * Data access layer for models, players, and audit log.
 * All model data is stored encrypted at the column level.
 * Scoped queries: players can only access their own models.
 */
public class ModelRepository {

    private final DatabaseManager dbManager;
    private final CryptoService crypto;
    private final SecretKey columnMasterKey;

    public ModelRepository(DatabaseManager dbManager, CryptoService crypto,
                           SecretKey columnMasterKey) {
        this.dbManager = dbManager;
        this.crypto = crypto;
        this.columnMasterKey = columnMasterKey;
    }

    // ================================================================
    // Player Management
    // ================================================================

    /**
     * Register or update a player on join.
     */
    public void upsertPlayer(String uuid, String username) throws SQLException {
        String sql = """
            MERGE INTO players (uuid, username, last_seen)
            KEY (uuid) VALUES (?, ?, CURRENT_TIMESTAMP)
            """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    public boolean isPlayerBlocked(String uuid) throws SQLException {
        String sql = "SELECT is_blocked FROM players WHERE uuid = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    public void setPlayerBlocked(String uuid, boolean blocked) throws SQLException {
        String sql = "UPDATE players SET is_blocked = ? WHERE uuid = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, blocked);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    // ================================================================
    // Model CRUD
    // ================================================================

    /**
     * Store a model. The plaintext byte array is encrypted with a per-row key
     * derived from the column master key. The plaintext is WIPED by this method.
     * 
     * @return the new model's ID
     */
    public long storeModel(String playerUuid, String name, String description,
                            byte[] modelData, byte[] iconData) throws SQLException {
        // Compute SHA-256 of plaintext before encryption
        byte[] sha256;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            sha256 = md.digest(modelData);
        } catch (Exception e) {
            throw new SQLException("SHA-256 not available", e);
        }

        String insertSql = """
            INSERT INTO models (player_uuid, name, description,
                data_enc, data_iv, data_tag,
                icon_enc, icon_iv, icon_tag,
                size_bytes, sha256, is_cloneable, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
        String updateSql = """
            UPDATE models
            SET data_enc=?, data_iv=?, data_tag=?,
                icon_enc=?, icon_iv=?, icon_tag=?,
                size_bytes=?, sha256=?, updated_at=CURRENT_TIMESTAMP
            WHERE id=?
            """;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long modelId;
                try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, playerUuid);
                    ps.setString(2, name);
                    ps.setString(3, description);
                    ps.setBytes(4, new byte[0]);
                    ps.setBytes(5, new byte[12]);
                    ps.setBytes(6, new byte[16]);
                    ps.setNull(7, java.sql.Types.BLOB);
                    ps.setNull(8, java.sql.Types.BINARY);
                    ps.setNull(9, java.sql.Types.BINARY);
                    ps.setInt(10, modelData.length);
                    ps.setBytes(11, sha256);
                    ps.executeUpdate();

                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) {
                            throw new SQLException("Failed to store model — no ID returned");
                        }
                        modelId = rs.getLong(1);
                    }
                }

                SecretKey perRowKey = crypto.derivePerRowKey(columnMasterKey, modelId);
                EncryptedModelBlob blob = new EncryptedModelBlob(modelData, perRowKey);
                EncryptedModelBlob iconBlob = null;
                if (iconData != null && iconData.length > 0) {
                    iconBlob = new EncryptedModelBlob(iconData, perRowKey);
                }

                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setBytes(1, blob.getCiphertext());
                    ps.setBytes(2, blob.getIv());
                    ps.setBytes(3, blob.getGcmTag());
                    if (iconBlob != null) {
                        ps.setBytes(4, iconBlob.getCiphertext());
                        ps.setBytes(5, iconBlob.getIv());
                        ps.setBytes(6, iconBlob.getGcmTag());
                    } else {
                        ps.setNull(4, java.sql.Types.BLOB);
                        ps.setNull(5, java.sql.Types.BINARY);
                        ps.setNull(6, java.sql.Types.BINARY);
                    }
                    ps.setInt(7, blob.getPlaintextSize());
                    ps.setBytes(8, sha256);
                    ps.setLong(9, modelId);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Failed to finalize encrypted model row: " + modelId);
                    }
                }

                conn.commit();
                return modelId;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException sqlEx) throw sqlEx;
                throw new SQLException("Failed to store encrypted model", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Load a model's encrypted data. Returns an EncryptedModelBlob that can be
     * decrypted on demand.
     */
    public EncryptedModelBlob loadModelBlob(long modelId) throws SQLException {
        String sql = "SELECT data_enc, data_iv, data_tag, size_bytes FROM models WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, modelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] dataEnc = rs.getBytes("data_enc");
                    byte[] dataIv = rs.getBytes("data_iv");
                    byte[] dataTag = rs.getBytes("data_tag");
                    int size = rs.getInt("size_bytes");

                    if (dataEnc == null) return null;

                    SecretKey perRowKey = crypto.derivePerRowKey(columnMasterKey, modelId);
                    return new EncryptedModelBlob(dataEnc, dataIv, dataTag, size, perRowKey);
                }
            }
        }
        return null;
    }

    /**
     * Load a model's encrypted icon data. Returns decrypted PNG bytes, or null.
     */
    public byte[] loadModelIcon(long modelId) throws SQLException {
        String sql = "SELECT icon_enc, icon_iv, icon_tag FROM models WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, modelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] iconEnc = rs.getBytes("icon_enc");
                    byte[] iconIv = rs.getBytes("icon_iv");
                    byte[] iconTag = rs.getBytes("icon_tag");

                    if (iconEnc == null || iconEnc.length == 0) return null;

                    SecretKey perRowKey = crypto.derivePerRowKey(columnMasterKey, modelId);
                    EncryptedModelBlob blob = new EncryptedModelBlob(iconEnc, iconIv, iconTag, 0, perRowKey);
                    return blob.getDecrypted();
                }
            }
        }
        return null;
    }

    /**
     * List models for a specific player.
     */
    public List<ModelEntity> listModelsForPlayer(String playerUuid) throws SQLException {
        String sql = """
            SELECT id, player_uuid, name, description, size_bytes,
                   is_default, is_forced, is_cloneable, created_at, updated_at
            FROM models WHERE player_uuid = ? ORDER BY updated_at DESC
            """;
        List<ModelEntity> models = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    models.add(mapRow(rs, false));
                }
            }
        }
        return models;
    }

    /**
     * List ALL models (admin only).
     */
    public List<ModelEntity> listAllModels(int offset, int limit) throws SQLException {
        String sql = """
            SELECT id, player_uuid, name, description, size_bytes,
                   is_default, is_forced, is_cloneable, created_at, updated_at
            FROM models ORDER BY updated_at DESC LIMIT ? OFFSET ?
            """;
        List<ModelEntity> models = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    models.add(mapRow(rs, false));
                }
            }
        }
        return models;
    }

    /**
     * Get total model count (admin).
     */
    public int countAllModels() throws SQLException {
        String sql = "SELECT COUNT(*) FROM models";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Delete a model. Checks ownership unless admin flag is set.
     */
    public boolean deleteModel(long modelId, String requestingPlayerUuid,
                                boolean isAdmin) throws SQLException {
        String sql = isAdmin
            ? "DELETE FROM models WHERE id = ?"
            : "DELETE FROM models WHERE id = ? AND player_uuid = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, modelId);
            if (!isAdmin) {
                ps.setString(2, requestingPlayerUuid);
            }
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Gap 5: Update a model's encrypted data in-place, preserving its ID.
     * The plaintext model bytes are encrypted and the row is UPDATEd.
     * This avoids the delete+reinsert pattern which changes the modelId.
     *
     * @param modelId     the existing model to update
     * @param modelData   the new plaintext model data (will be encrypted)
     * @param iconData    optional new icon data (null to keep existing)
     * @return true if the update succeeded, false if model not found
     */
    public boolean updateModel(long modelId, byte[] modelData, byte[] iconData) throws SQLException {
        // Compute SHA-256 of plaintext
        byte[] sha256;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            sha256 = md.digest(modelData);
        } catch (Exception e) {
            throw new SQLException("SHA-256 not available", e);
        }

        // Encrypt with the per-row key derived from the model ID
        SecretKey perRowKey = crypto.derivePerRowKey(columnMasterKey, modelId);
        EncryptedModelBlob blob = new EncryptedModelBlob(modelData, perRowKey);
        // modelData is now wiped by EncryptedModelBlob constructor

        EncryptedModelBlob iconBlob = null;
        if (iconData != null && iconData.length > 0) {
            iconBlob = new EncryptedModelBlob(iconData, perRowKey);
        }

        String sql;
        if (iconBlob != null) {
            sql = "UPDATE models SET data_enc=?, data_iv=?, data_tag=?, " +
                  "icon_enc=?, icon_iv=?, icon_tag=?, size_bytes=?, sha256=?, " +
                  "updated_at=CURRENT_TIMESTAMP WHERE id=?";
        } else {
            sql = "UPDATE models SET data_enc=?, data_iv=?, data_tag=?, " +
                  "size_bytes=?, sha256=?, updated_at=CURRENT_TIMESTAMP WHERE id=?";
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, blob.getCiphertext());
            ps.setBytes(2, blob.getIv());
            ps.setBytes(3, blob.getGcmTag());
            int idx = 4;
            if (iconBlob != null) {
                ps.setBytes(idx++, iconBlob.getCiphertext());
                ps.setBytes(idx++, iconBlob.getIv());
                ps.setBytes(idx++, iconBlob.getGcmTag());
            }
            ps.setInt(idx++, blob.getPlaintextSize());
            ps.setBytes(idx++, sha256);
            ps.setLong(idx, modelId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Set a model as the player's default.
     */
    public void setDefaultModel(String playerUuid, long modelId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Unset previous default
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE models SET is_default = FALSE WHERE player_uuid = ?")) {
                    ps.setString(1, playerUuid);
                    ps.executeUpdate();
                }
                // Set new default
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE models SET is_default = TRUE WHERE id = ? AND player_uuid = ?")) {
                    ps.setLong(1, modelId);
                    ps.setString(2, playerUuid);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Set forced status on a model (admin).
     */
    public void setForced(long modelId, boolean forced) throws SQLException {
        String sql = "UPDATE models SET is_forced = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, forced);
            ps.setLong(2, modelId);
            ps.executeUpdate();
        }
    }

    /**
     * Set cloneable flag on a model (set during upload based on model data).
     */
    public void setCloneable(long modelId, boolean cloneable) throws SQLException {
        String sql = "UPDATE models SET is_cloneable = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, cloneable);
            ps.setLong(2, modelId);
            ps.executeUpdate();
        }
    }

    /**
     * Retrieve model metadata (owner UUID, flags) without loading the encrypted blob.
     * Used for authorization checks before serving downloads.
     * 
     * @return ModelEntity with id, playerUuid, name, isForced, isDefault, isCloneable
     *         populated; null if not found
     */
    public ModelEntity getModelMetadata(long modelId) throws SQLException {
        String sql = "SELECT id, player_uuid, name, is_forced, is_default, is_cloneable FROM models WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, modelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ModelEntity m = new ModelEntity();
                    m.setId(rs.getLong("id"));
                    m.setPlayerUuid(rs.getString("player_uuid"));
                    m.setName(rs.getString("name"));
                    m.setForced(rs.getBoolean("is_forced"));
                    m.setDefault(rs.getBoolean("is_default"));
                    m.setCloneable(rs.getBoolean("is_cloneable"));
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Log an auditable action with hash-chain integrity (Stage 2.5).
     * Each entry chains to the previous via SHA-256.
     */
    public void logAction(String actor, String action, String target,
                           Long modelId, String details, String ipAddress) throws SQLException {
        // Compute chain hash from previous entry
        byte[] previousHash = getLatestChainHash();
        byte[] chainHash;
        try {
            String entryData = String.format("%s|%s|%s|%s|%s|%d",
                actor, action, target != null ? target : "",
                modelId != null ? modelId.toString() : "",
                details != null ? details : "",
                System.currentTimeMillis());
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            if (previousHash != null) md.update(previousHash);
            chainHash = md.digest(entryData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new SQLException("SHA-256 not available for audit chain", e);
        }

        String sql = """
            INSERT INTO audit_log (actor, action, target, model_id, details,
                                   ip_address, chain_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, actor);
            ps.setString(2, action);
            ps.setString(3, target);
            if (modelId != null) ps.setLong(4, modelId);
            else ps.setNull(4, java.sql.Types.BIGINT);
            ps.setString(5, details);
            ps.setString(6, ipAddress);
            ps.setBytes(7, chainHash);
            ps.executeUpdate();
        }

        // Update chain head in server_config
        setConfigValue("audit_chain_head", bytesToHex(chainHash));
    }

    private byte[] getLatestChainHash() throws SQLException {
        String hex = getConfigValue("audit_chain_head");
        if (hex == null || hex.isEmpty()) return null;
        return hexToBytes(hex);
    }

    private String getConfigValue(String key) throws SQLException {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT cfg_value FROM server_config WHERE cfg_key = ?")) {
            ps.setString(1, key);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void setConfigValue(String key, String value) throws SQLException {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "MERGE INTO server_config (cfg_key, cfg_value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Delete audit entries older than retentionDays and enforce maxEntries (Stage 2.5).
     */
    public void cleanupAuditLog(int retentionDays, int maxEntries) throws SQLException {
        try (Connection conn = dbManager.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM audit_log WHERE created_at < DATEADD('DAY', "
                + (-retentionDays) + ", CURRENT_TIMESTAMP)");
            stmt.execute("DELETE FROM audit_log WHERE id NOT IN ("
                + "SELECT id FROM audit_log ORDER BY created_at DESC LIMIT " + maxEntries + ")");
        }
    }

    /**
     * Get audit log entries (paginated).
     */
    public List<String> getAuditLog(int offset, int limit, String filterPlayer) throws SQLException {
        String sql = filterPlayer != null
            ? "SELECT * FROM audit_log WHERE target = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
            : "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<String> entries = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (filterPlayer != null) ps.setString(idx++, filterPlayer);
            ps.setInt(idx++, limit);
            ps.setInt(idx, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(String.format("[%s] %s %s -> %s (model:%s) %s",
                        rs.getTimestamp("created_at"),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("target"),
                        rs.getString("model_id"),
                        rs.getString("details") != null ? rs.getString("details") : ""));
                }
            }
        }
        return entries;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private ModelEntity mapRow(ResultSet rs, boolean includeData) throws SQLException {
        ModelEntity m = new ModelEntity();
        m.setId(rs.getLong("id"));
        m.setPlayerUuid(rs.getString("player_uuid"));
        m.setName(rs.getString("name"));
        m.setDescription(rs.getString("description"));
        m.setSizeBytes(rs.getInt("size_bytes"));
        m.setDefault(rs.getBoolean("is_default"));
        m.setForced(rs.getBoolean("is_forced"));
        try { m.setCloneable(rs.getBoolean("is_cloneable")); } catch (Exception ignored) {}
        m.setCreatedAt(rs.getTimestamp("created_at"));
        m.setUpdatedAt(rs.getTimestamp("updated_at"));
        if (includeData) {
            m.setDataEnc(rs.getBytes("data_enc"));
            m.setDataIv(rs.getBytes("data_iv"));
            m.setDataTag(rs.getBytes("data_tag"));
            m.setIconEnc(rs.getBytes("icon_enc"));
            m.setIconIv(rs.getBytes("icon_iv"));
            m.setIconTag(rs.getBytes("icon_tag"));
            m.setSha256(rs.getBytes("sha256"));
        }
        return m;
    }
}
