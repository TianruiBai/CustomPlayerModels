package com.tom.cpm.server.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter for player model operations.
 * 
 * Limits are session-scoped: state is cleared when a player disconnects.
 * This ensures legitimate client-swapping is never penalized.
 * Admins (OP ≥ 2) are exempt.
 * 
 * Stage 2 Security — Enhancement 2.3
 */
public final class RateLimitFilter {

    // ---- Configuration ----
    private static final int DOWNLOAD_LIMIT = 10;     // per window
    private static final int LIST_LIMIT = 5;          // per window
    private static final int UPLOAD_INIT_LIMIT = 3;   // per window
    private static final long WINDOW_MS = 60_000;     // 1 minute

    public enum Action { DOWNLOAD, LIST, UPLOAD_INIT }

    // ---- State ----
    private static final Map<UUID, TokenBucket> buckets = new ConcurrentHashMap<>();

    private RateLimitFilter() {}

    /**
     * Check whether a player is allowed to perform an action.
     * 
     * @param playerUuid the player's UUID
     * @param action     the action class
     * @param isAdmin    if true, always allow (bypass rate limits)
     * @return true if allowed, false if rate-limited
     */
    public static boolean allow(UUID playerUuid, Action action, boolean isAdmin) {
        if (isAdmin) return true;

        TokenBucket bucket = buckets.computeIfAbsent(playerUuid,
            k -> new TokenBucket(getLimit(action), WINDOW_MS));
        return bucket.tryConsume();
    }

    /**
     * Clear all rate limit state for a player.
     * Called on player disconnect via SessionKeyManager.destroySession().
     */
    public static void clearPlayer(UUID playerUuid) {
        buckets.remove(playerUuid);
    }

    /**
     * Get the number of remaining tokens for a player/action (for debugging).
     */
    public static int remaining(UUID playerUuid, Action action) {
        TokenBucket bucket = buckets.get(playerUuid);
        return bucket != null ? bucket.available() : getLimit(action);
    }

    private static int getLimit(Action action) {
        return switch (action) {
            case DOWNLOAD -> DOWNLOAD_LIMIT;
            case LIST -> LIST_LIMIT;
            case UPLOAD_INIT -> UPLOAD_INIT_LIMIT;
        };
    }

    // ---- Token Bucket Implementation ----

    private static class TokenBucket {
        private final int capacity;
        private final long windowMs;
        private volatile double tokens;
        private volatile long lastRefill;

        TokenBucket(int capacity, long windowMs) {
            this.capacity = capacity;
            this.windowMs = windowMs;
            this.tokens = capacity;
            this.lastRefill = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized int available() {
            refill();
            return (int) Math.floor(tokens);
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            if (elapsed <= 0) return;

            double refillAmount = (double) elapsed / windowMs * capacity;
            tokens = Math.min(capacity, tokens + refillAmount);
            lastRefill = now;
        }
    }
}
