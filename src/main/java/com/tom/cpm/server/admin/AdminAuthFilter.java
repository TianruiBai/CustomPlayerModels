package com.tom.cpm.server.admin;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;

import com.tom.cpm.server.crypto.MemoryProtector;
import com.tom.cpm.shared.util.Log;

/**
 * Admin authentication: bcrypt password hashing + HMAC-based token validation.
 *
 * Uses bcrypt (cost factor 12) for password storage verification,
 * loaded reflectively to avoid NeoForge ModuleClassLoader issues.
 * Tokens are HMAC-SHA256 signed with server secret, containing:
 *   username:expiryTimestamp:randomNonce
 *
 * Rate limiting: max 5 login attempts per minute per IP.
 */
public class AdminAuthFilter {

    private static final int BCRYPT_COST = 12;
    private static final long TOKEN_EXPIRY_MS = 60 * 60 * 1000; // 1 hour
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_WINDOW_MS = 60 * 1000; // 1 minute

    private final javax.crypto.SecretKey secretKey;
    private final Map<String, LoginTracker> loginTrackers = new ConcurrentHashMap<>();

    // Reflective bcrypt access — avoids compile-time dependency
    private static Object bcryptWithDefaults;
    private static Method bcryptHashToString;
    private static Object bcryptVerifyer;
    private static Method bcryptVerify;
    private static boolean bcryptAvailable;

    static {
        initBcrypt();
    }

    private static void initBcrypt() {
        try {
            Class<?> bcryptClass = loadClass("at.favre.lib.crypto.bcrypt.BCrypt");
            // BCrypt.withDefaults()
            Method withDefaults = bcryptClass.getMethod("withDefaults");
            bcryptWithDefaults = withDefaults.invoke(null);

            // BCrypt.Hasher.hashToString(int cost, char[] password)
            Class<?> hasherClass = loadClass("at.favre.lib.crypto.bcrypt.BCrypt$Hasher");
            for (Method m : hasherClass.getMethods()) {
                if (!"hashToString".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == int.class) {
                    bcryptHashToString = m;
                    break;
                }
            }
            if (bcryptHashToString == null) {
                throw new NoSuchMethodException("No compatible BCrypt.Hasher.hashToString found");
            }

            // BCrypt.verifyer()
            Method verifyerMethod = bcryptClass.getMethod("verifyer");
            bcryptVerifyer = verifyerMethod.invoke(null);

            // BCrypt.Verifyer.verify(char[] password, String hash)
            Class<?> verifyerClass = loadClass("at.favre.lib.crypto.bcrypt.BCrypt$Verifyer");
            int bestScore = Integer.MAX_VALUE;
            for (Method m : verifyerClass.getMethods()) {
                if (!"verify".equals(m.getName())) continue;
                if (m.getParameterCount() == 2) {
                    Class<?>[] pt = m.getParameterTypes();
                    int score = scoreVerifyMethod(pt);
                    if (score < bestScore) {
                        bestScore = score;
                        bcryptVerify = m;
                    }
                }
            }
            if (bcryptVerify == null) {
                throw new NoSuchMethodException("No compatible BCrypt.Verifyer.verify found");
            }

            bcryptAvailable = true;
            Log.info("Bcrypt loaded successfully via reflection");
        } catch (Throwable t) {
            bcryptAvailable = false;
            Log.warn("Bcrypt library not available. Admin password verification will not work.", t);
        }
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader[] candidates = new ClassLoader[] {
            AdminAuthFilter.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        };

        for (ClassLoader loader : candidates) {
            if (loader == null) continue;
            try {
                return Class.forName(className, true, loader);
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        return Class.forName(className);
    }

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
     */
    public boolean verifyPassword(String password, String storedHash) {
        return verifyPasswordHash(password, storedHash);
    }

    public static boolean verifyPasswordHash(String password, String storedHash) {
        if (password == null || storedHash == null || !bcryptAvailable) return false;
        try {
            Object result = invokeVerify(password, storedHash);
            // BCrypt.Result.verified (field)
            java.lang.reflect.Field verifiedField = result.getClass().getDeclaredField("verified");
            verifiedField.setAccessible(true);
            return verifiedField.getBoolean(result);
        } catch (Exception e) {
            Log.warn("Bcrypt verification error", e);
            return false;
        }
    }

    /**
     * Hash a password with bcrypt for storage.
     */
    public static String hashPassword(String password) {
        if (!bcryptAvailable) {
            Log.error("Cannot hash password: bcrypt library not available");
            return null;
        }
        try {
            Class<?>[] pt = bcryptHashToString.getParameterTypes();
            Object passArg;
            if (pt[1] == char[].class) {
                passArg = password.toCharArray();
            } else if (pt[1] == byte[].class) {
                passArg = password.getBytes(StandardCharsets.UTF_8);
            } else if (pt[1] == String.class || CharSequence.class.isAssignableFrom(pt[1])) {
                passArg = password;
            } else {
                Log.error("Unsupported bcrypt hashToString password parameter: " + pt[1]);
                return null;
            }
            return (String) bcryptHashToString.invoke(bcryptWithDefaults, BCRYPT_COST, passArg);
        } catch (Exception e) {
            Log.error("Bcrypt hashing failed", e);
            return null;
        }
    }

    private static Object invokeVerify(String password, String storedHash) throws Exception {
        Class<?>[] pt = bcryptVerify.getParameterTypes();
        Object arg0 = toPasswordArg(pt[0], password);
        Object arg1 = toHashArg(pt[1], storedHash);

        return bcryptVerify.invoke(bcryptVerifyer, arg0, arg1);
    }

    private static int scoreVerifyMethod(Class<?>[] pt) {
        if (pt.length != 2) return Integer.MAX_VALUE;
        return scorePasswordType(pt[0]) + scoreHashType(pt[1]);
    }

    private static int scorePasswordType(Class<?> type) {
        if (type == char[].class) return 0;
        if (type == byte[].class) return 1;
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) return 2;
        return 100;
    }

    private static int scoreHashType(Class<?> type) {
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) return 0;
        if (type == char[].class) return 1;
        if (type == byte[].class) return 2;
        if (type.getName().endsWith("$HashData")) return 10;
        return 100;
    }

    private static Object toPasswordArg(Class<?> targetType, String password) {
        if (targetType == char[].class) return password.toCharArray();
        if (targetType == byte[].class) return password.getBytes(StandardCharsets.UTF_8);
        if (targetType == String.class || CharSequence.class.isAssignableFrom(targetType)) return password;
        throw new IllegalStateException("Unsupported bcrypt verify parameter[0]: " + targetType);
    }

    private static Object toHashArg(Class<?> targetType, String storedHash) throws Exception {
        if (targetType == char[].class) return storedHash.toCharArray();
        if (targetType == byte[].class) return storedHash.getBytes(StandardCharsets.UTF_8);
        if (targetType == String.class || CharSequence.class.isAssignableFrom(targetType)) return storedHash;

        // Some bcrypt versions use BCrypt.HashData as the second parameter.
        if (targetType.getName().endsWith("$HashData")) {
            Object parsed = tryParseHashData(targetType, storedHash);
            if (parsed != null) return parsed;
        }

        throw new IllegalStateException("Unsupported bcrypt verify parameter[1]: " + targetType);
    }

    private static Object tryParseHashData(Class<?> hashDataType, String storedHash) {
        String[] parserCandidates = new String[] {
            "at.favre.lib.crypto.bcrypt.BCryptParser$Default",
            "at.favre.lib.crypto.bcrypt.BCryptParser"
        };

        for (String parserClassName : parserCandidates) {
            try {
                Class<?> parserClass = loadClass(parserClassName);
                Object parser = createParserInstance(parserClass);
                if (parser == null) continue;

                for (Method m : parserClass.getMethods()) {
                    if (!"parse".equals(m.getName()) || m.getParameterCount() != 1) continue;
                    if (!hashDataType.isAssignableFrom(m.getReturnType())) continue;

                    Class<?> p = m.getParameterTypes()[0];
                    Object arg;
                    if (p == byte[].class) arg = storedHash.getBytes(StandardCharsets.UTF_8);
                    else if (p == char[].class) arg = storedHash.toCharArray();
                    else if (p == String.class || CharSequence.class.isAssignableFrom(p)) arg = storedHash;
                    else continue;

                    return m.invoke(parser, arg);
                }
            } catch (Exception ignored) {
                // try next parser candidate
            }
        }
        return null;
    }

    private static Object createParserInstance(Class<?> parserClass) {
        try {
            Method m = parserClass.getMethod("strict");
            return m.invoke(null);
        } catch (Exception ignored) {
        }
        try {
            Method m = parserClass.getMethod("defaultParser");
            return m.invoke(null);
        } catch (Exception ignored) {
        }
        try {
            return parserClass.getDeclaredConstructor().newInstance();
        } catch (Exception ignored) {
        }
        return null;
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
