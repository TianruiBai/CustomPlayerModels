package com.tom.cpm.shared.psl;

import java.io.InputStream;

/**
 * Platform-agnostic runtime interface for PSL operations.
 * Each platform port (NeoForge 1.21, Fabric 1.20, Forge 1.16, etc.)
 * implements this to bridge shared PSL logic with Minecraft-specific APIs.
 */
public interface IPslRuntime {

	// --- Resource access ---

	/**
	 * Get a resource from the model's project cache (particle textures, sound files, etc.).
	 * @param path Path within the project (e.g., "particles/sparkle.png", "sounds/swoosh.ogg")
	 * @return InputStream or null if not found
	 */
	InputStream getResource(String path);

	// --- Graphics settings ---

	/**
	 * Get the current particle amount factor based on Minecraft's graphics setting.
	 * @return 1.0 (ALL), 0.5 (DECREASED), 0.25 (MINIMAL)
	 */
	float getParticleAmountFactor();

	/**
	 * Check if a shader pack is currently active.
	 */
	boolean isShaderPackActive();

	// --- Dynamic Light ---

	/**
	 * Register a dynamic light source at a world-space position.
	 * The light casts actual illumination on blocks and entities.
	 *
	 * @param entityId  The entity this light belongs to (for lifecycle management)
	 * @param x, y, z   World-space position
	 * @param color     RGB packed int (no alpha)
	 * @param level     Light level (0.0–15.0)
	 * @param radius    Light radius in blocks
	 */
	void registerDynamicLight(int entityId, float x, float y, float z,
	                          int color, float level, float radius);

	/**
	 * Update the position of a registered dynamic light.
	 */
	void updateDynamicLight(int entityId, float x, float y, float z);

	/**
	 * Remove a registered dynamic light.
	 */
	void unregisterDynamicLight(int entityId);

	/**
	 * Check if dynamic lights are supported on this platform/configuration.
	 */
	boolean isDynamicLightSupported();

	// --- Block collision ---

	/**
	 * Check if there is a solid block at the given world-space position.
	 * Used for particle collision.
	 */
	boolean checkBlockCollision(float x, float y, float z);
}
