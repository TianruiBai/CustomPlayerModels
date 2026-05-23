package com.tom.cpm.server.client;

import java.util.UUID;
import java.util.function.Consumer;

import com.tom.cpm.server.transfer.ChunkedUploader;
import com.tom.cpm.server.transfer.ChunkedUploader.Progress;
import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.shared.util.Log;

/**
 * Client-side coordinator for model transfer operations.
 * Manages chunked upload/download lifecycle, progress tracking,
 * and integration with the CPM network layer.
 * 
 * All model data goes through the Minecraft native port (25565).
 */
public class CpmModelTransferClient {

    private final CryptoService crypto;
    private ChunkedUploader activeUpload;
    private Consumer<Progress> globalProgressListener;

    public CpmModelTransferClient(CryptoService crypto) {
        this.crypto = crypto;
    }

    /**
     * Start uploading a model to the server.
     * 
     * @param modelData       the complete model byte array
     * @param modelName       display name
     * @param modelDesc       description
     * @param progressListener receives progress updates for UI
     * @return the uploader instance for controlling the upload
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
     * Get the currently active upload, if any.
     */
    public ChunkedUploader getActiveUpload() {
        return activeUpload;
    }

    /**
     * Cancel the active upload.
     */
    public void cancelUpload() {
        if (activeUpload != null) {
            activeUpload.cancel();
            activeUpload = null;
        }
    }

    /**
     * Set a global progress listener for all uploads.
     */
    public void setGlobalProgressListener(Consumer<Progress> listener) {
        this.globalProgressListener = listener;
    }

    /**
     * Check if there's an upload that can be resumed.
     * Called after reconnecting to a server.
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
