package com.tom.cpm.server;

import java.io.File;

import com.tom.cpl.config.ConfigEntry;
import com.tom.cpl.config.ModConfigFile;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.util.Log;

/**
 * Validates and loads the CPM server configuration on startup.
 * Performs sanity checks on all settings before the server initializes.
 * Auto-generates cpm.json with secure defaults on first run.
 */
public final class CpmServerConfig {

    private CpmServerConfig() {}

    /**
     * Cached bcrypt hash of the default admin password "admin" (cost 12).
     * Computed lazily on first access. Null if bcrypt is unavailable.
     */
    private static volatile String defaultAdminHash;
    private static volatile boolean defaultAdminHashComputed;

    /**
     * Returns the bcrypt hash of "admin". Computed once, then cached.
     * Returns null if bcrypt library is not available at runtime.
     */
    public static synchronized String getDefaultAdminHash() {
        if (!defaultAdminHashComputed) {
            defaultAdminHashComputed = true;
            try {
                defaultAdminHash = com.tom.cpm.server.admin.AdminAuthFilter.hashPassword("admin");
            } catch (Throwable t) {
                Log.error("Failed to compute default admin password hash", t);
                defaultAdminHash = null;
            }
        }
        return defaultAdminHash;
    }

    /**
     * Check whether a stored bcrypt hash matches the default "admin" password.
     */
    public static boolean isDefaultAdminPassword(String storedHash) {
        return com.tom.cpm.server.admin.AdminAuthFilter.verifyPasswordHash("admin", storedHash);
    }

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
     * Ensure the cpm.json file exists. If not, create it with secure defaults
     * (including admin password hash for "admin").
     */
    public static void ensureConfigFile(File configDir) {
        File cfgFile = new File(configDir, "cpm.json");
        boolean newlyCreated = !cfgFile.exists();

        try {
            ModConfigFile cfg = ModConfig.getCommonConfig();
            if (!cfg.hasEntry("cpmServer.enabled")) {
                cfg.setBoolean("cpmServer.enabled", true);
            }

            // Admin credentials
            if (cfg.getString("cpmServer.admin.username", "").isEmpty()) {
                cfg.setString("cpmServer.admin.username", "admin");
            }
            if (cfg.getString("cpmServer.admin.passwordHash", "").isEmpty()) {
                String hash = getDefaultAdminHash();
                if (hash != null) {
                    cfg.setString("cpmServer.admin.passwordHash", hash);
                    if (newlyCreated) {
                        Log.info("Created default cpm.json with admin password set to: admin");
                    } else {
                        Log.info("Updated cpm.json with missing admin password hash (default password: admin)");
                    }
                    Log.warn("SECURITY: Change the admin dashboard password immediately after first login!");
                } else {
                    Log.warn("Could not compute admin password hash (bcrypt unavailable). Set cpmServer.admin.passwordHash manually.");
                }
            }

            // HTTP dashboard defaults
            if (!cfg.hasEntry("cpmServer.httpPort.enabled")) {
                cfg.setBoolean("cpmServer.httpPort.enabled", true);
            }
            if (!cfg.hasEntry("cpmServer.httpPort.port")) {
                cfg.setInt("cpmServer.httpPort.port", 8080);
            }
            if (!cfg.hasEntry("cpmServer.httpPort.bindAddress")) {
                cfg.setString("cpmServer.httpPort.bindAddress", "127.0.0.1");
            }

            // Database defaults
            if (!cfg.hasEntry("cpmServer.db.path")) {
                cfg.setString("cpmServer.db.path", "cpm_db");
            }
            if (!cfg.hasEntry("cpmServer.db.fileEncryption")) {
                cfg.setBoolean("cpmServer.db.fileEncryption", true);
            }
            if (!cfg.hasEntry("cpmServer.db.columnEncryption")) {
                cfg.setBoolean("cpmServer.db.columnEncryption", true);
            }

            // Transfer defaults
            if (!cfg.hasEntry("cpmServer.transfer.chunkSizeKb")) {
                cfg.setInt("cpmServer.transfer.chunkSizeKb", 30);
            }
            if (!cfg.hasEntry("cpmServer.transfer.chunkTimeoutSec")) {
                cfg.setInt("cpmServer.transfer.chunkTimeoutSec", 30);
            }
            if (!cfg.hasEntry("cpmServer.transfer.sessionTimeoutMin")) {
                cfg.setInt("cpmServer.transfer.sessionTimeoutMin", 10);
            }

            // Time-bound crypto defaults
            if (!cfg.hasEntry("cpmServer.time.windowMinutes")) {
                cfg.setInt("cpmServer.time.windowMinutes", 30);
            }
            if (!cfg.hasEntry("cpmServer.time.windowSkew")) {
                cfg.setInt("cpmServer.time.windowSkew", 1);
            }
            if (!cfg.hasEntry("cpmServer.time.syncOnJoin")) {
                cfg.setBoolean("cpmServer.time.syncOnJoin", true);
            }

            cfg.save();
        } catch (Throwable t) {
            Log.error("Failed to auto-generate cpm.json config file", t);
        }
    }

    /**
     * Load and validate the server configuration from cpm.json.
     * Logs warnings for suspicious settings and caps out-of-range values.
     */
    public static ValidatedConfig loadAndValidate(File configDir) {
        ensureConfigFile(configDir);

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
        if (httpEnabled && !adminHash.isEmpty() && isDefaultAdminPassword(adminHash)) {
            warnings.append("SECURITY: Admin password is still the default \"admin\". Change it immediately via the dashboard!\n");
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
