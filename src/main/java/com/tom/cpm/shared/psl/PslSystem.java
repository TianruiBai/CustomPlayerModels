package com.tom.cpm.shared.psl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.psl.light.LightEmitter;
import com.tom.cpm.shared.psl.light.LightRuntime;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.particle.ParticleInstance;
import com.tom.cpm.shared.psl.particle.ParticleRuntime;
import com.tom.cpm.shared.psl.physics.PhysicsBone;
import com.tom.cpm.shared.psl.physics.PhysicsRuntime;
import com.tom.cpm.shared.psl.sound.SoundEmitter;
import com.tom.cpm.shared.psl.sound.SoundRuntime;

/**
 * Core system managing all PSL (Particle · Physics · Sound · Light) elements for a model.
 * Provides registration, iteration, and lifecycle management.
 */
public class PslSystem {

	private final List<PslElement> elements = new ArrayList<>();
	private boolean dirty;
	private transient Map<Long, ParticleRuntime> particleRuntimes;
	private transient Map<Long, SoundRuntime> soundRuntimes;
	private transient PhysicsRuntime physicsRuntime;
	private transient LightRuntime lightRuntime;
	private transient Map<Long, Boolean> activeDynamicLights;
	private transient long tickCounter;

	/**
	 * Add a PSL element to the system.
	 */
	public void addElement(PslElement element) {
		elements.add(element);
		dirty = true;
	}

	/**
	 * Remove a PSL element by ID.
	 */
	public boolean removeElement(long id) {
		boolean removed = elements.removeIf(e -> e.getId() == id);
		if (removed) {
			dirty = true;
			clearRuntimeState();
		}
		return removed;
	}

	/**
	 * Get an unmodifiable view of all PSL elements.
	 */
	public List<PslElement> getElements() {
		return Collections.unmodifiableList(elements);
	}

	/**
	 * Get all elements of a specific type.
	 */
	@SuppressWarnings("unchecked")
	public <T extends PslElement> List<T> getElementsOfType(PslElementType type) {
		List<T> result = new ArrayList<>();
		for (PslElement e : elements) {
			if (e.getType() == type) {
				result.add((T) e);
			}
		}
		return result;
	}

	/**
	 * Find an element by its ID.
	 */
	public PslElement getElement(long id) {
		for (PslElement e : elements) {
			if (e.getId() == id) return e;
		}
		return null;
	}

	/**
	 * Replace all elements with a new list.
	 */
	public void setElements(List<PslElement> elements) {
		this.elements.clear();
		if (elements != null) {
			this.elements.addAll(elements);
		}
		dirty = true;
		clearRuntimeState();
	}

	/**
	 * Clear all elements.
	 */
	public void clear() {
		elements.clear();
		dirty = true;
		clearRuntimeState();
	}

	/**
	 * Check if any elements exist.
	 */
	public boolean isEmpty() {
		return elements.isEmpty();
	}

	/**
	 * Get the total number of elements.
	 */
	public int size() {
		return elements.size();
	}

	/**
	 * Check if the system has been modified since last save.
	 */
	public boolean isDirty() {
		return dirty;
	}

	/**
	 * Mark as clean (e.g., after successful save).
	 */
	public void markClean() {
		dirty = false;
	}

	/**
	 * Tick the PSL system (called each game tick from AnimationEngine).
	 * This is a placeholder for Phase 2+ runtime integration.
	 */
	public void tick(PslTriggerState state, IPslRuntime runtime) {
		tick(state, runtime, id -> Vec3f.ZERO, 1 / 20f);
	}

	/**
	 * Tick active PSL elements with a target-position lookup supplied by the renderer/editor.
	 */
	public void tick(PslTriggerState state, IPslRuntime runtime, Function<Integer, Vec3f> positionLookup, float dt) {
		tick(state, runtime, positionLookup, dt, false);
	}

	public void tickPreview(IPslRuntime runtime, Function<Integer, Vec3f> positionLookup, float dt) {
		tick(null, runtime, positionLookup, dt, true);
	}

	private void tick(PslTriggerState state, IPslRuntime runtime, Function<Integer, Vec3f> positionLookup, float dt, boolean forceActive) {
		if(runtime == null || elements.isEmpty())return;
		ensureRuntimeState();
		tickCounter++;

		List<PhysicsBone> physicsBones = getElementsOfType(PslElementType.PHYSICS);
		if(!physicsBones.isEmpty()) {
			physicsRuntime.simulate(physicsBones, dt, id -> runtime.toWorldPosition(positionLookup.apply(id)));
			physicsRuntime.resolveCollisions(physicsBones);
		}

		for(PslElement element : elements) {
			boolean active = forceActive || element.isActive(state);
			Vec3f worldPos = runtime.toWorldPosition(positionLookup.apply(element.getElementId()));
			switch (element.getType()) {
				case PARTICLE:
					if(active)particleRuntimes.computeIfAbsent(element.getId(), id -> new ParticleRuntime()).tick((ParticleEmitter) element, worldPos, dt, runtime);
					break;
				case SOUND: {
					SoundRuntime soundRuntime = soundRuntimes.computeIfAbsent(element.getId(), id -> new SoundRuntime());
					SoundEmitter sound = (SoundEmitter) element;
					if(soundRuntime.shouldPlay(sound, tickCounter, active)) {
						runtime.playSound(sound, worldPos);
						soundRuntime.markTriggered(tickCounter);
					} else if(!active) {
						soundRuntime.reset();
					}
					break;
				}
				case LIGHT:
					updateDynamicLight((LightEmitter) element, active, worldPos, runtime);
					break;
				default:
					break;
			}
		}
	}

	public List<ParticleInstance> getParticleInstances(ParticleEmitter emitter) {
		if(particleRuntimes == null || emitter == null)return Collections.emptyList();
		ParticleRuntime runtime = particleRuntimes.get(emitter.getId());
		return runtime != null ? runtime.getActiveParticles() : Collections.emptyList();
	}

	public PhysicsRuntime getPhysicsRuntime() {
		ensureRuntimeState();
		return physicsRuntime;
	}

	public void clearRuntimeState() {
		if(particleRuntimes != null)particleRuntimes.values().forEach(ParticleRuntime::clear);
		if(soundRuntimes != null)soundRuntimes.values().forEach(SoundRuntime::reset);
		if(physicsRuntime != null)physicsRuntime.clear();
		if(activeDynamicLights != null)activeDynamicLights.clear();
		tickCounter = 0;
	}

	private void ensureRuntimeState() {
		if(particleRuntimes == null)particleRuntimes = new HashMap<>();
		if(soundRuntimes == null)soundRuntimes = new HashMap<>();
		if(physicsRuntime == null)physicsRuntime = new PhysicsRuntime();
		if(lightRuntime == null)lightRuntime = new LightRuntime();
		if(activeDynamicLights == null)activeDynamicLights = new HashMap<>();
	}

	private void updateDynamicLight(LightEmitter light, boolean active, Vec3f worldPos, IPslRuntime runtime) {
		if(!runtime.isDynamicLightSupported())return;
		boolean wasActive = activeDynamicLights.getOrDefault(light.getId(), false);
		if(!light.isDynamic()) {
			if(wasActive)runtime.unregisterDynamicLight((int)(light.getId() & 0x7fffffff));
			activeDynamicLights.put(light.getId(), false);
			return;
		}
		float intensity = lightRuntime.getCurrentIntensity(light, tickCounter, active);
		int runtimeId = (int)(light.getId() & 0x7fffffff);
		if(intensity > 0.01f) {
			if(wasActive)runtime.updateDynamicLight(runtimeId, worldPos.x, worldPos.y, worldPos.z);
			else runtime.registerDynamicLight(runtimeId, worldPos.x, worldPos.y, worldPos.z, light.getColor(), intensity * 15, light.getRadius());
			activeDynamicLights.put(light.getId(), true);
		} else if(wasActive) {
			runtime.unregisterDynamicLight(runtimeId);
			activeDynamicLights.put(light.getId(), false);
		}
	}
}
