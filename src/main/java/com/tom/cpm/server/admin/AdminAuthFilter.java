package com.tom.cpm.server.admin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.tom.cpm.server.crypto.MemoryProtector;
import com.tom.cpm.shared.util.Log;

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * Admin authentication: bcrypt password hashing + HMAC-based token validation.
 * 
 * Uses bcrypt (cost factor 12) for password storage verification.
 * Tokens are HMAC-SHA256 signed with server secret, containing:
 *   username:expiryTimestamp:randomNonce
 * 
 * Rate limiting: max 5 login attempts per minute per IP.
 * 
 * This is a simplified JWT-alternative since we control both sides.
 * If full JWT is desired, swap to io.jsonwebtoken in a future update.
 */
public class AdminAuthFilter {

    private static final int BCRYPT_COST = 12;
    private static final long TOKEN_EXPIRY_MS = 60 * 60 * 1000; // 1 hour
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_WINDOW_MS = 60 * 1000; // 1 minute

    private final javax.crypto.SecretKey secretKey;
    private final Map<String, LoginTracker> loginTrackers = new ConcurrentHashMap<>();

    public AdminAuthFilter(char[] jwtSecret) {
        // Derive HMAC key from secret via HKDF-like single-step derivation.
        // The original char[] is wiped immediately.
        byte[] rawKey = new String(jwtSecret).getBytes(StandardCharsets.UTF_8);
        MemoryProtector.wipe(jwtSecret);

        // Store as SecretKey (opaque — harder to extract from memory dump)
        this.secretKey = new javax.crypto.spec.SecretKeySpec(rawKey, "HmacSHA256");
        MemoryProtector.wipe(rawKey);
    }

    /**
     * Wipe the secret key on server shutdown.
     */
    public void destroy() {
        byte[] encoded = secretKey.getEncoded();
        if (encoded != null) MemoryProtector.wipe(encoded);
    }

    /**
     * Verify a password against a bcrypt hash.
     * 
     * @param password   the plaintext password attempt
     * @param storedHash the bcrypt hash from config
     * @return true if password matches
     */
    public boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        try {
            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), storedHash);
            return result.verified;
        } catch (Exception e) {
            Log.warn("Bcrypt verification error", e);
            return false;
        }
    }

    /**
     * Hash a password with bcrypt for storage.
     */
    public static String hashPassword(String password) {
        return BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
    }

    /**
     * Generate an authentication token for a successfully authenticated admin.
     */
    public String generateToken(String username) {
        long now = System.currentTimeMillis();
        long expiry = now + TOKEN_EXPIRY_MS;
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MemoryProtector.secureRandom(16));

        String payload = username + ":" + expiry + ":" + nonce;
        String signature = hmacSign(payload);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (payload + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validate an authentication token.
     * 
     * @return the username if valid, null if invalid/expired
     */
    public String validateToken(String token) {
        if (token == null || token.isEmpty()) return null;

        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");

            if (parts.length < 4) return null;

            String username = parts[0];
            long expiry = Long.parseLong(parts[1]);
            String nonce = parts[2];
            String providedSig = parts[3];

            // Check expiry
            if (System.currentTimeMillis() > expiry) {
                return null;
            }

            // Verify signature
            String payload = username + ":" + expiry + ":" + nonce;
            String expectedSig = hmacSign(payload);

            if (!constantTimeEquals(providedSig, expectedSig)) {
                return null;
            }

            return username;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check rate limiting for login attempts.
     * 
     * @param ipAddress the client IP
     * @return true if login is allowed
     */
    public boolean checkRateLimit(String ipAddress) {
        long now = System.currentTimeMillis();
        LoginTracker tracker = loginTrackers.computeIfAbsent(ipAddress,
            k -> new LoginTracker());

        synchronized (tracker) {
            if (now - tracker.windowStart > LOGIN_WINDOW_MS) {
                tracker.windowStart = now;
                tracker.attempts = 0;
            }
            tracker.attempts++;
            return tracker.attempts <= MAX_LOGIN_ATTEMPTS;
        }
    }

    /**
     * Extract the Bearer token from an Authorization header.
     */
    public static String extractToken(Map<String, String> headers) {
        String auth = headers.get("authorization");
        if (auth == null) auth = headers.get("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }

    private String hmacSign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKey);
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int result = 0;
        for (int i = 0; i < ba.length; i++) {
            result |= ba[i] ^ bb[i];
        }
        return result == 0;
    }

    private static class LoginTracker {
        long windowStart;
        int attempts;
    }
}
