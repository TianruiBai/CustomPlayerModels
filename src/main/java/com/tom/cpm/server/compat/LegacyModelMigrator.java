package com.tom.cpm.server.compat;

import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;

import com.tom.cpl.config.ConfigEntry;
import com.tom.cpm.server.model.ModelRepository;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.util.Log;

/**
 * Migrates player models from the legacy JSON-based world config storage
 * to the new H2 database. Runs once on first startup after the update.
 * 
 * Legacy format (in world's cpm.json):
 *   skins: {
 *     "<uuid>": {
 *       "model": "<base64-encoded model data>",
 *       "forced": true/false
 *     }
 *   }
 * 
 * Migration is idempotent: models already in the DB are skipped.
 */
public class LegacyModelMigrator {

    private final ModelRepository repo;

    public LegacyModelMigrator(ModelRepository repo) {
        this.repo = repo;
    }

    /**
     * Check if migration is needed and run it.
     * 
     * @return number of models migrated
     */
    public int migrateIfNeeded() {
        ConfigEntry worldCfg = ModConfig.getWorldConfig();
        if (worldCfg == null) {
            Log.info("No world config available — skipping legacy migration");
            return 0;
        }

        ConfigEntry skinsEntry = worldCfg.getEntry(ConfigKeys.SERVER_SKINS);
        Map<String, Object> rawData = getRawEntries(skinsEntry);
        if (rawData == null || rawData.isEmpty()) {
            Log.info("No legacy models found — migration not needed");
            return 0;
        }

        Log.info("Found " + rawData.size() + " legacy model entries. Starting migration...");
        int migrated = 0;
        int skipped = 0;
        int errors = 0;

        for (Map.Entry<String, Object> entry : rawData.entrySet()) {
            String uuid = entry.getKey();
            try {
                // Check if player already has models in DB
                if (!repo.listModelsForPlayer(uuid).isEmpty()) {
                    skipped++;
                    continue;
                }

                // Extract legacy data
                ConfigEntry playerEntry = skinsEntry.getEntry(uuid);
                String b64Model = playerEntry.getString(ConfigKeys.MODEL, null);
                boolean forced = playerEntry.getBoolean(ConfigKeys.FORCED, false);

                if (b64Model == null || b64Model.isEmpty()) {
                    skipped++;
                    continue;
                }

                // Decode and store
                byte[] modelData = Base64.getDecoder().decode(b64Model);

                // Validate basic header
                if (modelData.length < 10 || modelData[0] != 0x53) {
                    Log.warn("Skipping legacy model for " + uuid +
                        ": invalid header (not a CPM model file)");
                    skipped++;
                    continue;
                }

                long modelId = repo.storeModel(uuid, "Legacy Model", "Migrated from JSON storage",
                    modelData, null);

                // Apply forced status if it was forced
                if (forced) {
                    repo.setForced(modelId, true);
                }

                // Mark as default since it was their only model
                repo.setDefaultModel(uuid, modelId);

                // Register player
                repo.upsertPlayer(uuid, "migrated_player");

                migrated++;
                Log.info("Migrated legacy model for " + uuid + " -> modelId=" + modelId);

                // Clear legacy entry after successful migration
                skinsEntry.clearValue(uuid);

            } catch (Exception e) {
                errors++;
                Log.error("Failed to migrate legacy model for " + uuid, e);
            }
        }

        // Save world config to persist the cleared entries
        ModConfig.getWorldConfig().save();

        Log.info("Legacy migration complete: " + migrated + " migrated, " +
            skipped + " skipped, " + errors + " errors");
        return migrated;
    }

    /**
     * Get the raw entries from a ConfigEntry as a Map.
     * Uses reflection since ConfigEntry doesn't expose its internal map directly.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getRawEntries(ConfigEntry entry) {
        // Try reflection to access the internal data map
        try {
            java.lang.reflect.Field dataField = ConfigEntry.class.getDeclaredField("data");
            dataField.setAccessible(true);
            Object data = dataField.get(entry);
            if (data instanceof Map) {
                return (Map<String, Object>) data;
            }
        } catch (NoSuchFieldException e) {
            // Fallback: ConfigEntry may have renamed the field in a newer version.
            // Try common alternative field names.
            for (String name : new String[]{"entries", "values", "map", "configData"}) {
                try {
                    java.lang.reflect.Field f = ConfigEntry.class.getDeclaredField(name);
                    f.setAccessible(true);
                    Object data = f.get(entry);
                    if (data instanceof Map) {
                        return (Map<String, Object>) data;
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }
            Log.warn("Could not access ConfigEntry internal map: field not found");
        } catch (Exception e) {
            Log.warn("Could not read legacy config entries: " + e.getMessage());
        }
        return java.util.Collections.emptyMap();
    }
}
