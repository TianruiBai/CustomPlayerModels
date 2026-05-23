package com.tom.cpm.server.crypto;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Memory-safe utilities for handling sensitive byte arrays.
 * 
 * In Java, the GC may copy objects, so absolute memory security is impossible.
 * These utilities reduce the exposure window and ensure explicit wipe.
 * 
 * For production-grade security:
 * - Use ByteBuffer.allocateDirect() for off-heap key storage
 * - Use JCEKS keystore with password callback for long-lived keys
 * - The java.security.SecureRandom DRBG is FIPS 140-2 compliant on Windows
 */
public final class MemoryProtector {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private MemoryProtector() {
    }

    /**
     * Securely wipe a byte array by filling with zeros then random data.
     * Call this as soon as the sensitive data is no longer needed.
     */
    public static void wipe(byte[] data) {
        if (data == null) return;
        Arrays.fill(data, (byte) 0);
        // Second pass with random data to defeat potential flash-memory remanence
        SECURE_RANDOM.nextBytes(data);
        Arrays.fill(data, (byte) 0);
    }

    /**
     * Securely wipe a char array (e.g., passwords).
     */
    public static void wipe(char[] data) {
        if (data == null) return;
        Arrays.fill(data, '\0');
    }

    /**
     * Generate cryptographically secure random bytes.
     */
    public static byte[] secureRandom(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * Constant-time array comparison to prevent timing attacks.
     * Execution time depends only on array length, not content.
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * A byte array wrapper that XOR-masks data with a random pad.
     * The underlying plaintext can only be accessed via getDecrypted().
     * Implements AutoCloseable for try-with-resources usage.
     * 
     * Usage:
     * try (GuardedByteArray gba = new GuardedByteArray(sensitiveData)) {
     *     byte[] plain = gba.getDecrypted();
     *     // ... use plain ...
     *     MemoryProtector.wipe(plain);
     * }
     */
    public static final class GuardedByteArray implements AutoCloseable {
        private final byte[] data;
        private final byte[] pad;
        private volatile boolean wiped;

        public GuardedByteArray(byte[] input) {
            this.pad = new byte[input.length];
            SECURE_RANDOM.nextBytes(pad);
            this.data = new byte[input.length];
            for (int i = 0; i < input.length; i++) {
                data[i] = (byte) (input[i] ^ pad[i]);
            }
            // Clear input immediately
            Arrays.fill(input, (byte) 0);
        }

        public byte[] getDecrypted() {
            if (wiped) throw new IllegalStateException("GuardedByteArray already wiped");
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                out[i] = (byte) (data[i] ^ pad[i]);
            }
            return out;
        }

        public int size() {
            return data.length;
        }

        @Override
        public void close() {
            if (!wiped) {
                Arrays.fill(data, (byte) 0);
                Arrays.fill(pad, (byte) 0);
                wiped = true;
            }
        }
    }
}
