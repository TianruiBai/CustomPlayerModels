package com.tom.cpm.client.psl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.psl.physics.PhysicsBone;
import com.tom.cpm.shared.psl.physics.PhysicsRuntime;
import com.tom.cpm.shared.psl.physics.PhysicsState;

/**
 * Client-side physics renderer for NeoForge 1.21.
 * Applies physics-computed transforms to model elements before rendering.
 * Phase 3 stub — full integration with ModelRenderManager happens in Phase 5/6.
 */
public class PhysicsRenderer {

	private final PhysicsRuntime runtime = new PhysicsRuntime();

	/**
	 * Simulate physics for all bones and return the computed world-space positions.
	 * Called each frame before model rendering.
	 */
	public void tick(List<PhysicsBone> bones, float dt,
	                 java.util.function.Function<Integer, Vec3f> parentPosFn) {
		runtime.simulate(bones, dt, parentPosFn);
		runtime.resolveCollisions(bones);
	}

	/**
	 * Get the world-space position of a physics bone for rendering.
	 */
	public Vec3f getBonePosition(long boneId) {
		PhysicsState s = runtime.getState(boneId);
		return s != null ? s.position : null;
	}

	public PhysicsState getBoneState(long boneId) {
		return runtime.getState(boneId);
	}

	/**
	 * Get the velocity of a physics bone (used for wind computation in chains).
	 */
	public Vec3f getBoneVelocity(long boneId) {
		PhysicsState s = runtime.getState(boneId);
		return s != null ? s.velocity : Vec3f.ZERO;
	}

	public void clear() {
		runtime.clear();
	}
}
