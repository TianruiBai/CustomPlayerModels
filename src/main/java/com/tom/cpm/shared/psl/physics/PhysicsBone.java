package com.tom.cpm.shared.psl.physics;

import java.io.IOException;

import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;

/**
 * Defines a physics bone — a model element simulated with simple real-time physics
 * (gravity, damping, stiffness) for secondary motion like swaying hair or bouncing tails.
 */
public class PhysicsBone extends PslElement {

	public enum SimType {
		CHAIN,
		SINGLE,
		CLOTH,
		;
		public static final SimType[] VALUES = values();
	}

	private int parentElementId;
	private SimType simType = SimType.CHAIN;
	private float gravity = 1.0f;
	private float damping = 0.3f;
	private float stiffness = 0.2f;
	private float mass = 0.5f;
	private float windInfluence = 0.6f;
	private float collisionRadius;
	private float maxStretch = 1.05f;
	private float limitAngleX;
	private float limitAngleY;
	private float limitAngleZ;
	private int iterations = 3;
	private boolean inheritAnimation;

	public PhysicsBone() {
	}

	public PhysicsBone(long id, int elementId) {
		super(id, elementId);
	}

	@Override
	public PslElementType getType() {
		return PslElementType.PHYSICS;
	}

	@Override
	protected void writeData(IOHelper out) throws IOException {
		out.writeVarInt(parentElementId);
		out.writeVarInt(simType.ordinal());
		out.writeFloat(gravity);
		out.writeFloat(damping);
		out.writeFloat(stiffness);
		out.writeFloat(mass);
		out.writeFloat(windInfluence);
		out.writeFloat(collisionRadius);
		out.writeFloat(maxStretch);
		out.writeFloat(limitAngleX);
		out.writeFloat(limitAngleY);
		out.writeFloat(limitAngleZ);
		out.writeVarInt(iterations);
		out.writeBoolean(inheritAnimation);
	}

	@Override
	protected void readData(IOHelper in) throws IOException {
		parentElementId = in.readVarInt();
		simType = SimType.VALUES[in.readVarInt()];
		gravity = in.readFloat();
		damping = in.readFloat();
		stiffness = in.readFloat();
		mass = in.readFloat();
		windInfluence = in.readFloat();
		collisionRadius = in.readFloat();
		maxStretch = in.readFloat();
		limitAngleX = in.readFloat();
		limitAngleY = in.readFloat();
		limitAngleZ = in.readFloat();
		iterations = in.readVarInt();
		inheritAnimation = in.readBoolean();
	}

	// --- Getters/Setters ---

	public int getParentElementId() { return parentElementId; }
	public void setParentElementId(int parentElementId) { this.parentElementId = parentElementId; }
	public SimType getSimType() { return simType; }
	public void setSimType(SimType simType) { this.simType = simType; }
	public float getGravity() { return gravity; }
	public void setGravity(float gravity) { this.gravity = gravity; }
	public float getDamping() { return damping; }
	public void setDamping(float damping) { this.damping = damping; }
	public float getStiffness() { return stiffness; }
	public void setStiffness(float stiffness) { this.stiffness = stiffness; }
	public float getMass() { return mass; }
	public void setMass(float mass) { this.mass = mass; }
	public float getWindInfluence() { return windInfluence; }
	public void setWindInfluence(float windInfluence) { this.windInfluence = windInfluence; }
	public float getCollisionRadius() { return collisionRadius; }
	public void setCollisionRadius(float collisionRadius) { this.collisionRadius = collisionRadius; }
	public float getMaxStretch() { return maxStretch; }
	public void setMaxStretch(float maxStretch) { this.maxStretch = maxStretch; }
	public float getLimitAngleX() { return limitAngleX; }
	public void setLimitAngleX(float limitAngleX) { this.limitAngleX = limitAngleX; }
	public float getLimitAngleY() { return limitAngleY; }
	public void setLimitAngleY(float limitAngleY) { this.limitAngleY = limitAngleY; }
	public float getLimitAngleZ() { return limitAngleZ; }
	public void setLimitAngleZ(float limitAngleZ) { this.limitAngleZ = limitAngleZ; }
	public int getIterations() { return iterations; }
	public void setIterations(int iterations) { this.iterations = Math.max(1, Math.min(10, iterations)); }
	public boolean isInheritAnimation() { return inheritAnimation; }
	public void setInheritAnimation(boolean inheritAnimation) { this.inheritAnimation = inheritAnimation; }
}
