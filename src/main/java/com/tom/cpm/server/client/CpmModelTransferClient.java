package com.tom.cpm.server.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.tom.cpm.server.transfer.ChunkedUploader;
import com.tom.cpm.server.transfer.ChunkedUploader.Progress;
import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.network.IModelClientHandler;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.network.packet.ModelUploadInitC2S;
import com.tom.cpm.shared.network.packet.ModelDataChunkC2S;
import com.tom.cpm.shared.network.packet.ModelUploadCompleteC2S;
import com.tom.cpm.shared.network.packet.ModelUploadCancelC2S;
import com.tom.cpm.shared.util.Log;
import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpl.nbt.NBTTagList;

/**
 * Client-side coordinator for model transfer operations.
 * Manages chunked upload/download lifecycle, progress tracking,
 * and integration with the CPM network layer.
 * 
 * All model data goes through the Minecraft native port (25565).
 */
public class CpmModelTransferClient implements IModelClientHandler {

    private static CpmModelTransferClient INSTANCE;

    private final CryptoService crypto;
    private ChunkedUploader activeUpload;
    private Consumer<Progress> globalProgressListener;
    private Runnable onCompleteCallback;
    private final ModelListCache modelListCache = new ModelListCache();
    private final List<Consumer<NBTTagCompound>> modelListListeners = new CopyOnWriteArrayList<>();

    /** Get or create the singleton instance. */
    public static CpmModelTransferClient getInstance(CryptoService crypto) {
        if (INSTANCE == null) {
            INSTANCE = new CpmModelTransferClient(crypto);
        }
        return INSTANCE;
    }

    public CpmModelTransferClient(CryptoService crypto) {
        this.crypto = crypto;
    }

    /**
     * Start uploading a model to the server.
     */
    public ChunkedUploader startUpload(byte[] modelData, String modelName,
                                        String modelDesc,
                                        Consumer<Progress> progressListener) {
        if (activeUpload != null && activeUpload.getState() != ChunkedUploader.State.DONE) {
            Log.warn("Upload already in progress, cancelling previous");
            activeUpload.cancel();
        }

        ChunkedUploader uploader = new ChunkedUploader(
            crypto, modelData, modelName, modelDesc, progress -> {
                if (progressListener != null) progressListener.accept(progress);
                if (globalProgressListener != null) globalProgressListener.accept(progress);
            }
        );

        this.activeUpload = uploader;
        Log.info("Upload initiated: " + modelName + " (" +
            uploader.getTotalSize() + " bytes, " +
            uploader.getNumChunks() + " chunks)");

        return uploader;
    }

    /**
     * Send the init packet for the active upload.
     */
    @SuppressWarnings("unchecked")
    public void sendInitPacket() {
        if (activeUpload == null) return;

        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("name", activeUpload.getModelName());
        tag.setString("desc", activeUpload.getModelDesc());
        tag.setInteger("size", activeUpload.getTotalSize());
        tag.setInteger("chunks", activeUpload.getNumChunks());
        tag.setByteArray("sha256", activeUpload.getFullSha256());
        // Gap 2: Include existingModelId for updates
        long existingId = activeUpload.getExistingModelId();
        if (existingId > 0) {
            tag.setLong("existingModelId", existingId);
        }

        NetHandler<?, ?, ?> handler = MinecraftClientAccess.get().getNetHandler();
        if (handler == null) return;
        handler.sendPacketToServer(new ModelUploadInitC2S(tag));
    }

    /**
     * Send the next pending chunk to the server.
     * Called after each chunk ACK or to kick off the upload.
     */
    @SuppressWarnings("unchecked")
    public void sendNextChunk() {
        if (activeUpload == null) return;

        // Check state: if not yet initiated, send init first
        if (activeUpload.getState() == ChunkedUploader.State.IDLE) {
            sendInitPacket();
            return;
        }

        ChunkedUploader.ChunkData chunk = activeUpload.getNextChunk();
        if (chunk == null) {
            // All chunks sent - send complete
            sendCompletePacket();
            return;
        }

        byte[] data = chunk.plaintext() != null ? chunk.plaintext().clone() : new byte[0];
        byte[] iv = chunk.iv() != null ? chunk.iv().clone() : new byte[0];
        byte[] gtag = chunk.gcmTag() != null ? chunk.gcmTag().clone() : new byte[0];
        byte[] csha = chunk.chunkSha256() != null ? chunk.chunkSha256().clone() : new byte[0];
        byte[] fsha = chunk.fullSha256() != null ? chunk.fullSha256().clone() : new byte[0];

        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("uid", activeUpload.getUploadId());
        tag.setInteger("idx", chunk.chunkIndex());
        tag.setInteger("total", chunk.totalChunks());
        tag.setByteArray("data", data);
        tag.setByteArray("iv", iv);
        tag.setByteArray("gtag", gtag);
        tag.setByteArray("csha", csha);
        tag.setByteArray("fsha", fsha);

        NetHandler<?, ?, ?> handler = MinecraftClientAccess.get().getNetHandler();
        if (handler == null) return;
        handler.sendPacketToServer(new ModelDataChunkC2S(tag));

        // Clean up sensitive data after sending
        chunk.wipe();
    }

    /**
     * Send the upload complete packet.
     */
    @SuppressWarnings("unchecked")
    private void sendCompletePacket() {
        if (activeUpload == null) return;

        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("uid", activeUpload.getUploadId());
        tag.setByteArray("fsha", activeUpload.getFullSha256());

        NetHandler<?, ?, ?> handler = MinecraftClientAccess.get().getNetHandler();
        if (handler == null) return;
        handler.sendPacketToServer(new ModelUploadCompleteC2S(tag));

        Log.info("Upload complete packet sent: " + activeUpload.getUploadId());
    }

    /**
     * Cancel the active upload.
     */
    @SuppressWarnings("unchecked")
    public void cancelUpload() {
        if (activeUpload != null) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("uid", activeUpload.getUploadId());

            NetHandler<?, ?, ?> handler = MinecraftClientAccess.get().getNetHandler();
            if (handler != null) {
                handler.sendPacketToServer(new ModelUploadCancelC2S(tag));
            }
            activeUpload.cancel();
            activeUpload = null;
        }
    }

    /**
     * Get the currently active upload, if any.
     */
    public ChunkedUploader getActiveUpload() {
        return activeUpload;
    }

    /**
     * Set a global progress listener for all uploads.
     */
    public void setGlobalProgressListener(Consumer<Progress> listener) {
        this.globalProgressListener = listener;
    }

    /**
     * Set a callback to run when an upload completes successfully.
     * Used by ExportPopup to store serverModelId for future updates.
     */
    public void setOnCompleteCallback(Runnable callback) {
        this.onCompleteCallback = callback;
    }

    public ModelListCache getModelListCache() {
        return modelListCache;
    }

    public void addModelListListener(Consumer<NBTTagCompound> listener) {
        if (listener != null) modelListListeners.add(listener);
    }

    public void removeModelListListener(Consumer<NBTTagCompound> listener) {
        if (listener != null) modelListListeners.remove(listener);
    }

    /**
     * Check if there's an upload that can be resumed.
     */
    public boolean hasResumableUpload() {
        return activeUpload != null &&
            (activeUpload.getState() == ChunkedUploader.State.UPLOADING ||
             activeUpload.getState() == ChunkedUploader.State.INITIATING);
    }

    /**
     * Get the upload ID of the resumable upload.
     */
    public String getResumableUploadId() {
        return activeUpload != null ? activeUpload.getUploadId() : null;
    }

    // ---- IModelClientHandler implementation (S2C response handlers) ----

    @Override
    public void handleModelList(NBTTagCompound tag) {
        Log.debug("Received model list: total=" + tag.getInteger("total"));
        List<ModelListCache.CachedModel> fresh = new ArrayList<>();
        NBTTagList list = tag.getTagList("models", 10);
        if (list != null) {
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound e = (NBTTagCompound) list.get(i);
                fresh.add(new ModelListCache.CachedModel(
                    e.getLong("id"),
                    e.getString("name"),
                    e.getInteger("size"),
                    e.getBoolean("default"),
                    e.getBoolean("forced"),
                    Long.toString(e.getLong("created"))
                ));
            }
        }
        modelListCache.update(fresh);
        for (Consumer<NBTTagCompound> l : modelListListeners) {
            try {
                l.accept(tag);
            } catch (Exception ex) {
                Log.warn("Model list listener failed", ex);
            }
        }
    }

    @Override
    public void handleUploadInitAck(NBTTagCompound tag) {
        String uid = tag.getString("uid");
        boolean accepted = tag.getBoolean("accepted");
        Log.info("Upload init ack: uid=" + uid + " accepted=" + accepted);

        if (activeUpload == null) return;

        String currentUploadId = activeUpload.getUploadId();
        // The server assigns the upload id in init-ack; accept first ack when id is not yet bound.
        if (currentUploadId != null && !uid.equals(currentUploadId)) {
            Log.warn("Ignoring upload init ack for unexpected uid=" + uid +
                " activeUid=" + currentUploadId);
            return;
        }

        if (accepted) {
            activeUpload.onInitAccepted(uid);
            sendNextChunk(); // Start sending chunks
        } else {
            String reason = tag.getString("reason");
            activeUpload.onInitRejected(reason != null ? reason : "Rejected");
            activeUpload = null;
        }
    }

    @Override
    public void handleDataChunkAck(NBTTagCompound tag) {
        String uid = tag.getString("uid");
        int chunkIdx = tag.getInteger("idx");
        boolean ok = tag.getBoolean("ok");
        Log.debug("Chunk ack: uid=" + uid + " idx=" + chunkIdx + " ok=" + ok);

        if (activeUpload != null && uid.equals(activeUpload.getUploadId())) {
            activeUpload.onChunkAck(chunkIdx, ok);
            if (ok) {
                sendNextChunk(); // Send next chunk
            } else {
                // Retry: resend the failed chunk
                sendNextChunk();
            }
        }
    }

    @Override
    public void handleUploadResult(NBTTagCompound tag) {
        String uid = tag.getString("uid");
        boolean ok = tag.getBoolean("ok");
        long modelId = tag.getLong("mid");
        String status = tag.getString("status");

        Log.info("Upload result: uid=" + uid + " ok=" + ok + " modelId=" + modelId + " status=" + status);

        if (activeUpload != null && uid.equals(activeUpload.getUploadId())) {
            if (ok) {
                activeUpload.onComplete(modelId);
                modelListCache.invalidate();
                // Store serverModelId for future updates via the onComplete callback
                if (onCompleteCallback != null) {
                    onCompleteCallback.run();
                }
            } else {
                String msg = tag.getString("msg");
                activeUpload.onFailed(msg != null ? msg : status);
            }
            activeUpload = null; // Upload is done
        }
    }

    @Override
    public void handleUploadResumeAck(NBTTagCompound tag) {
        String uid = tag.getString("uid");
        int lastChunk = tag.getInteger("lastChunk");
        boolean canResume = tag.getBoolean("canResume");
        Log.info("Upload resume ack: uid=" + uid + " lastChunk=" + lastChunk + " canResume=" + canResume);

        if (activeUpload != null && canResume) {
            activeUpload.resumeFrom(lastChunk);
            sendNextChunk(); // Resume sending
        }
    }

    // Gap 4: Download chunk reassembly buffer
    private final java.util.Map<Long, byte[]> downloadBuffers = new java.util.HashMap<>();
    private final java.util.Map<Long, Integer> downloadExpectedChunks = new java.util.HashMap<>();

    @Override
    public void handleDownloadChunk(NBTTagCompound tag, NetH from) {
        long modelId = tag.getLong("mid");
        int chunkIdx = tag.getInteger("idx");
        int totalChunks = tag.getInteger("total");
        byte[] data = tag.getByteArray("data");
        Log.info("Download chunk: modelId=" + modelId + " chunk=" + chunkIdx + "/" + totalChunks + " size=" + data.length);

        if (chunkIdx < 0 || data.length == 0) {
            // Error or empty — fail the download
            NetHandler<?, ?, ?> handler = MinecraftClientAccess.get().getNetHandler();
            if (handler != null) handler.failDownload(modelId, new java.io.IOException("Empty download chunk"));
            return;
        }

        synchronized (downloadBuffers) {
            downloadExpectedChunks.put(modelId, totalChunks);
            byte[] buffer = downloadBuffers.get(modelId);
            if (buffer == null) {
                buffer = new byte[totalChunks * 30_720]; // Max size estimate
                downloadBuffers.put(modelId, buffer);
            }
            // Copy chunk data into buffer at the correct offset
            int offset = chunkIdx * 30_720;
            System.arraycopy(data, 0, buffer, offset, Math.min(data.length, buffer.length - offset));

            // Check if all chunks received
            int receivedCount = downloadExpectedChunks.getOrDefault(modelId, 0) > 0 ? 1 : 0;
            // Simple check: if this is the last chunk (idx == total-1), complete
            if (chunkIdx == totalChunks - 1) {
                // Trim to actual size
                int actualSize = offset + data.length;
                byte[] result = java.util.Arrays.copyOf(buffer, actualSize);
                downloadBuffers.remove(modelId);
                downloadExpectedChunks.remove(modelId);

                NetHandler<?, ?, ?> handler = MinecraftClientAccess.get().getNetHandler();
                if (handler != null) handler.completeDownload(modelId, result);
            }
        }
    }

    @Override
    public void handleDeleteResult(NBTTagCompound tag) {
        long modelId = tag.getLong("mid");
        boolean ok = tag.getBoolean("ok");
        String status = tag.getString("status");
        Log.info("Delete result: modelId=" + modelId + " ok=" + ok + " status=" + status);
        if (ok) modelListCache.invalidate();
    }
}
