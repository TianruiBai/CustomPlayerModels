package com.tom.cpm.server.crypto;

import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Holds a model in server RAM as AES-256-GCM ciphertext.
 * Replaces the plain byte[] data field in PlayerData.
 * 
 * The decrypted form exists ONLY for the duration of a single
 * getDecrypted() -> use -> wipe cycle. This prevents a server
 * memory dump from exposing all player models simultaneously.
 * 
 * Thread-safe: the ciphertext is immutable; decryption produces
 * a new byte[] each time.
 */
public final class EncryptedModelBlob {

    private final byte[] ciphertext;
    private final byte[] iv;
    private final byte[] gcmTag;
    private final int plaintextSize;
    private final SecretKey perBlobKey;

    /**
     * Create an encrypted model blob from plaintext.
     * The input plaintext array is WIPED by this constructor.
     * 
     * @param plaintext the model data (will be zeroed)
     * @param key       per-model encryption key
     */
    public EncryptedModelBlob(byte[] plaintext, SecretKey key) {
        this.plaintextSize = plaintext.length;
        this.perBlobKey = key;

        try {
            this.iv = MemoryProtector.secureRandom(12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] output = cipher.doFinal(plaintext);

            // output = ciphertext || GCM tag (last 16 bytes)
            int tagStart = output.length - 16;
            this.ciphertext = Arrays.copyOf(output, tagStart);
            this.gcmTag = Arrays.copyOfRange(output, tagStart, output.length);
        } catch (Exception e) {
            throw new CryptoException("Failed to encrypt model blob", e);
        } finally {
            MemoryProtector.wipe(plaintext);
        }
    }

    /**
     * Reconstruct an EncryptedModelBlob from previously encrypted components.
     * Used when loading from database.
     */
    public EncryptedModelBlob(byte[] ciphertext, byte[] iv, byte[] gcmTag,
                              int plaintextSize, SecretKey key) {
        this.ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
        this.iv = Arrays.copyOf(iv, iv.length);
        this.gcmTag = Arrays.copyOf(gcmTag, gcmTag.length);
        this.plaintextSize = plaintextSize;
        this.perBlobKey = key;
    }

    /**
     * Decrypt the model data.
     * CRITICAL: Caller MUST wipe the returned byte[] after use!
     * 
     * Usage:
     * byte[] plain = blob.getDecrypted();
     * try {
     *     // use plain...
     * } finally {
     *     MemoryProtector.wipe(plain);
     * }
     */
    public byte[] getDecrypted() {
        try {
            byte[] combined = Arrays.copyOf(ciphertext, ciphertext.length + 16);
            System.arraycopy(gcmTag, 0, combined, ciphertext.length, 16);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, perBlobKey, new GCMParameterSpec(128, iv));
            return cipher.doFinal(combined);
        } catch (Exception e) {
            throw new CryptoException("Failed to decrypt model blob — key may be wrong or data corrupted", e);
        }
    }

    public int getPlaintextSize() {
        return plaintextSize;
    }

    /** Raw ciphertext (for DB storage). Does NOT include IV or tag. */
    public byte[] getCiphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    public byte[] getIv() {
        return Arrays.copyOf(iv, iv.length);
    }

    public byte[] getGcmTag() {
        return Arrays.copyOf(gcmTag, gcmTag.length);
    }

    /**
     * Runtime exception for crypto failures.
     * Not a checked exception so it can propagate through existing code paths.
     */
    public static final class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
