package com.tom.cpm.server.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Client-side cache for the player's model list from the server.
 * Cached with a configurable TTL to avoid re-requesting on every GUI open.
 * Invalidated on upload, delete, or set-default operations.
 */
public class ModelListCache {

    private static final long DEFAULT_TTL_MS = TimeUnit.SECONDS.toMillis(30);

    private final List<CachedModel> models = new ArrayList<>();
    private volatile long lastFetchTime;
    private volatile boolean dirty = true;
    private final long ttlMs;

    public ModelListCache() {
        this(DEFAULT_TTL_MS);
    }

    public ModelListCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Update the cache with fresh data from the server.
     */
    public synchronized void update(List<CachedModel> freshModels) {
        models.clear();
        models.addAll(freshModels);
        lastFetchTime = System.currentTimeMillis();
        dirty = false;
    }

    /**
     * Get the cached model list. Returns empty list if cache is stale.
     */
    public synchronized List<CachedModel> getModels() {
        if (isStale()) {
            dirty = true;
        }
        return Collections.unmodifiableList(new ArrayList<>(models));
    }

    /**
     * Check if the cache needs refreshing.
     */
    public synchronized boolean isStale() {
        return dirty || (System.currentTimeMillis() - lastFetchTime > ttlMs);
    }

    /**
     * Mark the cache as needing refresh (called after upload/delete).
     */
    public synchronized void invalidate() {
        dirty = true;
    }

    /**
     * Get the default model ID, or -1 if none set.
     */
    public synchronized long getDefaultModelId() {
        for (CachedModel m : models) {
            if (m.isDefault) return m.id;
        }
        return -1;
    }

    public int size() {
        return models.size();
    }

    /**
     * Lightweight model info for caching.
     */
    public record CachedModel(long id, String name, int sizeBytes, boolean isDefault,
                               boolean isForced, String createdAt) {
    }
}
