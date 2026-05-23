package com.tom.cpm.server;

import java.io.File;

import com.tom.cpl.config.ConfigEntry;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.util.Log;

/**
 * Validates and loads the CPM server configuration on startup.
 * Performs sanity checks on all settings before the server initializes.
 */
public final class CpmServerConfig {

    private CpmServerConfig() {}

    public record ValidatedConfig(
        boolean enabled,
        int maxModelSizeMb,
        boolean httpEnabled,
        int httpPort,
        String httpBindAddress,
        boolean tlsEnabled,
        String keystorePath,
        int chunkSizeKb,
        int chunkTimeoutSec,
        int sessionTimeoutMin,
        int timeWindowMinutes,
        int timeWindowSkew,
        boolean timeSyncOnJoin,
        String dbPath,
        boolean dbFileEncryption,
        boolean dbColumnEncryption,
        String adminUsername,
        boolean offlineEnabled,
        boolean offlineAllowUpload,
        boolean clientEncryptLocalModels,
        boolean encryptionEnabled
    ) {}

    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;
    private static final int MIN_CHUNK_KB = 1;
    private static final int MAX_CHUNK_KB = 31; // Must be < 32KB
    private static final int MIN_WINDOW_MIN = 5;
    private static final int MAX_WINDOW_MIN = 1440; // 24 hours
    private static final int MIN_MODEL_MB = 1;
    private static final int MAX_MODEL_MB = 100;

    /**
     * Load and validate the server configuration from cpm.json.
     * Logs warnings for suspicious settings and caps out-of-range values.
     */
    public static ValidatedConfig loadAndValidate(File configDir) {
        ConfigEntry cfg = ModConfig.getCommonConfig();
        StringBuilder warnings = new StringBuilder();

        boolean enabled = cfg.getBoolean("cpmServer.enabled", true);
        if (!enabled) {
            Log.info("CPM built-in server is disabled (cpmServer.enabled=false)");
            return null;
        }

        // Model size
        int maxModelMb = clampInt(cfg.getInt("cpmServer.maxModelSizeMb", 10),
            MIN_MODEL_MB, MAX_MODEL_MB,
            "maxModelSizeMb", warnings);

        // HTTP port
        boolean httpEnabled = cfg.getBoolean("cpmServer.httpPort.enabled", true);
        int httpPort = clampInt(cfg.getInt("cpmServer.httpPort.port", 8080),
            MIN_PORT, MAX_PORT, "httpPort.port", warnings);
        String httpBind = cfg.getString("cpmServer.httpPort.bindAddress", "127.0.0.1");
        if (!"127.0.0.1".equals(httpBind) && !"localhost".equals(httpBind)) {
            warnings.append("WARNING: Admin dashboard bind address is not localhost (")
                .append(httpBind).append("). Remote access enabled.\n");
        }

        // TLS
        boolean tlsEnabled = cfg.getBoolean("cpmServer.tls.enabled", false);
        String keystorePath = cfg.getString("cpmServer.tls.keystore", "cpm_keystore.jks");
        if (tlsEnabled) {
            File ks = new File(configDir, keystorePath);
            if (!ks.exists()) {
                warnings.append("WARNING: TLS keystore not found: ").append(ks.getAbsolutePath())
                    .append(". TLS will not work.\n");
            }
        }

        // Chunked transfer
        int chunkSizeKb = clampInt(cfg.getInt("cpmServer.transfer.chunkSizeKb", 30),
            MIN_CHUNK_KB, MAX_CHUNK_KB, "transfer.chunkSizeKb", warnings);
        int chunkTimeoutSec = clampInt(cfg.getInt("cpmServer.transfer.chunkTimeoutSec", 30),
            5, 300, "transfer.chunkTimeoutSec", warnings);
        int sessionTimeoutMin = clampInt(cfg.getInt("cpmServer.transfer.sessionTimeoutMin", 10),
            1, 60, "transfer.sessionTimeoutMin", warnings);

        // Time-bound crypto
        int timeWindowMin = clampInt(cfg.getInt("cpmServer.time.windowMinutes", 30),
            MIN_WINDOW_MIN, MAX_WINDOW_MIN, "time.windowMinutes", warnings);
        int timeWindowSkew = clampInt(cfg.getInt("cpmServer.time.windowSkew", 1),
            0, 5, "time.windowSkew", warnings);
        boolean timeSyncOnJoin = cfg.getBoolean("cpmServer.time.syncOnJoin", true);

        // Database
        String dbPath = cfg.getString("cpmServer.db.path", "cpm_db");
        boolean dbFileEncryption = cfg.getBoolean("cpmServer.db.fileEncryption", true);
        boolean dbColumnEncryption = cfg.getBoolean("cpmServer.db.columnEncryption", true);
        boolean encryptionEnabled = dbFileEncryption || dbColumnEncryption;

        if (!encryptionEnabled) {
            warnings.append("WARNING: Database encryption is disabled. Models stored in plaintext on disk.\n");
        }
        if (dbFileEncryption) {
            String dbPwd = cfg.getString("cpmServer.db.filePassword", "");
            if (dbPwd.isEmpty()) {
                warnings.append("WARNING: DB file password not set. Auto-generating. Store this safely.\n");
            }
        }

        // Admin
        String adminUser = cfg.getString("cpmServer.admin.username", "admin");
        String adminHash = cfg.getString("cpmServer.admin.passwordHash", "");
        if (adminHash.isEmpty() && httpEnabled) {
            warnings.append("WARNING: Admin password not set. Dashboard login will fail.\n");
        }

        // Offline mode
        boolean offlineEnabled = cfg.getBoolean("cpmServer.offlineMode.enabled", false);
        boolean offlineAllowUpload = cfg.getBoolean("cpmServer.offlineMode.allowUpload", true);

        // Client-side
        boolean clientEncrypt = cfg.getBoolean("cpmClient.encryptLocalModels", false);

        // Log warnings
        if (warnings.length() > 0) {
            Log.warn("CPM Server configuration warnings:\n" + warnings);
        }
        Log.info("CPM Server configuration validated successfully");

        return new ValidatedConfig(
            enabled, maxModelMb, httpEnabled, httpPort, httpBind,
            tlsEnabled, keystorePath,
            chunkSizeKb, chunkTimeoutSec, sessionTimeoutMin,
            timeWindowMin, timeWindowSkew, timeSyncOnJoin,
            dbPath, dbFileEncryption, dbColumnEncryption,
            adminUser, offlineEnabled, offlineAllowUpload,
            clientEncrypt, encryptionEnabled
        );
    }

    private static int clampInt(int value, int min, int max, String key, StringBuilder warnings) {
        if (value < min) {
            warnings.append("WARNING: ").append(key).append("=").append(value)
                .append(" below minimum ").append(min).append(", clamping.\n");
            return min;
        }
        if (value > max) {
            warnings.append("WARNING: ").append(key).append("=").append(value)
                .append(" above maximum ").append(max).append(", clamping.\n");
            return max;
        }
        return value;
    }
}
