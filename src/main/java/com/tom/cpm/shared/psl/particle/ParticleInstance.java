package com.tom.cpm.shared.psl.particle;

import com.tom.cpl.math.Vec3f;

/**
 * Runtime state of a single particle.
 * All fields are plain Java types — no Minecraft dependencies.
 */
public class ParticleInstance {
	public Vec3f position = new Vec3f();
	public Vec3f velocity = new Vec3f();
	public Vec3f pathOffset = new Vec3f();
	public float age;
	public float maxAge = 1;
	public float scale = 1;
	public int color = 0xFFFFFFFF;
	public float alpha = 1;
	public float rotX, rotY, rotZ;
	public float rotSpeedX, rotSpeedY, rotSpeedZ;
}
