package com.tom.cpm.server.transfer;

/**
 * Protocol constants for chunked model transfer over the Minecraft native port.
 * All values are chosen to safely fit within Minecraft's FriendlyByteBuf limit (~32,767 bytes).
 */
public final class ChunkProtocol {

    private ChunkProtocol() {}

    /** Maximum chunk payload size in bytes (30 KB — safe under 32 KB FriendlyByteBuf limit). */
    public static final int MAX_CHUNK_SIZE = 30_720;

    /** Maximum number of retry attempts per chunk before failing the upload. */
    public static final int MAX_RETRIES = 3;

    /** Timeout waiting for a single chunk ACK from the server (milliseconds). */
    public static final int CHUNK_TIMEOUT_MS = 30_000;

    /** Total upload session timeout — after this, the server discards partial uploads (milliseconds). */
    public static final int SESSION_TIMEOUT_MS = 600_000; // 10 minutes

    /** Maximum total model size accepted by the server (10 MB default). */
    public static final int MAX_MODEL_SIZE = 10 * 1024 * 1024;

    // NBT tag keys used in chunk wire format
    public static final String TAG_UPLOAD_ID      = "uploadId";
    public static final String TAG_CHUNK_INDEX    = "chunkIdx";
    public static final String TAG_TOTAL_CHUNKS   = "totalChunks";
    public static final String TAG_DATA           = "data";
    public static final String TAG_DATA_IV        = "dataIv";
    public static final String TAG_DATA_TAG       = "dataTag";
    public static final String TAG_CHUNK_SHA256   = "chunkSha256";
    public static final String TAG_FULL_SHA256    = "fullSha256";
    public static final String TAG_MODEL_NAME     = "name";
    public static final String TAG_MODEL_DESC     = "desc";
    public static final String TAG_TOTAL_SIZE     = "totalSize";
    public static final String TAG_STATUS         = "status";
    public static final String TAG_REASON         = "reason";
    public static final String TAG_MODEL_ID       = "modelId";

    // Status values
    public static final String STATUS_OK            = "OK";
    public static final String STATUS_RETRY         = "RETRY";
    public static final String STATUS_REJECTED      = "REJECTED";
    public static final String STATUS_HASH_MISMATCH = "HASH_MISMATCH";
    public static final String STATUS_VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String STATUS_NOT_FOUND     = "NOT_FOUND";
    public static final String STATUS_NOT_OWNER     = "NOT_OWNER";

    // Upload session states
    public static final String SESSION_ACTIVE    = "ACTIVE";
    public static final String SESSION_COMPLETE  = "COMPLETE";
    public static final String SESSION_CANCELLED = "CANCELLED";
    public static final String SESSION_EXPIRED   = "EXPIRED";

    /**
     * Calculate the number of chunks needed for a given data size.
     */
    public static int chunkCount(int dataSize) {
        return (dataSize + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE;
    }
}
