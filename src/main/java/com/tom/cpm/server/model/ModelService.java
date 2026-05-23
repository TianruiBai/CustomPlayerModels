package com.tom.cpm.server.model;

import java.io.File;
import java.sql.SQLException;

import com.tom.cpl.command.CommandCtx;
import com.tom.cpm.server.admin.AdminCommandHandler;
import com.tom.cpm.server.db.DatabaseManager;
import com.tom.cpm.shared.CommandCPM;
import com.tom.cpm.shared.CommandCPM.AdminCommandHook;
import com.tom.cpm.shared.util.Log;

/**
 * Business logic facade for model operations.
 * Wires the admin command hook to the database layer.
 * This is the main entry point for the server module.
 */
public class ModelService implements AdminCommandHook {

    private final ModelRepository repo;
    private final DatabaseManager db;

    public ModelService(ModelRepository repo, DatabaseManager db) {
        this.repo = repo;
        this.db = db;
    }

    /**
     * Register this service as the admin command handler.
     * Must be called during server initialization.
     */
    public void registerCommands() {
        CommandCPM.setAdminHook(this);
        Log.info("CPM Admin commands registered");
    }

    // ================================================================
    // AdminCommandHook implementation
    // ================================================================

    @Override
    public void listModels(CommandCtx<?> ctx) {
        AdminCommandHandler.listModels(ctx, repo, db);
    }

    @Override
    public void modelInfo(CommandCtx<?> ctx) {
        AdminCommandHandler.modelInfo(ctx, repo);
    }

    @Override
    public void deleteModel(CommandCtx<?> ctx) {
        AdminCommandHandler.deleteModel(ctx, repo);
    }

    @Override
    public void forceModel(CommandCtx<?> ctx) {
        AdminCommandHandler.forceModel(ctx, repo);
    }

    @Override
    public void unforceModel(CommandCtx<?> ctx) {
        AdminCommandHandler.unforceModel(ctx, repo);
    }

    @Override
    public void blockPlayer(CommandCtx<?> ctx) {
        AdminCommandHandler.blockPlayer(ctx, repo);
    }

    @Override
    public void unblockPlayer(CommandCtx<?> ctx) {
        AdminCommandHandler.unblockPlayer(ctx, repo);
    }

    @Override
    public void playerInfo(CommandCtx<?> ctx) {
        AdminCommandHandler.playerInfo(ctx, repo);
    }

    @Override
    public void viewAudit(CommandCtx<?> ctx) {
        AdminCommandHandler.viewAudit(ctx, repo);
    }

    @Override
    public void dbBackup(CommandCtx<?> ctx) {
        AdminCommandHandler.dbBackup(ctx, db);
    }

    @Override
    public void dbStats(CommandCtx<?> ctx) {
        AdminCommandHandler.dbStats(ctx, db, repo);
    }

    // ================================================================
    // Model operations for native port packet handlers
    // ================================================================

    /**
     * Store a model uploaded by a player.
     */
    public long storeModel(String playerUuid, String name, String desc,
                            byte[] modelData, byte[] iconData) throws SQLException {
        return repo.storeModel(playerUuid, name, desc, modelData, iconData);
    }

    /**
     * Get the default model for a player.
     */
    public long getDefaultModelId(String playerUuid) throws SQLException {
        for (ModelEntity m : repo.listModelsForPlayer(playerUuid)) {
            if (m.isDefault()) return m.getId();
        }
        return -1;
    }

    public ModelRepository getRepo() { return repo; }
    public DatabaseManager getDb() { return db; }
}
