package com.tom.cpm.server.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.tom.cpm.shared.util.Log;

/**
 * Manages server cryptographic keys using JCEKS keystore.
 * 
 * Keys managed:
 * - DB master key (AES-256): for column-level encryption
 * - DB file password: for H2 file-level encryption
 * 
 * Keys are auto-generated on first run and stored in a JCEKS keystore
 * with restricted file permissions (the OS must enforce 0600).
 */
public class KeyManager {

    private static final String KEYSTORE_TYPE = "JCEKS";
    private static final String DB_MASTER_KEY_ALIAS = "cpm-db-master-key";
    private static final String DB_FILE_PASSWORD_ALIAS = "cpm-db-file-password";
    private static final String SIGNING_KEY_ALIAS = "cpm-signing-key";

    private final File keystoreFile;
    private final CryptoService crypto;
    private KeyStore keyStore;
    private char[] keystorePassword;

    private SecretKey dbMasterKey;
    private String dbFilePassword;
    private SecretKey signingKey;

    public KeyManager(File serverDir, CryptoService crypto) {
        this.keystoreFile = new File(serverDir, "cpm_keystore.jks");
        this.crypto = crypto;
    }

    /**
     * Initialize or load the keystore. Auto-generates keys if missing.
     * 
     * @param keystorePassword the password protecting the keystore file
     */
    public void initialize(char[] keystorePassword) throws IOException {
        this.keystorePassword = keystorePassword;
        try {
            this.keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        } catch (java.security.KeyStoreException e) {
            throw new IOException("Keystore type not available: " + KEYSTORE_TYPE, e);
        }

        try {
            if (keystoreFile.exists()) {
                try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                    keyStore.load(fis, keystorePassword);
                }
                Log.info("Loaded existing keystore: " + keystoreFile.getAbsolutePath());
            } else {
                keyStore.load(null, keystorePassword); // Create empty
                Log.info("Created new keystore: " + keystoreFile.getAbsolutePath());
            }
        } catch (Exception e) {
            throw new IOException("Failed to load keystore — wrong password or corrupt file: "
                + keystoreFile.getAbsolutePath(), e);
        }

        // Load or generate DB master key
        try {
            if (keyStore.containsAlias(DB_MASTER_KEY_ALIAS)) {
                KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
                    keyStore.getEntry(DB_MASTER_KEY_ALIAS,
                        new KeyStore.PasswordProtection(keystorePassword));
                this.dbMasterKey = entry.getSecretKey();
                Log.info("Loaded DB master key from keystore");
            } else {
                this.dbMasterKey = crypto.generateAesKey();
                keyStore.setEntry(DB_MASTER_KEY_ALIAS,
                    new KeyStore.SecretKeyEntry(dbMasterKey),
                    new KeyStore.PasswordProtection(keystorePassword));
                save();
                Log.warn("Generated new DB master key — STORE THIS SAFELY. If lost, models are irrecoverable.");
            }
        } catch (java.security.NoSuchAlgorithmException | java.security.UnrecoverableEntryException
                | java.security.KeyStoreException e) {
            throw new IOException("Failed to access DB master key in keystore", e);
        }

        // Load or generate DB file password
        try {
            if (keyStore.containsAlias(DB_FILE_PASSWORD_ALIAS)) {
                KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
                    keyStore.getEntry(DB_FILE_PASSWORD_ALIAS,
                        new KeyStore.PasswordProtection(keystorePassword));
                this.dbFilePassword = new String(entry.getSecretKey().getEncoded());
            } else {
                byte[] pwdBytes = crypto.secureRandom(32);
                this.dbFilePassword = java.util.Base64.getEncoder().encodeToString(pwdBytes);
                MemoryProtector.wipe(pwdBytes);

                SecretKey pwdKey = new SecretKeySpec(
                    dbFilePassword.getBytes(java.nio.charset.StandardCharsets.UTF_8), "RAW");
                keyStore.setEntry(DB_FILE_PASSWORD_ALIAS,
                    new KeyStore.SecretKeyEntry(pwdKey),
                    new KeyStore.PasswordProtection(keystorePassword));
                save();
            }
        } catch (java.security.NoSuchAlgorithmException | java.security.UnrecoverableEntryException
                | java.security.KeyStoreException e) {
            throw new IOException("Failed to access DB file password in keystore", e);
        }

        // Load or generate signing key (Stage 2.4)
        try {
            if (keyStore.containsAlias(SIGNING_KEY_ALIAS)) {
                KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
                    keyStore.getEntry(SIGNING_KEY_ALIAS,
                        new KeyStore.PasswordProtection(keystorePassword));
                this.signingKey = entry.getSecretKey();
                Log.info("Loaded signing key from keystore");
            } else {
                this.signingKey = crypto.generateAesKey();
                keyStore.setEntry(SIGNING_KEY_ALIAS,
                    new KeyStore.SecretKeyEntry(signingKey),
                    new KeyStore.PasswordProtection(keystorePassword));
                save();
                Log.info("Generated new signing key for model attestation");
            }
        } catch (java.security.NoSuchAlgorithmException | java.security.UnrecoverableEntryException
                | java.security.KeyStoreException e) {
            throw new IOException("Failed to access signing key in keystore", e);
        }

        // Restrict file permissions on the keystore
        if (keystoreFile.exists()) {
            keystoreFile.setReadable(true, false);  // owner only
            keystoreFile.setWritable(true, false);  // owner only
            keystoreFile.setExecutable(false);
        }
    }

    public SecretKey getDbMasterKey() {
        return dbMasterKey;
    }

    public String getDbFilePassword() {
        return dbFilePassword;
    }

    public SecretKey getSigningKey() {
        return signingKey;
    }

    private void save() throws IOException {
        try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
            keyStore.store(fos, keystorePassword);
        } catch (Exception e) {
            throw new IOException("Failed to save keystore", e);
        }
    }
}
