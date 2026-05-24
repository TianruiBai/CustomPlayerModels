package com.tom.cpm.shared.editor.ysm;

import java.util.Map;

import com.tom.cpl.math.BoundingBox;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockBone;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockCube;
import com.tom.cpm.shared.model.PlayerModelParts;

/**
 * Coordinate conversion utilities for YSM → CPM import.
 *
 * <p>Provides vanilla Minecraft part positions, YSM world-space position
 * computation, and mesh-scale safety clamping.
 */
public class YsmCoordUtil {

	/** Vanilla Minecraft player-model part positions (from PlayerPartValues).
	 *  These are the render-space positions of each CPM root part. */
	private static final Map<PlayerModelParts, Vec3f> VANILLA_PART_POS = Map.of(
		PlayerModelParts.HEAD,       new Vec3f(0, 0, 0),
		PlayerModelParts.BODY,       new Vec3f(0, 0, 0),
		PlayerModelParts.LEFT_ARM,   new Vec3f(5, 2, 0),
		PlayerModelParts.RIGHT_ARM,  new Vec3f(-5, 2, 0),
		PlayerModelParts.LEFT_LEG,   new Vec3f(1.9f, 12, 0),
		PlayerModelParts.RIGHT_LEG,  new Vec3f(-1.9f, 12, 0)
	);

	private YsmCoordUtil() {}

	/**
	 * Get the vanilla Minecraft render position for a CPM root part.
	 * @param part the CPM player-model part
	 * @return vanilla position (px, py, pz), never null
	 */
	public static Vec3f getVanillaPartPosition(PlayerModelParts part) {
		return VANILLA_PART_POS.getOrDefault(part, Vec3f.ZERO);
	}

	/**
	 * Compute the world-space position of a YSM bone by summing all ancestor pivots.
	 * @param boneName  the bone whose world position to compute
	 * @param boneIndex name → bone lookup (all bones in the YSM model)
	 * @return world-space pivot position
	 */
	public static Vec3f computeYsmWorldPosition(String boneName,
	                                             Map<String, BedrockBone> boneIndex) {
		BedrockBone bone = boneIndex.get(boneName);
		if (bone == null) return Vec3f.ZERO;

		Vec3f worldPos = new Vec3f(bone.pivot);
		String parent = bone.parent;
		while (parent != null) {
			BedrockBone p = boneIndex.get(parent);
			if (p == null) break;
			worldPos = worldPos.add(p.pivot);
			parent = p.parent;
		}
		return worldPos;
	}

	/**
	 * Compute a 3D bounding box enclosing a bone and all its descendants.
	 * Only bones that contain cubes contribute to the box.
	 *
	 * @param rootBone    the root of the subtree
	 * @param boneIndex   name → bone lookup
	 * @param childrenMap parent name → child bones lookup
	 * @return bounding box, or null if subtree has no cubes
	 */
	public static BoundingBox computeSubtreeBoundingBox(BedrockBone rootBone,
	                                                     Map<String, BedrockBone> boneIndex,
	                                                     Map<String, java.util.List<BedrockBone>> childrenMap) {
		Vec3f worldPivot = computeYsmWorldPosition(rootBone.name, boneIndex);
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		boolean hasCubes = collectCubesWorldBounds(rootBone, boneIndex, childrenMap,
			worldPivot, new float[]{minX, minY, minZ}, new float[]{maxX, maxY, maxZ});

		return hasCubes ? new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ) : null;
	}

	private static boolean collectCubesWorldBounds(BedrockBone bone,
	                                                Map<String, BedrockBone> boneIndex,
	                                                Map<String, java.util.List<BedrockBone>> childrenMap,
	                                                Vec3f boneWorldPos,
	                                                float[] mins, float[] maxs) {
		boolean hasCubes = false;
		for (BedrockCube cube : bone.cubes) {
			float cx = boneWorldPos.x + cube.origin.x;
			float cy = boneWorldPos.y + cube.origin.y;
			float cz = boneWorldPos.z + cube.origin.z;
			if (cx < mins[0]) mins[0] = cx;
			if (cy < mins[1]) mins[1] = cy;
			if (cz < mins[2]) mins[2] = cz;
			if (cx + cube.size.x > maxs[0]) maxs[0] = cx + cube.size.x;
			if (cy + cube.size.y > maxs[1]) maxs[1] = cy + cube.size.y;
			if (cz + cube.size.z > maxs[2]) maxs[2] = cz + cube.size.z;
			hasCubes = true;
		}
		java.util.List<BedrockBone> children = childrenMap.get(bone.name);
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

	/**
	 * Safe mesh-scale computation from Bedrock inflate.
	 * Clamped to [{@code 0.1}, {@code 10.0}] to prevent extreme geometry.
	 *
	 * @param size    the cube dimension along one axis
	 * @param inflate the Bedrock inflate value
	 * @return safe mesh scale factor (1.0 = no scaling)
	 */
	public static float safeMeshScale(float size, float inflate) {
		if (Math.abs(size) < 0.01f) return 1.0f;
		float scale = 1.0f + 2.0f * inflate / size;
		return Math.max(0.1f, Math.min(10.0f, scale));
	}

	/**
	 * Check whether a Vec3f has any non-zero component (beyond epsilon).
	 */
	public static boolean isNonZero(Vec3f v) {
		return v != null && (Math.abs(v.x) > 0.001f || Math.abs(v.y) > 0.001f || Math.abs(v.z) > 0.001f);
	}
}
