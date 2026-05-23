package com.tom.cpm.server;

import java.util.UUID;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpl.nbt.NBTTagList;
import com.tom.cpm.server.model.ModelEntity;
import com.tom.cpm.server.model.ModelService;
import com.tom.cpm.server.transfer.ChunkedReceiver;
import com.tom.cpm.server.transfer.ChunkedReceiver.CompleteResult;
import com.tom.cpm.server.transfer.ChunkedReceiver.InitResult;
import com.tom.cpm.server.transfer.TransferResumeManager;
import com.tom.cpm.shared.network.IModelServerHandler;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.network.packet.ModelDataChunkAckS2C;
import com.tom.cpm.shared.network.packet.ModelDeleteResultS2C;
import com.tom.cpm.shared.network.packet.ModelListResS2C;
import com.tom.cpm.shared.network.packet.ModelUploadInitAckS2C;
import com.tom.cpm.shared.network.packet.ModelUploadResumeAckS2C;
import com.tom.cpm.shared.network.packet.ModelUploadResultS2C;
import com.tom.cpm.shared.util.Log;

/**
 * Dispatches CPM model server packets to the appropriate backend services.
 * Handles: model list, chunked upload, download, set active/default, delete.
 * All S2C responses are sent back through the native Minecraft port.
 */
public class CpmModelPacketHandler implements IModelServerHandler {

    private final ModelService modelService;
    private final ChunkedReceiver chunkedReceiver;
    private final TransferResumeManager resumeManager;

    public CpmModelPacketHandler(ModelService modelService,
                                  ChunkedReceiver chunkedReceiver,
                                  TransferResumeManager resumeManager) {
        this.modelService = modelService;
        this.chunkedReceiver = chunkedReceiver;
        this.resumeManager = resumeManager;
    }

    // ---- Model List ----

    @Override
    public <P> void handleModelList(NetHandler<?, P, ?> handler, ServerNetH net, P player) {
        UUID uuid = handler.resolvePlayerUUID(player);
        Log.debug("Model list requested by " + uuid);

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
        String modelName = tag.getString("name");
        String modelDesc = tag.getString("desc");
        int totalSize = tag.getInteger("size");
        int numChunks = tag.getInteger("chunks");
        byte[] sha256 = tag.getByteArray("sha256");

        InitResult result = chunkedReceiver.initUpload(uuid, modelName, modelDesc,
            totalSize, numChunks, sha256);

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

    // ---- Model Download ----

    @Override
    public <P> void handleDownload(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                    NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        long modelId = tag.getLong("mid");

        try {
            com.tom.cpm.server.crypto.EncryptedModelBlob blob = modelService.getRepo().loadModelBlob(modelId);
            if (blob == null) {
                NBTTagCompound resp = new NBTTagCompound();
                resp.setLong("mid", modelId);
                resp.setInteger("idx", -1);
                resp.setInteger("total", 0);
                resp.setByteArray("data", new byte[0]);
                handler.sendPacketTo(net, new com.tom.cpm.shared.network.packet.ModelDownloadChunkS2C(resp));
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
                handler.sendPacketTo(net, new com.tom.cpm.shared.network.packet.ModelDownloadChunkS2C(resp));

                Log.info("Model download served: modelId=" + modelId + " size=" + modelData.length);
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
        Log.info("Set active model: player=" + uuid + " modelId=" + modelId);

        try {
            com.tom.cpm.server.crypto.EncryptedModelBlob blob = modelService.getRepo().loadModelBlob(modelId);
            if (blob != null) {
                byte[] modelData = blob.getDecrypted();
                try {
                    // Set the model as active skin via the existing SetSkin pipeline
                    handler.setSkin((P) player, modelData, false);
                    // Also save to PlayerData so it persists
                    handler.getSNetH((P) player).cpm$getEncodedModelData().setModel(modelData, false, true);
                    Log.info("Active model set: player=" + uuid + " modelId=" + modelId);
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

        NBTTagCompound resp = new NBTTagCompound();
        resp.setLong("mid", modelId);

        try {
            boolean ok = modelService.getRepo().deleteModel(modelId, uuid.toString(), false);
            resp.setBoolean("ok", ok);
            resp.setString("status", ok ? "OK" : "NOT_FOUND");

            if (ok) {
                modelService.getRepo().logAction(uuid.toString(), "DELETE", uuid.toString(),
                    modelId, "Deleted by owner", null);
                Log.info("Model deleted: modelId=" + modelId + " player=" + uuid);
            }
        } catch (Exception e) {
            Log.error("Failed to delete model", e);
            resp.setBoolean("ok", false);
            resp.setString("status", "ERROR");
            resp.setString("msg", e.getMessage());
        }
        handler.sendPacketTo(net, new ModelDeleteResultS2C(resp));
    }
}
