package com.tom.cpm.server.transfer;

import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.server.crypto.EncryptedModelBlob;
import com.tom.cpm.server.crypto.MemoryProtector;
import com.tom.cpm.server.crypto.SessionKeyManager;
import com.tom.cpm.server.crypto.TimeBoundKeyManager;
import com.tom.cpm.server.model.ModelRepository;
import com.tom.cpm.server.model.ModelValidator;
import com.tom.cpm.shared.util.Log;

/**
 * Server-side chunked upload receiver.
 * Receives individual encrypted chunks, sends ACK/RETRY, reassembles,
 * verifies integrity, and stores the completed model.
 * 
 * Thread-safe: each upload session is independent.
 */
public class ChunkedReceiver {

    private final SessionKeyManager sessionKeys;
    private final CryptoService crypto;
    private final ModelRepository repo;
    private final ModelValidator validator;

    /** In-progress uploads keyed by upload session ID. */
    private final Map<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();

    public ChunkedReceiver(SessionKeyManager sessionKeys, CryptoService crypto,
                           ModelRepository repo, ModelValidator validator) {
        this.sessionKeys = sessionKeys;
        this.crypto = crypto;
        this.repo = repo;
        this.validator = validator;
    }

    /**
     * Initialize a new upload session.
     * Called when ModelUploadInitC2S is received.
     */
    public InitResult initUpload(UUID playerUuid, String modelName, String modelDesc,
                                  int totalSize, int numChunks, byte[] fullSha256) {
        // Validate against limits
        if (totalSize > ChunkProtocol.MAX_MODEL_SIZE) {
            return InitResult.reject("Model exceeds maximum size: " +
                (ChunkProtocol.MAX_MODEL_SIZE / 1024 / 1024) + " MB");
        }
        if (numChunks < 1 || numChunks > 1000) {
            return InitResult.reject("Invalid chunk count: " + numChunks);
        }

        // Check that player isn't blocked
        try {
            if (repo.isPlayerBlocked(playerUuid.toString())) {
                return InitResult.reject("Player is blocked from uploading");
            }
        } catch (SQLException e) {
            Log.error("Failed to check player block status", e);
            return InitResult.reject("Internal error");
        }

        // Validate model name
        ModelValidator.ValidationResult nameCheck = validator.validateName(modelName);
        if (!nameCheck.valid()) {
            return InitResult.reject(nameCheck.error());
        }

        String uploadId = UUID.randomUUID().toString();
        PendingUpload upload = new PendingUpload(
            uploadId, playerUuid, modelName, modelDesc,
            totalSize, numChunks, fullSha256
        );
        pendingUploads.put(uploadId, upload);

        Log.info("Upload session created: " + uploadId + " for player " + playerUuid +
            " (" + totalSize + " bytes, " + numChunks + " chunks)");

        return InitResult.accept(uploadId);
    }

    /**
     * Receive a single encrypted chunk.
     * Called when ModelDataChunkC2S is received.
     */
    public ChunkAck receiveChunk(String uploadId, int chunkIndex, int totalChunks,
                                  byte[] encryptedChunkData, byte[] chunkIv,
                                  byte[] chunkTag, byte[] chunkSha256,
                                  byte[] fullSha256, UUID playerUuid) {
        PendingUpload upload = pendingUploads.get(uploadId);
        if (upload == null) {
            return ChunkAck.retry("Upload session not found — may have expired");
        }
        // Ownership check
        if (!upload.playerUuid.equals(playerUuid)) {
            return ChunkAck.retry("Not your upload session");
        }
        if (upload.completed) {
            return ChunkAck.retry("Upload already completed");
        }

        // Validate chunk index
        if (chunkIndex < 0 || chunkIndex >= upload.numChunks) {
            return ChunkAck.retry("Invalid chunk index: " + chunkIndex);
        }

        try {
            // Decrypt chunk with session key
            // (In production, this uses the time-window-specific session key;
            // for now we verify the chunk integrity via SHA-256)
            TimeBoundKeyManager timeKeyMgr = sessionKeys.getTimeKeyManager();
            long windowId = timeKeyMgr.getCurrentWindowId();
            // TODO: full decryption via sessionKeys.getEncryptionKey(playerUuid, windowId)

            // Verify chunk SHA-256
            if (chunkSha256 != null && chunkSha256.length == 32) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] computed = md.digest(encryptedChunkData);
                if (!Arrays.equals(computed, chunkSha256)) {
                    return ChunkAck.retry("Chunk SHA-256 mismatch");
                }
            }

            // Store chunk
            upload.storeChunk(chunkIndex, encryptedChunkData);

            // Update full SHA-256 if provided
            if (fullSha256 != null && fullSha256.length == 32 && upload.fullSha256 == null) {
                upload.fullSha256 = Arrays.copyOf(fullSha256, 32);
            }

            Log.debug("Chunk " + chunkIndex + "/" + upload.numChunks +
                " received for upload " + uploadId);

            return ChunkAck.ok(chunkIndex);
        } catch (Exception e) {
            Log.error("Error processing chunk " + chunkIndex + " for upload " + uploadId, e);
            return ChunkAck.retry("Internal error processing chunk");
        }
    }

    /**
     * Complete an upload: reassemble, verify, store in DB.
     * Called when ModelUploadCompleteC2S is received.
     */
    public CompleteResult completeUpload(String uploadId, byte[] fullSha256, UUID playerUuid) {
        PendingUpload upload = pendingUploads.get(uploadId);
        if (upload == null) {
            return CompleteResult.fail(ChunkProtocol.STATUS_NOT_FOUND, "Upload session not found");
        }
        if (!upload.playerUuid.equals(playerUuid)) {
            return CompleteResult.fail(ChunkProtocol.STATUS_NOT_OWNER, "Not your upload session");
        }
        if (upload.completed) {
            return CompleteResult.fail("ALREADY_COMPLETE", "Upload already completed");
        }

        // Check all chunks received
        if (!upload.allChunksReceived()) {
            int missing = upload.numChunks - upload.receivedCount();
            return CompleteResult.fail("INCOMPLETE",
                "Missing " + missing + " chunk(s)");
        }

        try {
            // Reassemble
            byte[] reassembled = upload.reassemble();
            if (reassembled == null) {
                return CompleteResult.fail("REASSEMBLY_FAILED", "Failed to reassemble chunks");
            }

            // Verify full SHA-256
            byte[] expected = fullSha256 != null ? fullSha256 : upload.fullSha256;
            if (expected != null && expected.length == 32) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] computed = md.digest(reassembled);
                if (!Arrays.equals(computed, expected)) {
                    MemoryProtector.wipe(reassembled);
                    return CompleteResult.fail(ChunkProtocol.STATUS_HASH_MISMATCH,
                        "Full model SHA-256 mismatch — data may be corrupted");
                }
            }

            // Validate model format
            ModelValidator.ValidationResult validation = validator.validate(reassembled, expected);
            if (!validation.valid()) {
                MemoryProtector.wipe(reassembled);
                return CompleteResult.fail(ChunkProtocol.STATUS_VALIDATION_FAILED,
                    validation.error());
            }

            // Store in database (model data is wiped by storeModel)
            long modelId = repo.storeModel(
                playerUuid.toString(),
                upload.modelName,
                upload.modelDesc,
                reassembled,
                null // icon — extracted from model data in future enhancement
            );
            // reassembled is now wiped by storeModel -> EncryptedModelBlob constructor

            // Log to audit
            repo.logAction(playerUuid.toString(), "UPLOAD", playerUuid.toString(),
                modelId, "Size: " + upload.totalSize + " bytes, chunks: " + upload.numChunks, null);

            upload.completed = true;
            pendingUploads.remove(uploadId);

            Log.info("Upload complete: " + uploadId + " -> modelId=" + modelId +
                " (" + upload.totalSize + " bytes)");

            return CompleteResult.ok(modelId);
        } catch (Exception e) {
            Log.error("Failed to complete upload " + uploadId, e);
            return CompleteResult.fail("INTERNAL_ERROR", "Internal error: " + e.getMessage());
        }
    }

    /**
     * Cancel an in-progress upload.
     */
    public void cancelUpload(String uploadId, UUID playerUuid) {
        PendingUpload upload = pendingUploads.get(uploadId);
        if (upload != null && upload.playerUuid.equals(playerUuid)) {
            upload.cleanup();
            pendingUploads.remove(uploadId);
            Log.info("Upload cancelled: " + uploadId);
        }
    }

    /**
     * Get the last successfully received chunk index (for resume).
     */
    public int getLastReceivedChunk(String uploadId, UUID playerUuid) {
        PendingUpload upload = pendingUploads.get(uploadId);
        if (upload != null && upload.playerUuid.equals(playerUuid)) {
            return upload.lastReceivedChunk();
        }
        return -1;
    }

    /**
     * Clean up expired upload sessions. Called periodically.
     */
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        pendingUploads.entrySet().removeIf(entry -> {
            PendingUpload upload = entry.getValue();
            if (now - upload.createdAt > ChunkProtocol.SESSION_TIMEOUT_MS) {
                upload.cleanup();
                Log.debug("Expired upload session: " + entry.getKey());
                return true;
            }
            return false;
        });
    }

    // ================================================================
    // Inner types
    // ================================================================

    public record InitResult(String uploadId, boolean accepted, String rejectReason) {
        public static InitResult accept(String uploadId) {
            return new InitResult(uploadId, true, null);
        }
        public static InitResult reject(String reason) {
            return new InitResult(null, false, reason);
        }
    }

    public record ChunkAck(int chunkIndex, boolean ok, String message) {
        public static ChunkAck ok(int chunkIndex) {
            return new ChunkAck(chunkIndex, true, null);
        }
        public static ChunkAck retry(String message) {
            return new ChunkAck(-1, false, message);
        }
    }

    public record CompleteResult(long modelId, boolean success, String status, String message) {
        public static CompleteResult ok(long modelId) {
            return new CompleteResult(modelId, true, ChunkProtocol.STATUS_OK, null);
        }
        public static CompleteResult fail(String status, String message) {
            return new CompleteResult(-1, false, status, message);
        }
    }

    // ================================================================
    // Pending upload state
    // ================================================================

    private static class PendingUpload {
        final String uploadId;
        final UUID playerUuid;
        final String modelName;
        final String modelDesc;
        final int totalSize;
        final int numChunks;
        final long createdAt;
        byte[] fullSha256;
        boolean completed;

        private final byte[][] chunks;
        private final boolean[] received;
        private int receivedCount;

        PendingUpload(String uploadId, UUID playerUuid, String modelName, String modelDesc,
                      int totalSize, int numChunks, byte[] fullSha256) {
            this.uploadId = uploadId;
            this.playerUuid = playerUuid;
            this.modelName = modelName;
            this.modelDesc = modelDesc;
            this.totalSize = totalSize;
            this.numChunks = numChunks;
            this.fullSha256 = fullSha256 != null ? Arrays.copyOf(fullSha256, fullSha256.length) : null;
            this.createdAt = System.currentTimeMillis();
            this.chunks = new byte[numChunks][];
            this.received = new boolean[numChunks];
            this.receivedCount = 0;
        }

        synchronized void storeChunk(int index, byte[] data) {
            if (!received[index]) {
                chunks[index] = Arrays.copyOf(data, data.length);
                received[index] = true;
                receivedCount++;
            }
        }

        synchronized boolean allChunksReceived() {
            return receivedCount >= numChunks;
        }

        synchronized int receivedCount() {
            return receivedCount;
        }

        synchronized int lastReceivedChunk() {
            int last = -1;
            for (int i = 0; i < numChunks; i++) {
                if (received[i]) last = i;
            }
            return last;
        }

        synchronized byte[] reassemble() {
            if (!allChunksReceived()) return null;
            int total = 0;
            for (byte[] c : chunks) total += c.length;
            byte[] result = new byte[total];
            int offset = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, result, offset, c.length);
                offset += c.length;
            }
            return result;
        }

        synchronized void cleanup() {
            for (int i = 0; i < chunks.length; i++) {
                if (chunks[i] != null) {
                    MemoryProtector.wipe(chunks[i]);
                    chunks[i] = null;
                }
            }
            if (fullSha256 != null) {
                MemoryProtector.wipe(fullSha256);
            }
        }
    }
}
