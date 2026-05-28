package com.tom.cpm.shared.psl.particle;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.psl.IPslRuntime;

/**
 * Shared particle simulation logic.
 * Handles spawning, velocity integration, gravity, aging, and removal of particle instances.
 * Does NOT reference any Minecraft-specific types.
 */
public class ParticleRuntime {

	private final List<ParticleInstance> activeParticles = new ArrayList<>();
	private float spawnTimer;
	private final Random random = new Random();
	private Vec3f lastWorldPos;

	/**
	 * Tick the particle system for one emitter.
	 * @param def       The emitter definition
	 * @param worldPos  The world-space position of the attached element
	 * @param dt        Delta time in seconds
	 * @param runtime   Platform runtime for collision/particle factor
	 */
	public void tick(ParticleEmitter def, Vec3f worldPos, float dt, IPslRuntime runtime) {
		if (def == null || runtime == null) return;
		// Apply playback speed to delta time
		float pdt = dt * def.getPlaybackSpeed();
		if(def.usesVanillaParticleMovement())activeParticles.clear();
		Vec3f targetDelta = null;
		if(lastWorldPos != null) {
			targetDelta = new Vec3f(worldPos.x - lastWorldPos.x, worldPos.y - lastWorldPos.y, worldPos.z - lastWorldPos.z);
		}
		lastWorldPos = copy(worldPos);

		float rate = def.getRate();
		int maxParticles = def.getMaxParticles();
		if (def.isRespectGraphicsSetting()) {
			float factor = runtime.getParticleAmountFactor();
			rate *= factor;
			maxParticles = Math.max(1, (int) (maxParticles * factor));
		}

		// Spawn new particles
		spawnTimer += pdt;
		float spawnInterval = rate > 0 ? 1.0f / rate : Float.MAX_VALUE;
		while (spawnTimer >= spawnInterval && (def.usesVanillaParticleMovement() || activeParticles.size() < maxParticles)) {
			spawnTimer -= spawnInterval;
			spawnParticle(def, worldPos, runtime);
		}
		// Cap the timer to spawnInterval so we don't lose accumulated credit,
		// but also don't allow infinite backlog if at capacity for a long time.
		if (spawnTimer > spawnInterval * 3f) spawnTimer = spawnInterval * 3f;

		// Update existing particles
		Iterator<ParticleInstance> it = activeParticles.iterator();
		while (it.hasNext()) {
			ParticleInstance p = it.next();
			p.age += pdt;

			if (p.age >= p.maxAge) {
				it.remove();
				continue;
			}

			if (def.isInheritTargetMotion() && def.getPathMode() != ParticleEmitter.PathMode.WORLD && targetDelta != null) {
				p.position.x += targetDelta.x;
				p.position.y += targetDelta.y;
				p.position.z += targetDelta.z;
			}

			// Velocity integration with gravity
			p.velocity.y -= def.getGravity() * 9.8f * pdt; // gravity in m/s²

			// Wind: local emitter wind direction * strength
			if (def.getWindStrength() > 0.001f) {
				Vec3f wd = def.getWindDirection();
				float ws = def.getWindStrength();
				p.velocity.x += wd.x * ws * pdt;
				p.velocity.y += wd.y * ws * pdt;
				p.velocity.z += wd.z * ws * pdt;
			}

			// Apply velocity
			p.position.x += p.velocity.x * pdt;
			p.position.y += p.velocity.y * pdt;
			p.position.z += p.velocity.z * pdt;

			// Block collision (if enabled and runtime supports it)
			if (def.isCollision() && runtime.checkBlockCollision(p.position.x, p.position.y, p.position.z)) {
				p.velocity.y = Math.abs(p.velocity.y) * 0.3f; // bounce
				p.velocity.x *= 0.5f;
				p.velocity.z *= 0.5f;
			}

			// Interpolate scale, alpha, rotation
			float progress = p.age / p.maxAge;
			p.scale = lerp(def.getScaleStart(), def.getScaleEnd(), progress);
			p.alpha = lerp(def.getAlphaStart(), def.getAlphaEnd(), progress);

			// Rotation (XYZ)
			switch (def.getRotationMode()) {
				case NONE:
					break;
				case LINEAR:
					p.rotX = lerp(p.rotStartX, p.rotEndX, progress);
					p.rotY = lerp(p.rotStartY, p.rotEndY, progress);
					p.rotZ = lerp(p.rotStartZ, p.rotEndZ, progress);
					break;
				case SPIN:
					p.rotX += p.rotSpeedX * pdt;
					p.rotY += p.rotSpeedY * pdt;
					p.rotZ += p.rotSpeedZ * pdt;
					break;
			}
			if(def.getPathMode() == ParticleEmitter.PathMode.ANIMATION_PATH) {
				Vec3f nextOffset = copy(runtime.sampleParticlePath(def.getPathAnimation(), progress));
				p.position.x += nextOffset.x - p.pathOffset.x;
				p.position.y += nextOffset.y - p.pathOffset.y;
				p.position.z += nextOffset.z - p.pathOffset.z;
				p.pathOffset = nextOffset;
			}
		}
	}

	private void spawnParticle(ParticleEmitter def, Vec3f worldPos, IPslRuntime runtime) {
		ParticleInstance p = new ParticleInstance();

		// Position: emitter origin + configured offset + random offset within emitter volume
		Vec3f emitterPos = new Vec3f(
			worldPos.x + def.getOffset().x,
			worldPos.y + def.getOffset().y,
			worldPos.z + def.getOffset().z
		);
		Vec3f offset = Vec3f.ZERO;
		switch (def.getEmitterType()) {
			case POINT:
				offset = Vec3f.ZERO;
				break;
			case BOX:
				offset = new Vec3f(
					(def.getEmitterSize().x * (random.nextFloat() - 0.5f)),
					(def.getEmitterSize().y * (random.nextFloat() - 0.5f)),
					(def.getEmitterSize().z * (random.nextFloat() - 0.5f))
				);
				break;
			case SPHERE:
				float theta = random.nextFloat() * (float) Math.PI * 2;
				float phi = random.nextFloat() * (float) Math.PI;
				float r = def.getEmitterSize().x * random.nextFloat(); // use x as radius
				offset = new Vec3f(
					r * (float) Math.sin(phi) * (float) Math.cos(theta),
					r * (float) Math.cos(phi),
					r * (float) Math.sin(phi) * (float) Math.sin(theta)
				);
				break;
		}

		p.position = new Vec3f(emitterPos.x + offset.x, emitterPos.y + offset.y, emitterPos.z + offset.z);
		if(def.getPathMode() == ParticleEmitter.PathMode.ANIMATION_PATH) {
			p.pathOffset = copy(runtime.sampleParticlePath(def.getPathAnimation(), 0));
			p.position.x += p.pathOffset.x;
			p.position.y += p.pathOffset.y;
			p.position.z += p.pathOffset.z;
		}

		// Velocity with variation
		float var = def.getVelocityVariation();
		p.velocity = new Vec3f(
			def.getVelocity().x * (1 + (random.nextFloat() * 2 - 1) * var),
			def.getVelocity().y * (1 + (random.nextFloat() * 2 - 1) * var),
			def.getVelocity().z * (1 + (random.nextFloat() * 2 - 1) * var)
		);

		// Lifetime
		p.maxAge = def.getLifeMin() + random.nextFloat() * (def.getLifeMax() - def.getLifeMin());
		p.age = 0;
		p.scale = def.getScaleStart();
		p.alpha = def.getAlphaStart();
		p.color = def.getColorStart();

		// Initial rotation (XYZ)
		if (def.isRandomRotationStart()) {
			p.rotStartX = random.nextFloat() * 360f;
			p.rotStartY = random.nextFloat() * 360f;
			p.rotStartZ = random.nextFloat() * 360f;
			p.rotEndX = p.rotStartX + (def.getRotationEndX() - def.getRotationStartX());
			p.rotEndY = p.rotStartY + (def.getRotationEndY() - def.getRotationStartY());
			p.rotEndZ = p.rotStartZ + (def.getRotationEndZ() - def.getRotationStartZ());
		} else {
			p.rotStartX = def.getRotationStartX();
			p.rotStartY = def.getRotationStartY();
			p.rotStartZ = def.getRotationStartZ();
			p.rotEndX = def.getRotationEndX();
			p.rotEndY = def.getRotationEndY();
			p.rotEndZ = def.getRotationEndZ();
		}
		p.rotX = p.rotStartX;
		p.rotY = p.rotStartY;
		p.rotZ = p.rotStartZ;
		p.rotSpeedX = def.getRotationSpeedX();
		p.rotSpeedY = def.getRotationSpeedY();
		p.rotSpeedZ = def.getRotationSpeedZ();

		if(def.usesVanillaParticleMovement() || (def.isMinecraftParticle() && runtime.useBuiltinParticleRenderer())) {
			runtime.spawnBuiltinParticle(def.getMinecraftParticle(), p.position.x, p.position.y, p.position.z, p.velocity.x, p.velocity.y, p.velocity.z);
		} else if(!def.isMinecraftParticle() && !runtime.useSharedParticleRenderer()) {
			runtime.spawnBuiltinParticle("minecraft:poof", p.position.x, p.position.y, p.position.z, p.velocity.x, p.velocity.y, p.velocity.z);
		} else {
			activeParticles.add(p);
		}
	}

	/**
	 * Get the list of currently active particles for rendering.
	 */
	public List<ParticleInstance> getActiveParticles() {
		return activeParticles;
	}

	/**
	 * Clear all particles (e.g., when model unloads).
	 */
	public void clear() {
		activeParticles.clear();
		spawnTimer = 0;
		lastWorldPos = null;
	}

	private static Vec3f copy(Vec3f v) {
		return v != null ? new Vec3f(v.x, v.y, v.z) : Vec3f.ZERO;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static int lerpColor(int a, int b, float t) {
		int ar = (a >> 24) & 0xFF, ag = (a >> 16) & 0xFF, ab = (a >> 8) & 0xFF, aa = a & 0xFF;
		int br = (b >> 24) & 0xFF, bg = (b >> 16) & 0xFF, bb = (b >> 8) & 0xFF, ba = b & 0xFF;
		int rr = (int) (ar + (br - ar) * t);
		int rg = (int) (ag + (bg - ag) * t);
		int rb = (int) (ab + (bb - ab) * t);
		int ra = (int) (aa + (ba - aa) * t);
		return (rr << 24) | (rg << 16) | (rb << 8) | ra;
	}
}
