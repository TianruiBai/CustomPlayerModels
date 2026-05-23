package com.tom.cpm.server.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.tom.cpm.server.transfer.ChunkedUploader.Progress;

/**
 * Tracks upload progress state for UI display.
 * Thread-safe: can be updated from network thread and read from render thread.
 */
public class UploadProgressTracker {

    private volatile String uploadId;
    private volatile String modelName;
    private volatile int lastAckedChunk;
    private volatile int totalChunks;
    private volatile State state = State.IDLE;
    private volatile String error;
    private volatile long modelId;

    private final List<Consumer<UploadProgressTracker>> listeners = new ArrayList<>();

    public enum State {
        IDLE,
        INITIATING,
        UPLOADING,
        COMPLETING,
        DONE,
        CANCELLED,
        FAILED
    }

    public void onProgress(Progress progress) {
        this.lastAckedChunk = progress.lastAckedChunk();
        this.totalChunks = progress.totalChunks();
        if (!progress.ok()) {
            this.state = State.FAILED;
            this.error = progress.error();
        }
        notifyListeners();
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setState(State state) {
        this.state = state;
        notifyListeners();
    }

    public void setError(String error) {
        this.error = error;
        this.state = State.FAILED;
        notifyListeners();
    }

    public void setModelId(long modelId) {
        this.modelId = modelId;
    }

    public void reset() {
        this.uploadId = null;
        this.modelName = null;
        this.lastAckedChunk = 0;
        this.totalChunks = 0;
        this.state = State.IDLE;
        this.error = null;
        this.modelId = 0;
        notifyListeners();
    }

    /** Progress as a float 0.0–1.0. */
    public float getProgress() {
        if (totalChunks <= 0) return 0f;
        return (lastAckedChunk + 1f) / totalChunks;
    }

    /** Progress percentage as integer 0–100. */
    public int getProgressPercent() {
        return (int) (getProgress() * 100);
    }

    public void addListener(Consumer<UploadProgressTracker> listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<UploadProgressTracker> listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners() {
        synchronized (listeners) {
            for (Consumer<UploadProgressTracker> l : listeners) {
                l.accept(this);
            }
        }
    }

    // Getters
    public String getUploadId() { return uploadId; }
    public String getModelName() { return modelName; }
    public int getLastAckedChunk() { return lastAckedChunk; }
    public int getTotalChunks() { return totalChunks; }
    public State getState() { return state; }
    public String getError() { return error; }
    public long getModelId() { return modelId; }
    public boolean isActive() { return state == State.UPLOADING || state == State.INITIATING || state == State.COMPLETING; }
    public boolean isDone() { return state == State.DONE; }
    public boolean isFailed() { return state == State.FAILED; }
}
