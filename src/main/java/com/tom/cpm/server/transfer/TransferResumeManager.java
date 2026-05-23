package com.tom.cpm.server.transfer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.tom.cpm.shared.util.Log;

/**
 * Tracks upload resume state across client disconnects.
 * When a player reconnects, they can resume an interrupted upload
 * from the last acknowledged chunk.
 * 
 * Thread-safe.
 */
public class TransferResumeManager {

    /** Pending resume states keyed by upload ID. */
    private final Map<String, ResumeState> resumeStates = new ConcurrentHashMap<>();

    /**
     * Register an in-progress upload for potential resume.
     */
    public void registerUpload(String uploadId, UUID playerUuid, String modelName,
                                int totalChunks, int lastAckedChunk) {
        resumeStates.put(uploadId, new ResumeState(
            uploadId, playerUuid, modelName, totalChunks, lastAckedChunk,
            System.currentTimeMillis()
        ));
    }

    /**
     * Get the resume state for an upload. Returns null if not found or expired.
     */
    public ResumeState getResumeState(String uploadId, UUID playerUuid) {
        ResumeState state = resumeStates.get(uploadId);
        if (state == null) return null;
        if (!state.playerUuid.equals(playerUuid)) return null;

        // Check expiry
        if (System.currentTimeMillis() - state.createdAt > ChunkProtocol.SESSION_TIMEOUT_MS) {
            resumeStates.remove(uploadId);
            return null;
        }
        return state;
    }

    /**
     * Update the last acknowledged chunk for an active upload.
     */
    public void updateProgress(String uploadId, int lastAckedChunk) {
        ResumeState state = resumeStates.get(uploadId);
        if (state != null) {
            state.lastAckedChunk = Math.max(state.lastAckedChunk, lastAckedChunk);
        }
    }

    /**
     * Remove a completed or cancelled upload from resume tracking.
     */
    public void removeUpload(String uploadId) {
        resumeStates.remove(uploadId);
    }

    /**
     * Clean up expired resume states. Called periodically.
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        resumeStates.entrySet().removeIf(entry -> {
            if (now - entry.getValue().createdAt > ChunkProtocol.SESSION_TIMEOUT_MS) {
                Log.debug("Expired resume state: " + entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Remove all resume states for a disconnected player.
     */
    public void onPlayerDisconnect(UUID playerUuid) {
        // Don't remove — keep for resume on reconnect.
        // They expire naturally via SESSION_TIMEOUT_MS.
        // But we could limit to N pending uploads per player.
    }

    // ================================================================

    public static class ResumeState {
        public final String uploadId;
        public final UUID playerUuid;
        public final String modelName;
        public final int totalChunks;
        public volatile int lastAckedChunk;
        public final long createdAt;

        ResumeState(String uploadId, UUID playerUuid, String modelName,
                    int totalChunks, int lastAckedChunk, long createdAt) {
            this.uploadId = uploadId;
            this.playerUuid = playerUuid;
            this.modelName = modelName;
            this.totalChunks = totalChunks;
            this.lastAckedChunk = lastAckedChunk;
            this.createdAt = createdAt;
        }
    }
}
