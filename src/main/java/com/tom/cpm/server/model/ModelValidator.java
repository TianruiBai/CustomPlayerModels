package com.tom.cpm.server.model;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Validates model data before storage.
 * Checks: magic header, size limits, SHA-256 hash, basic format sanity.
 */
public class ModelValidator {

    /** CPM model file magic byte (ModelDefinitionLoader.HEADER = 0x53) */
    public static final byte HEADER = 0x53;

    private final int maxSizeBytes;
    private final int maxCubeCount;
    private final int maxTexSheetSize;

    public ModelValidator(int maxSizeMb, int maxCubeCount, int maxTexSheetSize) {
        this.maxSizeBytes = maxSizeMb * 1024 * 1024;
        this.maxCubeCount = maxCubeCount;
        this.maxTexSheetSize = maxTexSheetSize;
    }

    /**
     * Validate model data. Throws on failure.
     */
    public ValidationResult validate(byte[] modelData, byte[] expectedSha256) {
        if (modelData == null || modelData.length == 0) {
            return ValidationResult.fail("Model data is empty");
        }

        if (modelData.length > maxSizeBytes) {
            return ValidationResult.fail("Model size " + modelData.length +
                " bytes exceeds maximum " + maxSizeBytes + " bytes");
        }

        if (modelData[0] != HEADER) {
            return ValidationResult.fail("Invalid model header: expected 0x" +
                Integer.toHexString(HEADER & 0xFF) + ", got 0x" +
                Integer.toHexString(modelData[0] & 0xFF));
        }

        // Verify SHA-256 if provided
        if (expectedSha256 != null && expectedSha256.length == 32) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] computed = md.digest(modelData);
                if (!Arrays.equals(computed, expectedSha256)) {
                    return ValidationResult.fail("SHA-256 mismatch — data may be corrupted");
                }
            } catch (Exception e) {
                return ValidationResult.fail("SHA-256 verification failed: " + e.getMessage());
            }
        }

        // Basic size sanity: a valid CPM model is at least 10 bytes
        // (header + minimal part data + ModelPartEnd)
        if (modelData.length < 10) {
            return ValidationResult.fail("Model data too small to be valid (" +
                modelData.length + " bytes)");
        }

        return ValidationResult.ok();
    }

    /**
     * Validate model name.
     */
    public ValidationResult validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return ValidationResult.fail("Model name cannot be empty");
        }
        if (name.length() > 128) {
            return ValidationResult.fail("Model name too long (max 128 characters)");
        }
        // Reject names with path separators or control characters
        for (char c : name.toCharArray()) {
            if (c < 32 || c == '/' || c == '\\' || c == ':') {
                return ValidationResult.fail("Model name contains invalid character: 0x" +
                    Integer.toHexString(c));
            }
        }
        return ValidationResult.ok();
    }

    public record ValidationResult(boolean valid, String error) {
        public static ValidationResult ok() { return new ValidationResult(true, null); }
        public static ValidationResult fail(String error) { return new ValidationResult(false, error); }
    }
}
