package com.tom.cpm.server.admin;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.tom.cpl.command.CommandCtx;
import com.tom.cpl.text.FormatText;
import com.tom.cpl.text.LiteralText;
import com.tom.cpm.server.db.DatabaseManager;
import com.tom.cpm.server.model.ModelEntity;
import com.tom.cpm.server.model.ModelRepository;
import com.tom.cpm.shared.util.Log;

/**
 * Implements /cpm admin ... subcommands.
 * All commands require OP level 2+ (enforced by the command registration).
 * Console use is supported (no player object needed for non-player-targeting commands).
 */
public final class AdminCommandHandler {

    private AdminCommandHandler() {}

    // ================================================================
    // Model management
    // ================================================================

    /** /cpm admin models list [player] */
    public static void listModels(CommandCtx<?> ctx, ModelRepository repo, DatabaseManager db) {
        try {
            String filterPlayer = ctx.getArgument("player");
            String filterUuid = null;

            if (filterPlayer != null) {
                filterUuid = resolvePlayerUuid(filterPlayer);
                if (filterUuid == null) {
                    ctx.fail(new FormatText("commands.cpm.admin.playerNotFound", filterPlayer));
                    return;
                }
            }

            List<ModelEntity> models;
            if (filterUuid != null) {
                models = repo.listModelsForPlayer(filterUuid);
            } else {
                models = repo.listAllModels(0, 50);
            }

            if (models.isEmpty()) {
                ctx.sendSuccess(new FormatText("commands.cpm.admin.noModels"));
                return;
            }

            ctx.sendSuccess(new FormatText("commands.cpm.admin.modelListHeader", models.size()));
            for (ModelEntity m : models) {
                String entry = String.format("  #%d %s | %s | %d KB | %s%s%s",
                    m.getId(),
                    truncate(m.getName(), 30),
                    truncate(m.getPlayerUuid(), 8),
                    m.getSizeBytes() / 1024,
                    m.isDefault() ? "[DEFAULT] " : "",
                    m.isForced() ? "[FORCED] " : "",
                    m.getCreatedAt() != null ? m.getCreatedAt().toString().substring(0, 10) : "");
                ctx.sendSuccess(new LiteralText(entry));
            }
        } catch (SQLException e) {
            Log.error("Admin listModels failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    /** /cpm admin models info <id> */
    public static void modelInfo(CommandCtx<?> ctx, ModelRepository repo) {
        try {
            long modelId = ctx.getArgument("id");
            // Load via repository
            ModelEntity m = null;
            for (ModelEntity e : repo.listAllModels(0, Integer.MAX_VALUE)) {
                if (e.getId() == modelId) { m = e; break; }
            }
            if (m == null) {
                ctx.fail(new FormatText("commands.cpm.admin.modelNotFound", modelId));
                return;
            }
            ctx.sendSuccess(new FormatText("commands.cpm.admin.modelInfo",
                m.getId(), m.getName(), m.getPlayerUuid(),
                m.getSizeBytes(), m.isDefault(), m.isForced(),
                m.getCreatedAt(), m.getUpdatedAt()));
        } catch (SQLException e) {
            Log.error("Admin modelInfo failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    /** /cpm admin models delete <id> [reason] */
    public static void deleteModel(CommandCtx<?> ctx, ModelRepository repo) {
        try {
            long modelId = ctx.getArgument("id");
            String reason = ctx.getArgument("reason");
            if (reason == null) reason = "Admin deletion";
            String actor = "CONSOLE"; // TODO: get actual admin UUID from context

            boolean deleted = repo.deleteModel(modelId, null, true);
            if (deleted) {
                repo.logAction(actor, "DELETE", null, modelId, reason, null);
                ctx.sendSuccess(new FormatText("commands.cpm.admin.modelDeleted", modelId));
            } else {
                ctx.fail(new FormatText("commands.cpm.admin.modelNotFound", modelId));
            }
        } catch (SQLException e) {
            Log.error("Admin deleteModel failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    /** /cpm admin models force <id> */
    public static void forceModel(CommandCtx<?> ctx, ModelRepository repo) {
        try {
            long modelId = ctx.getArgument("id");
            repo.setForced(modelId, true);
            repo.logAction("ADMIN", "FORCE", null, modelId, "Model forced by admin", null);
            ctx.sendSuccess(new FormatText("commands.cpm.admin.modelForced", modelId));
        } catch (SQLException e) {
            Log.error("Admin forceModel failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    /** /cpm admin models unforce <id> */
    public static void unforceModel(CommandCtx<?> ctx, ModelRepository repo) {
        try {
            long modelId = ctx.getArgument("id");
            repo.setForced(modelId, false);
            repo.logAction("ADMIN", "UNFORCE", null, modelId, "Force removed by admin", null);
            ctx.sendSuccess(new FormatText("commands.cpm.admin.modelUnforced", modelId));
        } catch (SQLException e) {
            Log.error("Admin unforceModel failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    /** /cpm admin models reassign <id> <uuid> */
    public static void reassignModel(CommandCtx<?> ctx, ModelRepository repo) {
        ctx.fail(new FormatText("commands.cpm.admin.notImplemented", "reassign"));
    }

    // ================================================================
    // Player management
    // ================================================================

    /** /cpm admin players block <uuid> */
    public static void blockPlayer(CommandCtx<?> ctx, ModelRepository repo) {
        try {
            String uuid = ctx.getArgument("uuid");
            repo.setPlayerBlocked(uuid, true);
            repo.logAction("ADMIN", "BLOCK", uuid, null, "Player blocked from uploading", null);
            ctx.sendSuccess(new FormatText("commands.cpm.admin.playerBlocked", uuid));
        } catch (SQLException e) {
            Log.error("Admin blockPlayer failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    /** /cpm admin players unblock <uuid> */
    public static void unblockPlayer(CommandCtx<?> ctx, ModelRepository repo) {
        try {
            String uuid = ctx.getArgument("uuid");
            repo.setPlayerBlocked(uuid, false);
            repo.logAction("ADMIN", "UNBLOCK", uuid, null, "Player unblocked", null);
            ctx.sendSuccess(new FormatText("commands.cpm.admin.playerUnblocked", uuid));
        } catch (SQLException e) {
            Log.error("Admin unblockPlayer failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    /** /cpm admin players info <uuid> */
    public static void playerInfo(CommandCtx<?> ctx, ModelRepository repo) {
        try {
            String uuid = ctx.getArgument("uuid");
            boolean blocked = repo.isPlayerBlocked(uuid);
            int modelCount = repo.listModelsForPlayer(uuid).size();
            ctx.sendSuccess(new FormatText("commands.cpm.admin.playerInfo",
                uuid, modelCount, blocked ? "BLOCKED" : "active"));
        } catch (SQLException e) {
            Log.error("Admin playerInfo failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    // ================================================================
    // Audit & Database
    // ================================================================

    /** /cpm admin audit [page] [player] */
    public static void viewAudit(CommandCtx<?> ctx, ModelRepository repo) {
        try {
            Integer pageArg = ctx.getArgument("page");
            int page = pageArg != null ? pageArg : 0;
            String filter = ctx.getArgument("player");
            List<String> entries = repo.getAuditLog(page * 10, 10, filter);
            if (entries.isEmpty()) {
                ctx.sendSuccess(new FormatText("commands.cpm.admin.auditEmpty"));
                return;
            }
            ctx.sendSuccess(new FormatText("commands.cpm.admin.auditHeader", page));
            for (String entry : entries) {
                ctx.sendSuccess(new LiteralText("  " + entry));
            }
        } catch (SQLException e) {
            Log.error("Admin viewAudit failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    /** /cpm admin db backup */
    public static void dbBackup(CommandCtx<?> ctx, DatabaseManager db) {
        try {
            java.io.File backupDir = new java.io.File("cpm_backups");
            db.backup(backupDir);
            ctx.sendSuccess(new FormatText("commands.cpm.admin.dbBackupOk"));
        } catch (SQLException e) {
            Log.error("Admin dbBackup failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbBackupFail", e.getMessage()));
        }
    }

    /** /cpm admin db stats */
    public static void dbStats(CommandCtx<?> ctx, DatabaseManager db, ModelRepository repo) {
        try {
            int totalModels = repo.countAllModels();
            String stats = db.getStats();
            ctx.sendSuccess(new FormatText("commands.cpm.admin.dbStats",
                totalModels, stats));
        } catch (SQLException e) {
            Log.error("Admin dbStats failed", e);
            ctx.fail(new FormatText("commands.cpm.admin.dbError"));
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private static String resolvePlayerUuid(String playerNameOrUuid) {
        // Try as UUID first
        try {
            UUID.fromString(playerNameOrUuid);
            return playerNameOrUuid;
        } catch (IllegalArgumentException e) {
            // Try to resolve from player name (offline UUID derivation)
        }
        // For offline mode: derive UUID from name
        return java.util.UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + playerNameOrUuid).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ).toString();
    }
}

