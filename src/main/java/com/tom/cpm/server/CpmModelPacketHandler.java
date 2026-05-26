package com.tom.cpm.server;

import java.util.Arrays;
import java.util.UUID;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpl.nbt.NBTTagList;
import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.server.crypto.EncryptedModelBlob;
import com.tom.cpm.server.crypto.SessionKeyManager;
import com.tom.cpm.server.model.ModelEntity;
import com.tom.cpm.server.model.ModelService;
import com.tom.cpm.server.security.RateLimitFilter;
import com.tom.cpm.server.transfer.ChunkedReceiver;
import com.tom.cpm.server.transfer.ChunkedReceiver.CompleteResult;
import com.tom.cpm.server.transfer.ChunkedReceiver.InitResult;
import com.tom.cpm.server.transfer.TransferResumeManager;
import com.tom.cpm.shared.config.PlayerData;
import com.tom.cpm.shared.network.IModelServerHandler;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.network.packet.ModelDataChunkAckS2C;
import com.tom.cpm.shared.network.packet.ModelDeleteResultS2C;
import com.tom.cpm.shared.network.packet.ModelDownloadChunkS2C;
import com.tom.cpm.shared.network.packet.ModelListResS2C;
import com.tom.cpm.shared.network.packet.ModelUpdateResultS2C;
import com.tom.cpm.shared.network.packet.ModelUploadInitAckS2C;
import com.tom.cpm.shared.network.packet.ModelUploadResumeAckS2C;
import com.tom.cpm.shared.network.packet.ModelUploadResultS2C;
import com.tom.cpm.shared.util.Log;

/**
 * Dispatches CPM model server packets to the appropriate backend services.
 * Handles: model list, chunked upload, download, set active/default, delete, update.
 * All S2C responses are sent back through the native Minecraft port.
 */
public class CpmModelPacketHandler implements IModelServerHandler {

    private final ModelService modelService;
    private final ChunkedReceiver chunkedReceiver;
    private final TransferResumeManager resumeManager;
    private final CryptoService cryptoService;
    private final SessionKeyManager sessionKeyManager;

    public CpmModelPacketHandler(ModelService modelService,
                                  ChunkedReceiver chunkedReceiver,
                                  TransferResumeManager resumeManager,
                                  CryptoService cryptoService,
                                  SessionKeyManager sessionKeyManager) {
        this.modelService = modelService;
        this.chunkedReceiver = chunkedReceiver;
        this.resumeManager = resumeManager;
        this.cryptoService = cryptoService;
        this.sessionKeyManager = sessionKeyManager;
    }

    // ---- Model List ----

    @Override
    public <P> void handleModelList(NetHandler<?, P, ?> handler, ServerNetH net, P player) {
        UUID uuid = handler.resolvePlayerUUID(player);
        Log.debug("Model list requested by " + uuid);

        // Rate limit check (Stage 2.3)
        if (!RateLimitFilter.allow(uuid, RateLimitFilter.Action.LIST, handler.isAdmin(player))) {
            Log.warn("Rate limit exceeded: model list by " + uuid);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("total", 0);
            tag.setTag("models", new NBTTagList());
            handler.sendPacketTo(net, new ModelListResS2C(tag));
            return;
        }

        try {
            java.util.List<ModelEntity> models = modelService.getRepo().listModelsForPlayer(uuid.toString());
            NBTTagCompound tag = new NBTTagCompound();
            NBTTagList list = new NBTTagList();
            for (ModelEntity m : models) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setLong("id", m.getId());
                entry.setString("name", m.getName());
                entry.setString("desc", m.getDescription() != null ? m.getDescription() : "");
                entry.setInteger("size", m.getSizeBytes());
                entry.setBoolean("default", m.isDefault());
                entry.setBoolean("forced", m.isForced());
                entry.setLong("created", m.getCreatedAt() != null ?
                    m.getCreatedAt().getTime() : 0L);
                // Load icon data
                try {
                    byte[] iconData = modelService.getRepo().loadModelIcon(m.getId());
                    if (iconData != null && iconData.length > 0) {
                        entry.setByteArray("icon", iconData);
                    }
                } catch (Exception e) {
                    Log.debug("Failed to load icon for model " + m.getId() + ": " + e.getMessage());
                }
                list.appendTag(entry);
            }
            tag.setTag("models", list);
            tag.setInteger("total", models.size());
            handler.sendPacketTo(net, new ModelListResS2C(tag));
        } catch (Exception e) {
            Log.error("Failed to build model list for " + uuid, e);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("total", 0);
            tag.setTag("models", new NBTTagList());
            handler.sendPacketTo(net, new ModelListResS2C(tag));
        }
    }

    // ---- Chunked Upload ----

    @Override
    public <P> void handleUploadInit(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                      NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);

        // Rate limit check (Stage 2.3)
        if (!RateLimitFilter.allow(uuid, RateLimitFilter.Action.UPLOAD_INIT, handler.isAdmin(player))) {
            Log.warn("Rate limit exceeded: upload init by " + uuid);
            NBTTagCompound ack = new NBTTagCompound();
            ack.setString("uid", "");
            ack.setBoolean("accepted", false);
            ack.setString("reason", "Too many upload requests — please wait");
            handler.sendPacketTo(net, new ModelUploadInitAckS2C(ack));
            return;
        }
        String username = player instanceof net.minecraft.world.entity.player.Player p
            ? p.getGameProfile().getName()
            : uuid.toString();
        String modelName = tag.getString("name");
        String modelDesc = tag.getString("desc");
        int totalSize = tag.getInteger("size");
        int numChunks = tag.getInteger("chunks");
        byte[] sha256 = tag.getByteArray("sha256");
        byte[] iconData = tag.hasKey("icon") ? tag.getByteArray("icon") : null;

        try {
            modelService.getRepo().upsertPlayer(uuid.toString(), username);
        } catch (Exception e) {
            Log.error("Failed to register player before upload init: " + uuid, e);
            NBTTagCompound ack = new NBTTagCompound();
            ack.setString("uid", "");
            ack.setBoolean("accepted", false);
            ack.setString("reason", "Unable to register player");
            handler.sendPacketTo(net, new ModelUploadInitAckS2C(ack));
            return;
        }

        InitResult result = chunkedReceiver.initUpload(uuid, modelName, modelDesc,
            totalSize, numChunks, sha256, iconData);

        NBTTagCompound ack = new NBTTagCompound();
        ack.setString("uid", result.uploadId());
        ack.setBoolean("accepted", result.accepted());
        if (!result.accepted()) {
            ack.setString("reason", result.rejectReason() != null ? result.rejectReason() : "Rejected");
        }
        handler.sendPacketTo(net, new ModelUploadInitAckS2C(ack));

        if (result.accepted()) {
            resumeManager.registerUpload(result.uploadId(), uuid, modelName,
                numChunks, -1);
            Log.info("Upload init accepted: uid=" + result.uploadId() +
                " player=" + uuid + " name=" + modelName);
        }
    }

    @Override
    public <P> void handleDataChunk(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                     NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        String uploadId = tag.getString("uid");
        int chunkIdx = tag.getInteger("idx");
        int totalChunks = tag.getInteger("total");
        byte[] data = tag.getByteArray("data");
        byte[] iv = tag.getByteArray("iv");
        byte[] gcmTag = tag.getByteArray("gtag");
        byte[] chunkSha256 = tag.getByteArray("csha");
        byte[] fullSha256 = tag.getByteArray("fsha");

        var ack = chunkedReceiver.receiveChunk(uploadId, chunkIdx, totalChunks,
            data, iv, gcmTag, chunkSha256, fullSha256, uuid);

        NBTTagCompound resp = new NBTTagCompound();
        resp.setString("uid", uploadId);
        resp.setInteger("idx", chunkIdx);
        resp.setBoolean("ok", ack.ok());
        if (!ack.ok()) {
            resp.setString("msg", ack.message() != null ? ack.message() : "Retry");
        }
        handler.sendPacketTo(net, new ModelDataChunkAckS2C(resp));

        if (ack.ok()) {
            resumeManager.updateProgress(uploadId, chunkIdx);
        }
    }

    @Override
    public <P> void handleUploadComplete(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                          NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        String uploadId = tag.getString("uid");
        byte[] fullSha256 = tag.getByteArray("fsha");

        CompleteResult result = chunkedReceiver.completeUpload(uploadId, fullSha256, uuid);

        NBTTagCompound resp = new NBTTagCompound();
        resp.setString("uid", uploadId);
        resp.setBoolean("ok", result.success());
        resp.setString("status", result.status() != null ? result.status() : "UNKNOWN");
        resp.setLong("mid", result.modelId());
        if (!result.success() && result.message() != null) {
            resp.setString("msg", result.message());
        }
        handler.sendPacketTo(net, new ModelUploadResultS2C(resp));

        if (result.success()) {
            // Stage 2: Detect cloneable/UUID-lock flags from uploaded model data
            try {
                EncryptedModelBlob blob = modelService.getRepo().loadModelBlob(result.modelId());
                if (blob != null) {
                    byte[] modelData = blob.getDecrypted();
                    try {
                        boolean cloneable = detectCloneable(modelData);
                        if (cloneable) {
                            modelService.getRepo().setCloneable(result.modelId(), true);
                            Log.info("Model marked as cloneable: modelId=" + result.modelId());
                        }
                    } finally {
                        com.tom.cpm.server.crypto.MemoryProtector.wipe(modelData);
                    }
                }
            } catch (Exception e) {
                Log.debug("Failed to detect cloneable flag for model " + result.modelId() + ": " + e.getMessage());
            }

            // Log upload with IP
            String ip = handler.getPlayerIp(player);
            try {
                modelService.getRepo().logAction(uuid.toString(), "UPLOAD", uuid.toString(),
                    result.modelId(), "Model uploaded", ip);
            } catch (Exception e) {
                Log.debug("Failed to log upload audit: " + e.getMessage());
            }

            resumeManager.removeUpload(uploadId);
            Log.info("Model upload complete: uploadId=" + uploadId +
                " modelId=" + result.modelId());
        }
    }

    @Override
    public <P> void handleUploadCancel(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                        NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        String uploadId = tag.getString("uid");
        chunkedReceiver.cancelUpload(uploadId, uuid);
        resumeManager.removeUpload(uploadId);
        Log.debug("Upload cancelled: uid=" + uploadId + " player=" + uuid);
    }

    @Override
    public <P> void handleUploadResume(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                        NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        String uploadId = tag.getString("uid");
        int lastChunk = chunkedReceiver.getLastReceivedChunk(uploadId, uuid);

        NBTTagCompound resp = new NBTTagCompound();
        resp.setString("uid", uploadId);
        resp.setInteger("lastChunk", lastChunk);
        resp.setBoolean("canResume", lastChunk >= 0);
        handler.sendPacketTo(net, new ModelUploadResumeAckS2C(resp));

        Log.debug("Upload resume: uid=" + uploadId + " lastChunk=" + lastChunk);
    }

    // ---- Authorization Helpers (Stage 2 Security) ----

    /**
     * Authorization check for model DATA access (download, set active).
     * 
     * Rules:
     * 1. Owner UUID match → allowed (full access to own models)
     * 2. Model is_forced → allowed (admin has made it public)
     * 3. Model is_cloneable → allowed (owner marked it as cloneable in editor)
     * 4. Admin → DENIED for data access (admins can only force/delete, not view content)
     * 5. Otherwise → denied
     * 
     * @return ModelEntity metadata if access is allowed, null if denied or not found
     */
    private ModelEntity canAccessModel(UUID playerUuid, long modelId) {
        try {
            ModelEntity meta = modelService.getRepo().getModelMetadata(modelId);
            if (meta == null) return null; // Not found — silent, don't leak existence

            // Owner check — full access
            if (meta.getPlayerUuid().equals(playerUuid.toString())) return meta;

            // Forced (admin-public) model — anyone can download
            if (meta.isForced()) return meta;

            // Cloneable model (owner marked it as shareable) — anyone can download
            if (meta.isCloneable()) return meta;

            // Access denied — log the attempt
            String ip = "unknown";
            modelService.getRepo().logAction(
                playerUuid.toString(), "ACCESS_DENIED",
                meta.getPlayerUuid(), modelId,
                "Unauthorized model data access attempt", ip
            );
            return null;
        } catch (Exception e) {
            Log.error("Authorization check failed for model " + modelId, e);
            return null;
        }
    }

    /**
     * Authorization for admin-level operations (force, delete).
     * Returns true if the player is the model owner OR has OP ≥ 2.
     */
    private <P> boolean canAdminOperateModel(UUID playerUuid, long modelId, 
                                              boolean isAdmin, NetHandler<?, P, ?> handler) {
        if (isAdmin) return true;
        try {
            ModelEntity meta = modelService.getRepo().getModelMetadata(modelId);
            return meta != null && meta.getPlayerUuid().equals(playerUuid.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Detect cloneable/UUID-lock flags from raw model data.
     * Parses the CPM model binary header to find ModelPartCloneable and ModelPartUUIDLockout markers.
     * 
     * @return true if the model contains a cloneable part (and no UUID lock)
     */
    private boolean detectCloneable(byte[] modelData) {
        if (modelData == null || modelData.length < 4) return false;
        try {
            // CPM model format: HEADER(0x53) + [parts...]
            // ModelPartCloneable type = 0x09, ModelPartUUIDLockout type = 0x0A
            // Simple scan for part type markers after the header
            boolean hasCloneable = false;
            boolean hasUuidLock = false;
            int len = modelData.length;
            // Scan after header byte
            for (int i = 1; i < len - 1; i++) {
                byte b = modelData[i];
                if (b == 0x09) hasCloneable = true; // CLONEABLE part type
                if (b == 0x0A) hasUuidLock = true;   // UUID_LOCK part type
            }
            // Cloneable only counts if there's no UUID lock contradicting it
            return hasCloneable && !hasUuidLock;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Model Download ----

    @Override
    public <P> void handleDownload(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                    NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        long modelId = tag.getLong("mid");

        // Rate limit check (Stage 2.3)
        if (!RateLimitFilter.allow(uuid, RateLimitFilter.Action.DOWNLOAD, handler.isAdmin(player))) {
            Log.warn("Rate limit exceeded: download by " + uuid);
            NBTTagCompound resp = new NBTTagCompound();
            resp.setLong("mid", modelId);
            resp.setInteger("idx", -2); // -2 = rate limited
            resp.setInteger("total", 0);
            resp.setByteArray("data", new byte[0]);
            handler.sendPacketTo(net, new ModelDownloadChunkS2C(resp));
            return;
        }

        // Authorization check (Stage 2.1)
        ModelEntity meta = canAccessModel(uuid, modelId);
        if (meta == null) {
            NBTTagCompound resp = new NBTTagCompound();
            resp.setLong("mid", modelId);
            resp.setInteger("idx", -1);
            resp.setInteger("total", 0);
            resp.setByteArray("data", new byte[0]);
            handler.sendPacketTo(net, new ModelDownloadChunkS2C(resp));
            return;
        }

        try {
            EncryptedModelBlob blob = modelService.getRepo().loadModelBlob(modelId);
            if (blob == null) {
                NBTTagCompound resp = new NBTTagCompound();
                resp.setLong("mid", modelId);
                resp.setInteger("idx", -1);
                resp.setInteger("total", 0);
                resp.setByteArray("data", new byte[0]);
                handler.sendPacketTo(net, new ModelDownloadChunkS2C(resp));
                return;
            }

            byte[] modelData = blob.getDecrypted();
            try {
                int chunkSize = 30_720; // 30 KB chunks
                int totalChunks = (modelData.length + chunkSize - 1) / chunkSize;

                NBTTagCompound resp = new NBTTagCompound();
                resp.setLong("mid", modelId);
                resp.setInteger("idx", 0);
                resp.setInteger("total", 1);
                resp.setByteArray("data", modelData);
                handler.sendPacketTo(net, new ModelDownloadChunkS2C(resp));

                Log.info("Model download served: modelId=" + modelId + " size=" + modelData.length
                    + " to=" + uuid);
            } finally {
                com.tom.cpm.server.crypto.MemoryProtector.wipe(modelData);
            }
        } catch (Exception e) {
            Log.error("Failed to serve model download: modelId=" + modelId, e);
        }
    }

    // ---- Model Actions ----

    @Override
    public <P> void handleSetActive(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                     NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        long modelId = tag.getLong("mid");
        if (modelId <= 0) modelId = tag.getLong("modelId");
        Log.info("Set active model: player=" + uuid + " modelId=" + modelId);

        // Only owner can set their own model active (Stage 2.1)
        ModelEntity meta = canAccessModel(uuid, modelId);
        if (meta == null) {
            Log.warn("Set active denied: player=" + uuid + " modelId=" + modelId);
            return;
        }

        try {
            EncryptedModelBlob blob = modelService.getRepo().loadModelBlob(modelId);
            if (blob != null) {
                byte[] modelData = blob.getDecrypted();
                try {
                    // Set active server models as a normal saved selection. Admin force is
                    // represented by the model row's is_forced flag, not by Set Active.
                    handler.setSkin((P) player, modelData, false, true);
                    Log.info("Active model set: player=" + uuid + " modelId=" + modelId + " size=" + modelData.length);
                } finally {
                    com.tom.cpm.server.crypto.MemoryProtector.wipe(modelData);
                }
            } else {
                Log.warn("Model not found for setActive: modelId=" + modelId);
            }
        } catch (Exception e) {
            Log.error("Failed to set active model", e);
        }
    }

    @Override
    public <P> void handleSetDefault(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                      NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        long modelId = tag.getLong("mid");
        if (modelId <= 0) modelId = tag.getLong("modelId");

        // Only owner can set default (Stage 2.1)
        ModelEntity meta = canAccessModel(uuid, modelId);
        if (meta == null) {
            Log.warn("Set default denied: player=" + uuid + " modelId=" + modelId);
            return;
        }

        try {
            modelService.getRepo().setDefaultModel(uuid.toString(), modelId);
            Log.info("Set default model: player=" + uuid + " modelId=" + modelId);
        } catch (Exception e) {
            Log.error("Failed to set default model", e);
        }
    }

    @Override
    public <P> void handleDelete(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                  NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        long modelId = tag.getLong("mid");
        if (modelId <= 0) modelId = tag.getLong("modelId");
        boolean isAdmin = handler.isAdmin(player);

        NBTTagCompound resp = new NBTTagCompound();
        resp.setLong("mid", modelId);

        // Owner or admin can delete (Stage 2.1)
        if (!canAdminOperateModel(uuid, modelId, isAdmin, handler)) {
            resp.setBoolean("ok", false);
            resp.setString("status", "ACCESS_DENIED");
            resp.setString("msg", "You can only delete your own models");
            handler.sendPacketTo(net, new ModelDeleteResultS2C(resp));
            Log.warn("Delete denied: player=" + uuid + " modelId=" + modelId);
            return;
        }

        byte[] deletedModelData = null;
        try {
            EncryptedModelBlob blob = modelService.getRepo().loadModelBlob(modelId);
            if (blob != null) deletedModelData = blob.getDecrypted();
            boolean ok = modelService.getRepo().deleteModel(modelId, uuid.toString(), isAdmin);
            resp.setBoolean("ok", ok);
            resp.setString("status", ok ? "OK" : "NOT_FOUND");

            if (ok) {
                PlayerData playerData = handler.getSNetH((P) player).cpm$getEncodedModelData();
                if (deletedModelData != null && Arrays.equals(playerData.data, deletedModelData)) {
                    handler.setSkin((P) player, (byte[]) null, false, true);
                    Log.info("Cleared active model after delete: modelId=" + modelId + " player=" + uuid);
                }
                String actor = isAdmin ? "ADMIN:" + uuid : uuid.toString();
                String details = isAdmin ? "Deleted by admin" : "Deleted by owner";
                String ip = handler.getPlayerIp(player);
                modelService.getRepo().logAction(actor, "DELETE", uuid.toString(),
                    modelId, details, ip);
                Log.info("Model deleted: modelId=" + modelId + " by=" + actor);
            }
        } catch (Exception e) {
            Log.error("Failed to delete model", e);
            resp.setBoolean("ok", false);
            resp.setString("status", "ERROR");
            resp.setString("msg", e.getMessage());
        } finally {
            if (deletedModelData != null) com.tom.cpm.server.crypto.MemoryProtector.wipe(deletedModelData);
        }
        handler.sendPacketTo(net, new ModelDeleteResultS2C(resp));
    }

    // ---- Model Update (single-packet, ≤30KB) ----

    @Override
    public <P> void handleModelUpdate(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                       NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        long modelId = tag.getLong("mid");
        byte[] encryptedData = tag.getByteArray("data");
        byte[] dataIv = tag.getByteArray("iv");
        byte[] dataTag = tag.getByteArray("gtag");
        long windowId = tag.getLong("twid");
        long msgCounter = tag.getLong("ctr");

        NBTTagCompound resp = new NBTTagCompound();
        resp.setLong("mid", modelId);

        try {
            // Decrypt the model data using the player's time-window session key
            javax.crypto.SecretKey key = sessionKeyManager.getEncryptionKey(uuid, windowId);
            // Combine data || tag for decryptAesGcm which expects iv || ciphertext || tag
            byte[] combined = new byte[dataIv.length + encryptedData.length + dataTag.length];
            System.arraycopy(dataIv, 0, combined, 0, dataIv.length);
            System.arraycopy(encryptedData, 0, combined, dataIv.length, encryptedData.length);
            System.arraycopy(dataTag, 0, combined, dataIv.length + encryptedData.length, dataTag.length);
            byte[] modelData = cryptoService.decryptAesGcm(combined, key);
            if (modelData == null) {
                resp.setBoolean("ok", false);
                resp.setString("status", "DECRYPT_FAILED");
                resp.setString("msg", "Failed to decrypt model data — possible time-window mismatch");
                handler.sendPacketTo(net, new ModelUpdateResultS2C(resp));
                return;
            }

            try {
                // Basic format validation: check magic header
                if (modelData.length == 0 || modelData[0] != 0x53) {
                    resp.setBoolean("ok", false);
                    resp.setString("status", "VALIDATION_FAILED");
                    resp.setString("msg", "Invalid model data: missing CPM header byte 0x53");
                    handler.sendPacketTo(net, new ModelUpdateResultS2C(resp));
                    return;
                }

                // Check ownership
                var models = modelService.getRepo().listModelsForPlayer(uuid.toString());
                var existing = models.stream().filter(m -> m.getId() == modelId).findFirst();
                if (existing.isEmpty()) {
                    resp.setBoolean("ok", false);
                    resp.setString("status", "NOT_OWNER");
                    resp.setString("msg", "You can only update your own models");
                    handler.sendPacketTo(net, new ModelUpdateResultS2C(resp));
                    return;
                }

                String modelName = existing.get().getName();

                // Gap 5: Use in-place updateModel to preserve the modelId
                boolean updated = modelService.getRepo().updateModel(modelId, modelData, null);
                if (!updated) {
                    resp.setBoolean("ok", false);
                    resp.setString("status", "NOT_FOUND");
                    handler.sendPacketTo(net, new ModelUpdateResultS2C(resp));
                    return;
                }

                resp.setBoolean("ok", true);
                resp.setString("status", "OK");
                resp.setLong("mid", modelId); // Same ID preserved

                String ip = handler.getPlayerIp(player);
                modelService.getRepo().logAction(uuid.toString(), "UPDATE", uuid.toString(),
                    modelId, "Updated in-place by owner", ip);

                // Re-detect cloneable flag on update
                boolean cloneable = detectCloneable(modelData);
                modelService.getRepo().setCloneable(modelId, cloneable);

                // Re-broadcast if this was the active model
                handler.setSkin((P) player, modelData, false);
                handler.getSNetH((P) player).cpm$getEncodedModelData().setModel(modelData, false, true);

                Log.info("Model updated in-place: modelId=" + modelId + " player=" + uuid);
            } finally {
                com.tom.cpm.server.crypto.MemoryProtector.wipe(modelData);
            }
        } catch (Exception e) {
            Log.error("Failed to update model: modelId=" + modelId, e);
            resp.setBoolean("ok", false);
            resp.setString("status", "ERROR");
            resp.setString("msg", e.getMessage());
        }
        handler.sendPacketTo(net, new ModelUpdateResultS2C(resp));
    }
}
