package com.tom.cpm.server.client;

import java.util.UUID;
import java.util.function.Consumer;

import com.tom.cpm.server.transfer.ChunkedUploader;
import com.tom.cpm.server.transfer.ChunkedUploader.Progress;
import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.network.packet.ModelUploadInitC2S;
import com.tom.cpm.shared.network.packet.ModelDataChunkC2S;
import com.tom.cpm.shared.network.packet.ModelUploadCompleteC2S;
import com.tom.cpm.shared.network.packet.ModelUploadCancelC2S;
import com.tom.cpm.shared.util.Log;
import com.tom.cpl.nbt.NBTTagCompound;

/**
 * Client-side coordinator for model transfer operations.
 * Manages chunked upload/download lifecycle, progress tracking,
 * and integration with the CPM network layer.
 * 
 * All model data goes through the Minecraft native port (25565).
 */
public class CpmModelTransferClient {

    private static CpmModelTransferClient INSTANCE;

    private final CryptoService crypto;
    private ChunkedUploader activeUpload;
    private Consumer<Progress> globalProgressListener;
    private Runnable onCompleteCallback;

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
            activeUpload.onInitAccepted(java.util.UUID.randomUUID().toString());
            sendInitPacket();
            return;
        }

        ChunkedUploader.ChunkData chunk = activeUpload.getNextChunk();
        if (chunk == null) {
            // All chunks sent - send complete
            sendCompletePacket();
            return;
        }

        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("uid", activeUpload.getUploadId());
        tag.setInteger("idx", chunk.chunkIndex());
        tag.setInteger("total", chunk.totalChunks());
        tag.setByteArray("data", chunk.plaintext());
        tag.setByteArray("iv", chunk.iv());
        tag.setByteArray("gtag", chunk.gcmTag());
        tag.setByteArray("csha", chunk.chunkSha256());
        tag.setByteArray("fsha", chunk.fullSha256());

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
}
