package com.tom.cpm.server.compat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Utility for working with Minecraft offline-mode UUIDs.
 * 
 * In offline mode, Minecraft derives UUIDs deterministically from player names:
 *   UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(UTF_8))
 * 
 * This is the same algorithm used by Mojang's Yggdrasil authentication library.
 */
public final class OfflineUUIDUtil {

    private static final String OFFLINE_PREFIX = "OfflinePlayer:";

    private OfflineUUIDUtil() {}

    /**
     * Generate the offline-mode UUID for a player name.
     * This is the same UUID that Minecraft uses in offline mode.
     */
    public static UUID getOfflineUUID(String playerName) {
        return UUID.nameUUIDFromBytes(
            (OFFLINE_PREFIX + playerName).getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Generate the offline-mode UUID string for a player name.
     */
    public static String getOfflineUUIDString(String playerName) {
        return getOfflineUUID(playerName).toString();
    }

    /**
     * Check if a UUID appears to be an offline-mode UUID.
     * Offline UUIDs are version 3 (MD5-based name UUID).
     */
    public static boolean isOfflineUUID(UUID uuid) {
        return uuid.version() == 3;
    }

    /**
     * Check if a UUID string appears to be an offline-mode UUID.
     */
    public static boolean isOfflineUUID(String uuidStr) {
        try {
            return isOfflineUUID(UUID.fromString(uuidStr));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
