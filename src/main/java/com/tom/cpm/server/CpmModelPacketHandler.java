package com.tom.cpm.server;

import java.util.UUID;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.server.model.ModelService;
import com.tom.cpm.server.transfer.ChunkedReceiver;
import com.tom.cpm.server.transfer.ChunkedReceiver.CompleteResult;
import com.tom.cpm.server.transfer.ChunkedReceiver.InitResult;
import com.tom.cpm.server.transfer.TransferResumeManager;
import com.tom.cpm.shared.network.IModelServerHandler;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.util.Log;

/**
 * Dispatches CPM model server packets to the appropriate backend services.
 * Handles: model list, chunked upload, download, set active/default, delete.
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
        // TODO: build ModelListResS2C and send back to client
        Log.debug("Model list requested by " + uuid);
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
        if (result.accepted()) {
            resumeManager.registerUpload(result.uploadId(), uuid, modelName,
                numChunks, -1);
        }
        // TODO: send ModelUploadInitAckS2C with result back to client
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
        if (ack.ok()) {
            resumeManager.updateProgress(uploadId, chunkIdx);
        }
        // TODO: send ModelDataChunkAckS2C back to client
    }

    @Override
    public <P> void handleUploadComplete(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                          NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        String uploadId = tag.getString("uid");
        byte[] fullSha256 = tag.getByteArray("fsha");

        CompleteResult result = chunkedReceiver.completeUpload(uploadId, fullSha256, uuid);
        if (result.success()) {
            resumeManager.removeUpload(uploadId);
            Log.info("Model upload complete: uploadId=" + uploadId +
                " modelId=" + result.modelId());
        }
        // TODO: send ModelUploadResultS2C back to client
    }

    @Override
    public <P> void handleUploadCancel(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                        NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        String uploadId = tag.getString("uid");
        chunkedReceiver.cancelUpload(uploadId, uuid);
        resumeManager.removeUpload(uploadId);
    }

    @Override
    public <P> void handleUploadResume(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                        NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        String uploadId = tag.getString("uid");
        int lastChunk = chunkedReceiver.getLastReceivedChunk(uploadId, uuid);
        // TODO: send ModelUploadResumeAckS2C with lastChunk
    }

    // ---- Model Download ----

    @Override
    public <P> void handleDownload(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                    NBTTagCompound tag) {
        long modelId = tag.getLong("mid");
        // TODO: load model from DB, chunk it, send via ModelDownloadChunkS2C
    }

    // ---- Model Actions ----

    @Override
    public <P> void handleSetActive(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                     NBTTagCompound tag) {
        UUID uuid = handler.resolvePlayerUUID(player);
        long modelId = tag.getLong("mid");
        Log.info("Set active model: player=" + uuid + " modelId=" + modelId);
        // TODO: load from DB, set as active PlayerData, broadcast SetSkinS2C
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
        try {
            boolean ok = modelService.getRepo().deleteModel(modelId, uuid.toString(), false);
            if (ok) {
                modelService.getRepo().logAction(uuid.toString(), "DELETE", uuid.toString(),
                    modelId, "Deleted by owner", null);
            }
            // TODO: send ModelDeleteResultS2C back to client
        } catch (Exception e) {
            Log.error("Failed to delete model", e);
        }
    }
}
