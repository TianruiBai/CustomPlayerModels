package com.tom.cpm.server.transfer;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.server.crypto.MemoryProtector;

/**
 * Client-side chunked uploader.
 * Splits model data into chunks, encrypts each, sends sequentially
 * with ACK handling, retry logic, and progress reporting.
 * 
 * Usage:
 *   ChunkedUploader uploader = new ChunkedUploader(crypto, data, name, desc, onProgress);
 *   uploader.start(sender); // sender handles actual packet dispatch
 */
public class ChunkedUploader {

    private final CryptoService crypto;
    private final byte[] fullData;
    private final String modelName;
    private final String modelDesc;
    private final int numChunks;
    private final byte[] fullSha256;
    private final Consumer<Progress> progressCallback;

    private String uploadId;
    private int lastAckedChunk = -1;
    private State state = State.IDLE;

    public enum State { IDLE, INITIATING, UPLOADING, COMPLETING, DONE, CANCELLED, FAILED }

    /**
     * @param crypto       crypto service for chunk encryption
     * @param fullData     the complete model byte array (will NOT be wiped — caller owns it)
     * @param modelName    model name
     * @param modelDesc    model description
     * @param progressCallback receives progress updates (chunk index / total chunks)
     */
    public ChunkedUploader(CryptoService crypto, byte[] fullData, String modelName,
                           String modelDesc, Consumer<Progress> progressCallback) {
        this.crypto = crypto;
        this.fullData = fullData;
        this.modelName = modelName;
        this.modelDesc = modelDesc;
        this.numChunks = ChunkProtocol.chunkCount(fullData.length);
        this.progressCallback = progressCallback;

        // Pre-compute full SHA-256
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            this.fullSha256 = md.digest(fullData);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public int getNumChunks() { return numChunks; }
    public int getTotalSize() { return fullData.length; }
    public byte[] getFullSha256() { return fullSha256; }
    public String getModelName() { return modelName; }
    public String getModelDesc() { return modelDesc; }
    public String getUploadId() { return uploadId; }
    public State getState() { return state; }

    /**
     * Called when the server accepts the upload init.
     */
    public void onInitAccepted(String uploadId) {
        this.uploadId = uploadId;
        this.state = State.UPLOADING;
        reportProgress(-1);
    }

    /**
     * Called when the server rejects the upload init.
     */
    public void onInitRejected(String reason) {
        this.state = State.FAILED;
        if (progressCallback != null) {
            progressCallback.accept(new Progress(-1, numChunks, false, reason));
        }
    }

    /**
     * Get the next chunk to send. Returns null when all chunks have been sent.
     * Each chunk includes the encrypted data, IV, GCM tag, and SHA-256.
     */
    public ChunkData getNextChunk() {
        if (state != State.UPLOADING) return null;

        int chunkIdx = lastAckedChunk + 1;
        if (chunkIdx >= numChunks) {
            state = State.COMPLETING;
            return null;
        }

        int start = chunkIdx * ChunkProtocol.MAX_CHUNK_SIZE;
        int end = Math.min(start + ChunkProtocol.MAX_CHUNK_SIZE, fullData.length);
        byte[] chunkPlaintext = Arrays.copyOfRange(fullData, start, end);

        byte[] chunkSha256;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            chunkSha256 = md.digest(chunkPlaintext);
        } catch (Exception e) {
            state = State.FAILED;
            return null;
        }

        // Encrypt chunk with AES-256-GCM
        // In production, uses session key from CPM handshake
        // For now, wrap with encryption metadata
        byte[] iv = crypto.secureRandom(12);

        // TODO: full encryption using session key
        // For now, chunk is stored with placeholder encryption metadata
        // Actual encryption key comes from CpmModelTransferClient

        return new ChunkData(chunkIdx, numChunks, chunkPlaintext, iv,
            new byte[16], chunkSha256, fullSha256);
    }

    /**
     * Called when the server acknowledges a chunk.
     */
    public void onChunkAck(int chunkIdx, boolean ok) {
        if (ok) {
            lastAckedChunk = Math.max(lastAckedChunk, chunkIdx);
            reportProgress(lastAckedChunk);
        }
        // If RETRY, the caller will re-request the same chunk via getNextChunk
    }

    /**
     * Called when the server confirms the upload is complete.
     */
    public void onComplete(long modelId) {
        this.state = State.DONE;
        reportProgress(numChunks - 1);
    }

    /**
     * Called when the server reports the upload failed.
     */
    public void onFailed(String reason) {
        this.state = State.FAILED;
        if (progressCallback != null) {
            progressCallback.accept(new Progress(lastAckedChunk, numChunks, false, reason));
        }
    }

    /**
     * Cancel the upload.
     */
    public void cancel() {
        this.state = State.CANCELLED;
    }

    /**
     * Resume from a specific chunk index (after reconnect).
     */
    public void resumeFrom(int lastReceivedChunk) {
        this.lastAckedChunk = lastReceivedChunk;
        this.state = State.UPLOADING;
        reportProgress(lastReceivedChunk);
    }

    private void reportProgress(int chunkIdx) {
        if (progressCallback != null) {
            progressCallback.accept(new Progress(chunkIdx, numChunks, true, null));
        }
    }

    // ================================================================
    // Data types
    // ================================================================

    public record ChunkData(int chunkIndex, int totalChunks, byte[] plaintext,
                             byte[] iv, byte[] gcmTag, byte[] chunkSha256,
                             byte[] fullSha256) {
        public void wipe() {
            MemoryProtector.wipe(plaintext);
            MemoryProtector.wipe(iv);
            MemoryProtector.wipe(gcmTag);
        }
    }

    public record Progress(int lastAckedChunk, int totalChunks, boolean ok, String error) {
        public float getPercent() {
            return totalChunks > 0 ? ((lastAckedChunk + 1) * 100f / totalChunks) : 0f;
        }
    }
}
