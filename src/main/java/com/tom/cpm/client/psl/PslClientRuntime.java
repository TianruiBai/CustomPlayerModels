package com.tom.cpm.client.psl;

import java.io.InputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import com.tom.cpm.shared.psl.IPslRuntime;

/**
 * NeoForge 1.21 client implementation of IPslRuntime.
 * Bridges shared PSL logic with Minecraft client APIs.
 */
public class PslClientRuntime implements IPslRuntime {

	private final Minecraft mc;

	public PslClientRuntime() {
		this.mc = Minecraft.getInstance();
	}

	@Override
	public InputStream getResource(String path) {
		// Phase 3+: Load from model's project cache
		return null;
	}

	@Override
	public float getParticleAmountFactor() {
		if (mc.options == null || mc.options.particles() == null) return 1.0f;
		return switch (mc.options.particles().get()) {
			case ALL -> 1.0f;
			case DECREASED -> 0.5f;
			case MINIMAL -> 0.25f;
		};
	}

	@Override
	public boolean isShaderPackActive() {
		// Phase 5+: Detect Iris/OptiFine shader packs
		return false;
	}

	@Override
	public void registerDynamicLight(int entityId, float x, float y, float z,
	                                  int color, float level, float radius) {
		// Phase 5+: Implement dynamic light registration
	}

	@Override
	public void updateDynamicLight(int entityId, float x, float y, float z) {
		// Phase 5+: Implement dynamic light position update
	}

	@Override
	public void unregisterDynamicLight(int entityId) {
		// Phase 5+: Implement dynamic light removal
	}

	@Override
	public boolean isDynamicLightSupported() {
		return false; // Phase 5+
	}

	@Override
	public void spawnBuiltinParticle(String particleId, float x, float y, float z, float vx, float vy, float vz) {
		if(mc.level == null || particleId == null || particleId.isEmpty())return;
		try {
			var type = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse(particleId));
			if(type instanceof SimpleParticleType) {
				mc.level.addParticle((SimpleParticleType) type, x, y, z, vx, vy, vz);
			}
		} catch (Exception ignored) {
		}
	}

	@Override
	public boolean checkBlockCollision(float x, float y, float z) {
		if (mc.level == null) return false;
		var blockPos = new net.minecraft.core.BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		return !mc.level.getBlockState(blockPos).isAir();
	}
}
