package com.tom.cpm.server.crypto;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Core cryptographic operations for CPM model server.
 * All algorithms are JDK built-in (Java 21+).
 * 
 * AES-256-GCM: Data encryption (confidentiality + integrity via AEAD)
 * HKDF-SHA512: Key derivation (from master key, session keys, per-row keys)
 * PBKDF2: Password-based key derivation (for DB file password)
 */
public final class CryptoService {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12;       // bytes (96 bits, recommended for GCM)

    private final SecureRandom secureRandom;

    // Pre-computed HKDF info strings (constant-time comparison not needed here)
    private static final byte[] HKDF_SESSION_INFO = "cpm_session_key_v2".getBytes();
    private static final byte[] HKDF_TIME_WINDOW_INFO = "cpm_time_window_v1".getBytes();
    private static final byte[] HKDF_COLUMN_INFO = "model_data_blob".getBytes();
    private static final byte[] HKDF_ROW_INFO = "model_data_row".getBytes();

    private static final byte[] PBKDF2_SALT = "cpm_db_v1".getBytes();
    private static final int PBKDF2_ITERATIONS = 600_000;

    public CryptoService() {
        this.secureRandom = new SecureRandom();
    }

    // ================================================================
    // AES-256-GCM Encryption / Decryption
    // ================================================================

    /**
     * Encrypt plaintext with AES-256-GCM.
     * 
     * @return { iv (12 bytes) || ciphertext || gcmTag (16 bytes) }
     */
    public byte[] encryptAesGcm(byte[] plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Prepend IV: iv || ciphertext (ciphertext already includes GCM tag)
            byte[] result = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, IV_LENGTH, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new EncryptedModelBlob.CryptoException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypt ciphertext produced by encryptAesGcm.
     * 
     * @param ivAndCiphertext { iv (12 bytes) || ciphertext || gcmTag (16 bytes) }
     */
    public byte[] decryptAesGcm(byte[] ivAndCiphertext, SecretKey key) {
        try {
            byte[] iv = Arrays.copyOf(ivAndCiphertext, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, IV_LENGTH, ivAndCiphertext.length);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new EncryptedModelBlob.CryptoException("AES-GCM decryption failed — key mismatch or data corruption", e);
        }
    }

    // ================================================================
    // HKDF-SHA512 Key Derivation
    // ================================================================

    /**
     * HKDF-Extract using HMAC-SHA512.
     * In Java 21+, this uses javax.crypto.Hkdf.
     */
    public SecretKey hkdfExtract(byte[] salt, byte[] inputKeyMaterial) {
        try {
            // Java 21+ has built-in HKDF via javax.crypto.KDF
            javax.crypto.KDF hkdf = javax.crypto.KDF.getInstance("HKDF-SHA512");
            javax.crypto.spec.HKDFParameterSpec spec =
                javax.crypto.spec.HKDFParameterSpec.ofExtract(salt, inputKeyMaterial);
            byte[] extracted = hkdf.deriveData(spec);
            return new SecretKeySpec(extracted, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new EncryptedModelBlob.CryptoException(
                "HKDF-SHA512 not available — requires Java 21+", e);
        } catch (Exception e) {
            throw new EncryptedModelBlob.CryptoException("HKDF extraction failed", e);
        }
    }

    /**
     * HKDF-Expand to derive a key of specified length.
     */
    public SecretKey hkdfExpand(SecretKey prk, byte[] info, int length) {
        try {
            javax.crypto.KDF hkdf = javax.crypto.KDF.getInstance("HKDF-SHA512");
            javax.crypto.spec.HKDFParameterSpec spec =
                javax.crypto.spec.HKDFParameterSpec.ofExpand(prk, info, length);
            byte[] expanded = hkdf.deriveData(spec);
            return new SecretKeySpec(expanded, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new EncryptedModelBlob.CryptoException(
                "HKDF-SHA512 not available — requires Java 21+", e);
        } catch (Exception e) {
            throw new EncryptedModelBlob.CryptoException("HKDF expansion failed", e);
        }
    }

    // ================================================================
    // Convenience Derivation Methods (matching plan specs)
    // ================================================================

    /**
     * Derive the session base key from the Minecraft shared secret.
     * session_key = HKDF-SHA512(shared_secret, salt="cpm_session_key_v2", info=playerUuid||serverRandom, len=32)
     */
    public SecretKey deriveSessionKey(byte[] mcSharedSecret, byte[] playerUuid,
                                       byte[] serverRandom) {
        SecretKey prk = hkdfExtract(HKDF_SESSION_INFO, mcSharedSecret);

        byte[] info = new byte[playerUuid.length + serverRandom.length];
        System.arraycopy(playerUuid, 0, info, 0, playerUuid.length);
        System.arraycopy(serverRandom, 0, info, playerUuid.length, serverRandom.length);

        return hkdfExpand(prk, info, 32);
    }

    /**
     * Derive a time-window-specific key from the base session key.
     * window_key = HKDF-SHA512(base_session_key, salt="cpm_time_window_v1", info=windowId, len=32)
     */
    public SecretKey deriveTimeWindowKey(SecretKey baseSessionKey, long windowId) {
        SecretKey prk = hkdfExtract(HKDF_TIME_WINDOW_INFO, baseSessionKey.getEncoded());
        return hkdfExpand(prk, String.valueOf(windowId).getBytes(), 32);
    }

    /**
     * Derive the column-level master key from the DB password.
     * master_key = HKDF-SHA512(PBKDF2(password, salt, iters), salt2, info, len=32)
     */
    public SecretKey deriveColumnMasterKey(String dbPassword) {
        try {
            PBEKeySpec spec = new PBEKeySpec(dbPassword.toCharArray(), PBKDF2_SALT,
                PBKDF2_ITERATIONS, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            byte[] pbkdf2Output = factory.generateSecret(spec).getEncoded();

            SecretKey prk = hkdfExtract(HKDF_COLUMN_INFO, pbkdf2Output);
            MemoryProtector.wipe(pbkdf2Output);

            return hkdfExpand(prk, HKDF_COLUMN_INFO, 32);
        } catch (Exception e) {
            throw new EncryptedModelBlob.CryptoException("Column master key derivation failed", e);
        }
    }

    /**
     * Derive a per-row encryption key from the column master key.
     * row_key = HKDF-SHA512(master_key, salt=modelId, info="model_data_row", len=32)
     */
    public SecretKey derivePerRowKey(SecretKey columnMasterKey, long modelId) {
        SecretKey prk = hkdfExtract(String.valueOf(modelId).getBytes(),
            columnMasterKey.getEncoded());
        return hkdfExpand(prk, HKDF_ROW_INFO, 32);
    }

    /**
     * Generate a random AES-256 key.
     */
    public SecretKey generateAesKey() {
        byte[] keyBytes = new byte[32];
        secureRandom.nextBytes(keyBytes);
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Generate secure random bytes for nonces, salts, etc.
     */
    public byte[] secureRandom(int length) {
        return MemoryProtector.secureRandom(length);
    }
}
