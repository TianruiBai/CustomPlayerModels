package com.tom.cpm.shared.psl.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpl.math.Vec3f;

/**
 * Shared physics simulation using Verlet integration.
 * Simulates gravity, damping, stiffness, angular limits, and self-collision
 * for physics bones attached to model elements.
 */
public class PhysicsRuntime {

	private final Map<Long, PhysicsState> states = new HashMap<>();
	private List<PhysicsBone> sortedBones;

	/**
	 * Sort physics bones into parent→child topological order and detect cycles.
	 * @return true if valid ordering, false if a cycle was detected.
	 */
	public boolean prepareOrder(List<PhysicsBone> bones) {
		sortedBones = new ArrayList<>();
		Map<Integer, PhysicsBone> boneMap = new HashMap<>();
		for (PhysicsBone b : bones) boneMap.put(b.getElementId(), b);

		// Simple topological sort: bones with no physics parent come first,
		// then children of those, etc.
		List<PhysicsBone> remaining = new ArrayList<>(bones);
		int maxPasses = bones.size() + 1;
		int pass = 0;

		while (!remaining.isEmpty() && pass < maxPasses) {
			pass++;
			for (int i = remaining.size() - 1; i >= 0; i--) {
				PhysicsBone b = remaining.get(i);
				int parentId = b.getParentElementId();
				PhysicsBone parentBone = boneMap.get(parentId);

				if (parentBone == null) {
					// Parent is not a physics bone → this is a root-level physics bone
					sortedBones.add(b);
					remaining.remove(i);
				} else if (sortedBones.contains(parentBone)) {
					// Parent already sorted → can sort this one now
					sortedBones.add(b);
					remaining.remove(i);
				}
				// else: parent not yet sorted, try again next pass
			}
		}

		if (!remaining.isEmpty()) {
			// Cycle detected — add remaining in arbitrary order for graceful degradation
			sortedBones.addAll(remaining);
			return false;
		}
		return true;
	}

	/**
	 * Simulate one tick for all physics bones.
	 * @param dt           Delta time in seconds
	 * @param parentPosFn  Function to get the world-space position of a parent element by ID
	 */
	public void simulate(List<PhysicsBone> bones, float dt,
	                     java.util.function.Function<Integer, Vec3f> parentPosFn) {
		if (bones.isEmpty()) return;

		// Re-sort if needed
		if (sortedBones == null || sortedBones.size() != bones.size()) {
			prepareOrder(bones);
		}

		for (PhysicsBone bone : sortedBones) {
			PhysicsState state = states.computeIfAbsent(bone.getId(), k -> new PhysicsState());
			simulateOne(bone, state, dt, parentPosFn);
		}
	}

	private void simulateOne(PhysicsBone bone, PhysicsState s, float dt,
	                         java.util.function.Function<Integer, Vec3f> parentPosFn) {
		Vec3f parentPos = parentPosFn.apply(bone.getParentElementId());
		if (parentPos == null) parentPos = Vec3f.ZERO;

		float damp = Math.max(0, Math.min(1, bone.getDamping()));
		float stiff = Math.max(0, Math.min(1, bone.getStiffness()));

		// Initialize on first frame
		if (!s.initialized) {
			s.position = new Vec3f(parentPos.x, parentPos.y - 2, parentPos.z); // offset below parent
			s.previousPosition = new Vec3f(s.position.x, s.position.y, s.position.z);
			s.restPosition = new Vec3f(s.position.x, s.position.y, s.position.z);
			s.initialized = true;
		}

		// Sub-step iterations for stability
		int iters = Math.max(1, Math.min(10, bone.getIterations()));
		float subDt = dt / iters;

		for (int i = 0; i < iters; i++) {
			// Verlet integration
			float velX = (s.position.x - s.previousPosition.x) * damp;
			float velY = (s.position.y - s.previousPosition.y) * damp;
			float velZ = (s.position.z - s.previousPosition.z) * damp;

			// Apply gravity
			velY -= bone.getGravity() * 9.8f * subDt * subDt;

			// Stiffness: nudge toward rest position
			float stiffForce = stiff * subDt;
			velX += (s.restPosition.x - s.position.x) * stiffForce;
			velY += (s.restPosition.y - s.position.y) * stiffForce;
			velZ += (s.restPosition.z - s.position.z) * stiffForce;

			// Store previous, integrate
			s.previousPosition = new Vec3f(s.position.x, s.position.y, s.position.z);
			s.position = new Vec3f(
				s.position.x + velX,
				s.position.y + velY,
				s.position.z + velZ
			);

			// Constrain max stretch from parent
			float dx = s.position.x - parentPos.x;
			float dy = s.position.y - parentPos.y;
			float dz = s.position.z - parentPos.z;
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

			float restDist = (float) Math.sqrt(
				(s.restPosition.x - parentPos.x) * (s.restPosition.x - parentPos.x) +
				(s.restPosition.y - parentPos.y) * (s.restPosition.y - parentPos.y) +
				(s.restPosition.z - parentPos.z) * (s.restPosition.z - parentPos.z)
			);
			if (restDist < 0.001f) restDist = 2.0f; // default rest distance

			float maxDist = restDist * bone.getMaxStretch();
			if (dist > maxDist && dist > 0.001f) {
				float scale = maxDist / dist;
				s.position = new Vec3f(
					parentPos.x + dx * scale,
					parentPos.y + dy * scale,
					parentPos.z + dz * scale
				);
			}

			// Clamp angular limits (simplified: clamp per-axis offset)
			if (bone.getLimitAngleX() > 0) {
				float maxOff = (float) Math.tan(Math.toRadians(bone.getLimitAngleX())) * maxDist;
				float offX = s.position.x - parentPos.x;
				if (Math.abs(offX) > maxOff) offX = Math.signum(offX) * maxOff;
				s.position = new Vec3f(parentPos.x + offX, s.position.y, s.position.z);
			}
			if (bone.getLimitAngleY() > 0) {
				float maxOff = (float) Math.tan(Math.toRadians(bone.getLimitAngleY())) * maxDist;
				float offY = s.position.y - parentPos.y;
				if (Math.abs(offY) > maxOff) offY = Math.signum(offY) * maxOff;
				s.position = new Vec3f(s.position.x, parentPos.y + offY, s.position.z);
			}
			if (bone.getLimitAngleZ() > 0) {
				float maxOff = (float) Math.tan(Math.toRadians(bone.getLimitAngleZ())) * maxDist;
				float offZ = s.position.z - parentPos.z;
				if (Math.abs(offZ) > maxOff) offZ = Math.signum(offZ) * maxOff;
				s.position = new Vec3f(s.position.x, s.position.y, parentPos.z + offZ);
			}
		}

		// Store velocity for wind/stiffness computation
		s.velocity = new Vec3f(
			s.position.x - s.previousPosition.x,
			s.position.y - s.previousPosition.y,
			s.position.z - s.previousPosition.z
		);
	}

	/**
	 * Resolve self-collisions between physics bones (sphere-sphere).
	 */
	public void resolveCollisions(List<PhysicsBone> bones) {
		for (int i = 0; i < bones.size(); i++) {
			PhysicsBone a = bones.get(i);
			if (a.getCollisionRadius() <= 0) continue;
			PhysicsState sa = states.get(a.getId());
			if (sa == null) continue;

			for (int j = i + 1; j < bones.size(); j++) {
				PhysicsBone b = bones.get(j);
				if (b.getCollisionRadius() <= 0) continue;
				PhysicsState sb = states.get(b.getId());
				if (sb == null) continue;

				float dx = sa.position.x - sb.position.x;
				float dy = sa.position.y - sb.position.y;
				float dz = sa.position.z - sb.position.z;
				float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				float minDist = a.getCollisionRadius() + b.getCollisionRadius();

				if (dist < minDist && dist > 0.001f) {
					float push = (minDist - dist) * 0.5f;
					float nx = dx / dist;
					float ny = dy / dist;
					float nz = dz / dist;
					sa.position = new Vec3f(sa.position.x + nx * push, sa.position.y + ny * push, sa.position.z + nz * push);
					sb.position = new Vec3f(sb.position.x - nx * push, sb.position.y - ny * push, sb.position.z - nz * push);
				}
			}
		}
	}

	/**
	 * Get the current world-space position of a physics bone.
	 */
	public PhysicsState getState(long boneId) {
		return states.get(boneId);
	}

	/**
	 * Clear all physics state.
	 */
	public void clear() {
		states.clear();
		sortedBones = null;
	}
}
