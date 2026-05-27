package com.tom.cpm.shared.psl;

import java.io.InputStream;

import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.psl.sound.SoundEmitter;

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
	 * Spawn a Minecraft-native particle if this platform can resolve the particle id.
	 * Custom CPM sprite particles are simulated/rendered by the shared particle runtime instead.
	 */
	default void spawnBuiltinParticle(String particleId, float x, float y, float z, float vx, float vy, float vz) {
	}

	/** Convert a model-local PSL position into the runtime's coordinate space. */
	default Vec3f toWorldPosition(Vec3f modelPosition) {
		return modelPosition != null ? modelPosition : Vec3f.ZERO;
	}

	/** True when Minecraft's native particle engine should handle built-in particle ids. */
	default boolean useBuiltinParticleRenderer() {
		return true;
	}

	/** True when the shared particle list is going to be drawn by the caller. */
	default boolean useSharedParticleRenderer() {
		return false;
	}

	/** Play an attached SFX at the resolved world position. */
	default void playSound(SoundEmitter emitter, Vec3f worldPosition) {
	}

	/**
	 * Sample an editor/runtime particle path by name. Implementations can bind this
	 * to animation curves; the default keeps older runtimes at the emitter origin.
	 */
	default Vec3f sampleParticlePath(String animationName, float progress) {
		return Vec3f.ZERO;
	}

	/**
	 * Check if there is a solid block at the given world-space position.
	 * Used for particle collision.
	 */
	boolean checkBlockCollision(float x, float y, float z);

	/**
	 * Load a particle texture image by its resource identifier.
	 * For custom sprites the id is a project path like "particles/sparkle.png".
	 * For Minecraft built-in particles the id is a namespaced key like "minecraft:flame".
	 * @param particleId the particle resource identifier
	 * @return an Image or null if the texture cannot be loaded
	 */
	default Image loadParticleImage(String particleId) {
		return null;
	}
}
