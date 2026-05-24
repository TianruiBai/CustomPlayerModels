package com.tom.cpm.shared.editor.ysm;

import java.util.List;
import java.util.Map;

import com.tom.cpl.math.BoundingBox;
import com.tom.cpl.math.Mat3f;
import com.tom.cpl.math.Rotation;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockBone;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockCube;
import com.tom.cpm.shared.model.PlayerModelParts;

/**
 * Coordinate conversion utilities for YSM to CPM import.
 *
 * YSM/Bedrock and CPM use the SAME pixel-unit coordinate system (Y-up).
 * Positions convert 1:1. The only adjustment is subtracting the YSM
 * reference pivot for bones re-parented to different CPM root parts.
 */
public class YsmCoordUtil {

	private static final Map<PlayerModelParts, Vec3f> VANILLA_PART_POS = Map.of(
		PlayerModelParts.HEAD,       new Vec3f(0, 0, 0),
		PlayerModelParts.BODY,       new Vec3f(0, 0, 0),
		PlayerModelParts.LEFT_ARM,   new Vec3f(5, 2, 0),
		PlayerModelParts.RIGHT_ARM,  new Vec3f(-5, 2, 0),
		PlayerModelParts.LEFT_LEG,   new Vec3f(1.9f, 12, 0),
		PlayerModelParts.RIGHT_LEG,  new Vec3f(-1.9f, 12, 0)
	);

	private YsmCoordUtil() {}

	public static Vec3f getVanillaPartPosition(PlayerModelParts part) {
		return VANILLA_PART_POS.getOrDefault(part, Vec3f.ZERO);
	}

	/** Find the first root bone's pivot as the coordinate reference. */
	public static Vec3f findYsmReferencePivot(List<BedrockBone> allBones) {
		for (BedrockBone b : allBones) {
			if (b.parent == null) return new Vec3f(b.pivot);
		}
		return Vec3f.ZERO;
	}

	/** Convert YSM absolute pivot to CPM position (subtract reference pivot). */
	public static Vec3f ysmToCpmPos(Vec3f bonePivot, Vec3f refPivot) {
		Vec3f d = bonePivot.sub(refPivot);
		return new Vec3f(d.x, -d.y, d.z);
	}

	/** Convert YSM parent-relative pivot to CPM parent-relative position. */
	public static Vec3f ysmToCpmRelPos(Vec3f childPivot, Vec3f parentPivot) {
		Vec3f d = childPivot.sub(parentPivot);
		return new Vec3f(d.x, -d.y, d.z);
	}

	/**
	 * Convert model/world-space delta to parent-local delta by removing parent rotation.
	 * This preserves world position when hierarchy is changed.
	 */
	public static Vec3f worldDeltaToParentLocal(Vec3f worldDelta, Vec3f parentRotationDeg) {
		if (worldDelta == null) return Vec3f.ZERO;
		if (!isNonZero(parentRotationDeg)) return new Vec3f(worldDelta);

		Mat3f parentRot = new Mat3f(new Rotation(parentRotationDeg, true).asQ());
		Mat3f invParentRot = parentRot.copy().invert();
		Vec3f local = new Vec3f(worldDelta);
		local.transform(invParentRot);
		return local;
	}

	/**
	 * Convert child and parent absolute pivots to parent-local CPM position,
	 * compensating for parent rest rotation.
	 */
	public static Vec3f ysmToCpmRelPos(Vec3f childPivot, Vec3f parentPivot, Vec3f parentRotationDeg) {
		Vec3f worldDelta = childPivot.sub(parentPivot);
		Vec3f local = worldDeltaToParentLocal(worldDelta, parentRotationDeg);
		return new Vec3f(local.x, -local.y, local.z);
	}

	/** Convert Bedrock cube origin to CPM offset relative to bone pivot. */
	public static Vec3f ysmToCpmOffset(Vec3f cubeOrigin, Vec3f bonePivot) {
		return cubeOrigin.sub(bonePivot);
	}

	/** Plugin-parity cube offset from Bedrock origin/size and bone pivot. */
	public static Vec3f ysmToCpmOffset(BedrockCube cube, Vec3f bonePivot) {
		float toX = cube.origin.x + cube.size.x;
		float toY = cube.origin.y + cube.size.y;
		return new Vec3f(
			bonePivot.x - toX,
			bonePivot.y - toY,
			cube.origin.z - bonePivot.z
		);
	}

	/** YSM rotation to CPM rotation — verified 1:1 identical (no sign change). */
	public static Vec3f ysmToCpmRotation(Vec3f ysmRot) {
		if (ysmRot == null || ysmRot.epsilon(0.001f)) return new Vec3f();
		return new Vec3f(ysmRot);
	}

	public static Vec3f computeYsmWorldPosition(String boneName, Map<String, BedrockBone> boneIndex) {
		BedrockBone bone = boneIndex.get(boneName);
		if (bone == null) return Vec3f.ZERO;
		// Bedrock bone pivots are already in model/world coordinates.
		return new Vec3f(bone.pivot);
	}

	public static float safeMeshScale(float size, float inflate) {
		if (Math.abs(size) < 0.01f) return 1.0f;
		float scale = 1.0f + 2.0f * inflate / size;
		return Math.max(0.1f, Math.min(10.0f, scale));
	}

	public static boolean isNonZero(Vec3f v) {
		return v != null && (Math.abs(v.x) > 0.001f || Math.abs(v.y) > 0.001f || Math.abs(v.z) > 0.001f);
	}

	/** Compute 3D bounding box enclosing a bone and all descendants. */
	public static BoundingBox computeSubtreeBoundingBox(BedrockBone rootBone,
			Map<String, BedrockBone> boneIndex, Map<String, List<BedrockBone>> childrenMap) {
		float[] mins = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] maxs = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		Vec3f worldPivot = computeYsmWorldPosition(rootBone.name, boneIndex);
		boolean has = collectCubesWorldBounds(rootBone, boneIndex, childrenMap, worldPivot, mins, maxs);
		return has ? new BoundingBox(mins[0], mins[1], mins[2], maxs[0], maxs[1], maxs[2]) : null;
	}

	private static boolean collectCubesWorldBounds(BedrockBone bone,
			Map<String, BedrockBone> boneIndex, Map<String, List<BedrockBone>> childrenMap,
			Vec3f boneWorldPos, float[] mins, float[] maxs) {
		boolean hasCubes = false;
		for (BedrockCube cube : bone.cubes) {
			float cx = boneWorldPos.x + cube.origin.x;
			float cy = boneWorldPos.y + cube.origin.y;
			float cz = boneWorldPos.z + cube.origin.z;
			if (cx < mins[0]) mins[0] = cx; if (cy < mins[1]) mins[1] = cy; if (cz < mins[2]) mins[2] = cz;
			if (cx + cube.size.x > maxs[0]) maxs[0] = cx + cube.size.x;
			if (cy + cube.size.y > maxs[1]) maxs[1] = cy + cube.size.y;
			if (cz + cube.size.z > maxs[2]) maxs[2] = cz + cube.size.z;
			hasCubes = true;
		}
		List<BedrockBone> children = childrenMap.get(bone.name);
		if (children != null) {
			for (BedrockBone child : children) {
				Vec3f childWorldPos = boneWorldPos.add(child.pivot.sub(bone.pivot));
				if (collectCubesWorldBounds(child, boneIndex, childrenMap, childWorldPos, mins, maxs)) {
					hasCubes = true;
				}
			}
		}
		return hasCubes;
	}
}
