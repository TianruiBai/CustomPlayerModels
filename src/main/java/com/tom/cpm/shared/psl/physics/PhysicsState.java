package com.tom.cpm.shared.psl.physics;

import com.tom.cpl.math.Vec3f;

/**
 * Runtime state for a single physics bone.
 * Uses Verlet integration (storing previous position) for stability.
 */
public class PhysicsState {
	public Vec3f position = new Vec3f();
	public Vec3f previousPosition = new Vec3f();
	public Vec3f velocity = new Vec3f();
	public Vec3f restPosition = new Vec3f();
	public Vec3f restRotation = new Vec3f();
	public boolean initialized;

	public void reset() {
		initialized = false;
		velocity = new Vec3f();
	}
}
