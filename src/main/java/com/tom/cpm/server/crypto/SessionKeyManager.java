package com.tom.cpm.server.crypto;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import com.tom.cpm.server.crypto.TimeBoundKeyManager.SessionKeyEntry;
import com.tom.cpm.server.security.RateLimitFilter;
import com.tom.cpm.shared.util.Log;

/**
 * Manages per-client session keys derived from Minecraft's shared secret.
 * Each connected player gets a unique session key. Keys are time-bound
 * and automatically rotated. Expired sessions are cleaned up periodically.
 */
public class SessionKeyManager {

    private final CryptoService crypto;
    private final TimeBoundKeyManager timeKeyManager;
    private final Map<UUID, SessionKeyEntry> sessions;
    private final ScheduledExecutorService cleanupExecutor;

    // Session expiry: 24 hours of inactivity (connection drop, etc.)
    private static final long SESSION_EXPIRY_MS = 24 * 60 * 60 * 1000;

    public SessionKeyManager(CryptoService crypto, TimeBoundKeyManager timeKeyManager) {
        this.crypto = crypto;
        this.timeKeyManager = timeKeyManager;
        this.sessions = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CPM-SessionCleanup");
            t.setDaemon(true);
            return t;
        });

        // Periodic cleanup every 10 minutes
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions,
            10, 10, TimeUnit.MINUTES);
    }

    /**
     * Create a new session for a player from the Minecraft shared secret.
     * Called when the CPM handshake completes.
     */
    public SessionKeyEntry createSession(UUID playerUuid, byte[] mcSharedSecret,
                                          byte[] serverRandom) {
        SecretKey baseKey = crypto.deriveSessionKey(mcSharedSecret,
            playerUuid.toString().getBytes(), serverRandom);
        SessionKeyEntry entry = new SessionKeyEntry(baseKey);
        sessions.put(playerUuid, entry);
        return entry;
    }

    /**
     * Get an existing session. Returns null if no session exists.
     */
    public SessionKeyEntry getSession(UUID playerUuid) {
        SessionKeyEntry entry = sessions.get(playerUuid);
        if (entry != null) {
            // Touch the session to prevent expiry
            entry.getLastAccessTime();
        }
        return entry;
    }

    /**
     * Remove and wipe a session (player disconnected).
     * Also clears rate limit state for the player (Stage 2.3).
     */
    public void destroySession(UUID playerUuid) {
        SessionKeyEntry entry = sessions.remove(playerUuid);
        if (entry != null) {
            entry.wipe();
        }
        RateLimitFilter.clearPlayer(playerUuid);
    }

    /**
     * Get the encryption key for a message in the current time window.
     */
    public SecretKey getEncryptionKey(UUID playerUuid, long windowId) {
        SessionKeyEntry session = getSession(playerUuid);
        if (session == null) {
            throw new IllegalStateException("No session for player: " + playerUuid);
        }
        return timeKeyManager.getWindowKey(session, windowId);
    }

    /**
     * Get the TimeBoundKeyManager for window validation.
     */
    public TimeBoundKeyManager getTimeKeyManager() {
        return timeKeyManager;
    }

    /**
     * Shutdown the cleanup executor. Call on server stop.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        // Wipe all sessions
        sessions.values().forEach(SessionKeyEntry::wipe);
        sessions.clear();
    }

    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        long currentWindow = timeKeyManager.getCurrentWindowId();

        sessions.entrySet().removeIf(entry -> {
            SessionKeyEntry session = entry.getValue();
            if (now - session.getLastAccessTime() > SESSION_EXPIRY_MS) {
                session.wipe();
                Log.debug("Expired session for player: " + entry.getKey());
                return true;
            }
            // Evict old window keys to free memory
            session.evictExpiredWindows(currentWindow, 2);
            return false;
        });
    }
}
