package com.tom.cpm.server;

import java.io.File;
import java.sql.SQLException;

import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.server.crypto.KeyManager;
import com.tom.cpm.server.crypto.SessionKeyManager;
import com.tom.cpm.server.crypto.TimeBoundKeyManager;
import com.tom.cpm.server.db.DatabaseManager;
import com.tom.cpm.server.db.MigrationManager;
import com.tom.cpm.server.model.ModelRepository;
import com.tom.cpm.server.model.ModelService;
import com.tom.cpm.server.model.ModelValidator;
import com.tom.cpm.server.transfer.ChunkedReceiver;
import com.tom.cpm.server.transfer.TransferResumeManager;
import com.tom.cpm.shared.util.Log;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Server lifecycle handler for the CPM built-in model server.
 * Initializes the database, crypto, and model service on server start.
 * Cleans up on server stop.
 */
public class CpmServerInit {

    private static CpmServerInit INSTANCE;

    private final CryptoService crypto;
    private final TimeBoundKeyManager timeKeyManager;
    private final SessionKeyManager sessionKeyManager;
    private final DatabaseManager dbManager;
    private final KeyManager keyManager;
    private final ModelRepository repo;
    private final ModelService modelService;
    private final ModelValidator validator;
    private final ChunkedReceiver chunkedReceiver;
    private final TransferResumeManager transferResumeManager;
    private final CpmModelHttpServer httpServer;
    private boolean initialized;

    public CpmServerInit(CpmServerConfig.ValidatedConfig config) {
        this.crypto = new CryptoService();

        // Validate config
        if (config == null || !config.enabled()) {
            this.timeKeyManager = null;
            this.sessionKeyManager = null;
            this.dbManager = null;
            this.keyManager = null;
            this.repo = null;
            this.modelService = null;
            this.validator = null;
            this.chunkedReceiver = null;
            this.transferResumeManager = null;
            this.httpServer = null;
            return;
        }

        // Initialize time-bound key manager
        this.timeKeyManager = new TimeBoundKeyManager(crypto,
            config.timeWindowMinutes(), config.timeWindowSkew());
        this.sessionKeyManager = new SessionKeyManager(crypto, timeKeyManager);

        // Initialize keystore
        File serverDir = FMLPaths.GAMEDIR.get().resolve("cpm").toFile();
        serverDir.mkdirs();
        File pwdFile = new File(serverDir, "cpm_keystore.pwd");
        this.keyManager = new KeyManager(serverDir, crypto);
        try {
            char[] ksPassword;
            if (pwdFile.exists()) {
                // Load persisted password
                byte[] pwdBytes = java.nio.file.Files.readAllBytes(pwdFile.toPath());
                ksPassword = new String(pwdBytes, java.nio.charset.StandardCharsets.UTF_8).trim().toCharArray();
                com.tom.cpm.server.crypto.MemoryProtector.wipe(pwdBytes);
            } else {
                // First run: generate and persist
                ksPassword = java.util.Base64.getEncoder().encodeToString(
                    crypto.secureRandom(32)).toCharArray();
                java.nio.file.Files.write(pwdFile.toPath(),
                    new String(ksPassword).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                // Restrict permissions (best-effort on Windows)
                pwdFile.setReadable(true, false); // owner only
            }
            keyManager.initialize(ksPassword);
            com.tom.cpm.server.crypto.MemoryProtector.wipe(ksPassword);
        } catch (Throwable e) {
            Log.error("Failed to initialize keystore", e);
            this.dbManager = null;
            this.repo = null;
            this.modelService = null;
            this.validator = null;
            this.chunkedReceiver = null;
            this.transferResumeManager = null;
            this.httpServer = null;
            return;
        }

        // Initialize database
        File dbDir = new File(FMLPaths.GAMEDIR.get().resolve("cpm").toFile(), config.dbPath());
        String dbPassword = keyManager.getDbFilePassword();
        MigrationManager migrationManager = new MigrationManager();
        this.dbManager = new DatabaseManager(dbDir, dbPassword,
            config.dbFileEncryption(), migrationManager);

        try {
            dbManager.initialize();
        } catch (Throwable e) {
            Log.error("Failed to initialize database", e);
            this.repo = null;
            this.modelService = null;
            this.validator = null;
            this.chunkedReceiver = null;
            this.transferResumeManager = null;
            this.httpServer = null;
            return;
        }

        // Initialize repositories and services
        this.repo = new ModelRepository(dbManager, crypto, keyManager.getDbMasterKey());
        this.validator = new ModelValidator(config.maxModelSizeMb(), 256, 512);
        this.modelService = new ModelService(repo, dbManager);

        // Chunked transfer infrastructure
        this.transferResumeManager = new TransferResumeManager();
        this.chunkedReceiver = new ChunkedReceiver(sessionKeyManager, crypto, repo, validator);

        // Register admin commands
        modelService.registerCommands();

        // Initialize HTTP admin dashboard (optional)
        if (config.httpEnabled()) {
            CpmModelHttpServer localHttpServer = null;
            try {
                String jwtSecret = config.adminUsername() + System.currentTimeMillis();
                localHttpServer = new CpmModelHttpServer(config.httpBindAddress(),
                    config.httpPort(), modelService, jwtSecret.toCharArray());
                localHttpServer.startServer();
            } catch (Throwable t) {
                Log.error("CPM admin HTTP dashboard failed to start. Continuing without HTTP dashboard.", t);
                localHttpServer = null;
            }
            this.httpServer = localHttpServer;
        } else {
            this.httpServer = null;
        }

        this.initialized = true;
        Log.info("CPM Built-in Model Server initialized successfully");
    }

    public void onServerStopping() {
        if (httpServer != null) {
            httpServer.stopServer();
        }
        if (sessionKeyManager != null) {
            sessionKeyManager.shutdown();
        }
        if (dbManager != null) {
            dbManager.close();
        }
        initialized = false;
        Log.info("CPM Built-in Model Server shut down");
    }

    public static CpmServerInit get() { return INSTANCE; }

    public ModelService getModelService() { return modelService; }
    public SessionKeyManager getSessionKeyManager() { return sessionKeyManager; }
    public CryptoService getCryptoService() { return crypto; }
    public boolean isInitialized() { return initialized; }
    public ChunkedReceiver getChunkedReceiver() { return chunkedReceiver; }
    public TransferResumeManager getTransferResumeManager() { return transferResumeManager; }

    /**
     * Initialize from the CPM server config. Called during server start.
     */
    public static void init() {
        File configDir = FMLPaths.CONFIGDIR.get().toFile();
        CpmServerConfig.ValidatedConfig config = CpmServerConfig.loadAndValidate(configDir);
        INSTANCE = new CpmServerInit(config);
    }

    /**
     * Cleanup on server stop.
     */
    public static void shutdown() {
        if (INSTANCE != null) {
            INSTANCE.onServerStopping();
            INSTANCE = null;
        }
    }
}
