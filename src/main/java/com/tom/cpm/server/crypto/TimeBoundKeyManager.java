package com.tom.cpm.server.crypto;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;

/**
 * Manages time-window key derivation for per-session encryption.
 * 
 * Each session has a base key (derived from MC shared secret).
 * The actual encryption key for any message is derived from:
 *   window_key = HKDF-SHA512(base_session_key, window_id)
 * 
 * Windows are 30 minutes by default. Keys for ±1 window from current
 * are pre-derived and cached for performance. Expired windows are wiped.
 * 
 * Thread-safe.
 */
public class TimeBoundKeyManager {

    private final CryptoService crypto;
    private final int windowSeconds;
    private final int windowSkew;

    /**
     * @param crypto          the crypto service
     * @param windowMinutes   duration of each time window in minutes (default: 30)
     * @param windowSkew      number of windows to accept on either side of current (default: 1)
     */
    public TimeBoundKeyManager(CryptoService crypto, int windowMinutes, int windowSkew) {
        this.crypto = crypto;
        this.windowSeconds = windowMinutes * 60;
        this.windowSkew = windowSkew;
    }

    /**
     * Calculate the current time window ID from a unix timestamp.
     */
    public long getWindowId(long unixTimeSeconds) {
        return Math.floorDiv(unixTimeSeconds, windowSeconds);
    }

    /**
     * Get the current window ID based on system time.
     */
    public long getCurrentWindowId() {
        return getWindowId(System.currentTimeMillis() / 1000);
    }

    /**
     * Check if a client's window ID is within the acceptable range.
     * 
     * @param clientWindowId  window ID from client's message
     * @param currentWindowId current server window ID
     * @return true if the window is acceptable
     */
    public boolean isWindowValid(long clientWindowId, long currentWindowId) {
        long diff = clientWindowId - currentWindowId;
        return diff >= -windowSkew && diff <= windowSkew;
    }

    /**
     * Get the encryption key for a specific time window.
     * Keys are cached per session per window for performance.
     */
    public SecretKey getWindowKey(SessionKeyEntry session, long windowId) {
        return session.getOrComputeWindowKey(windowId, () ->
            crypto.deriveTimeWindowKey(session.getBaseKey(), windowId)
        );
    }

    /**
     * Get the number of seconds per window.
     */
    public int getWindowSeconds() {
        return windowSeconds;
    }

    /**
     * Encapsulates a per-session base key and its time-window subkeys.
     */
    public static class SessionKeyEntry {
        private final SecretKey baseKey;
        private final Map<Long, SecretKey> windowKeyCache;
        private volatile long lastAccessTime;

        public SessionKeyEntry(SecretKey baseKey) {
            this.baseKey = baseKey;
            this.windowKeyCache = new ConcurrentHashMap<>();
            this.lastAccessTime = System.currentTimeMillis();
        }

        public SecretKey getBaseKey() {
            lastAccessTime = System.currentTimeMillis();
            return baseKey;
        }

        SecretKey getOrComputeWindowKey(long windowId,
                                         java.util.function.Supplier<SecretKey> computer) {
            lastAccessTime = System.currentTimeMillis();
            return windowKeyCache.computeIfAbsent(windowId, k -> computer.get());
        }

        public long getLastAccessTime() {
            return lastAccessTime;
        }

        /**
         * Wipe all keys from memory. Called on session expiry.
         */
        public void wipe() {
            for (SecretKey key : windowKeyCache.values()) {
                MemoryProtector.wipe(key.getEncoded());
            }
            MemoryProtector.wipe(baseKey.getEncoded());
            windowKeyCache.clear();
        }

        /**
         * Remove expired window keys to free memory.
         * Called periodically by SessionKeyManager.
         */
        public void evictExpiredWindows(long currentWindowId, int skew) {
            windowKeyCache.keySet().removeIf(windowId -> {
                long diff = currentWindowId - windowId;
                return diff > skew; // Window is too old
            });
        }
    }
}
