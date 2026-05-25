package com.tom.cpm.shared.editor.ysm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.math.BoundingBox;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockBone;
import com.tom.cpm.shared.model.PlayerModelParts;

/**
 * Multi-strategy classifier that maps YSM bone trees to CPM root parts.
 *
 * <p>Uses a cascade of strategies in order of reliability:
 * <ol>
 *   <li>Exact name match against known YSM bone patterns</li>
 *   <li>Child name consensus (if >50% children share a part)</li>
 *   <li>Ancestor chain inheritance</li>
 *   <li>Spatial position analysis (world-space bounding box)</li>
 *   <li>Default fallback to BODY</li>
 * </ol>
 */
public class YsmBoneClassifier {

	/** Known YSM bone name → CPM part mappings (lowercase keys). */
	private static final Map<String, PlayerModelParts> NAME_MAP = new HashMap<>();
	static {
		// Head group
		put("head",       PlayerModelParts.HEAD);
		put("mhead",      PlayerModelParts.HEAD);
		put("allhead",    PlayerModelParts.HEAD);
		put("hat",        PlayerModelParts.HEAD);
		put("helmet",     PlayerModelParts.HEAD);

		// Body group (core)
		put("body",       PlayerModelParts.BODY);
		put("mallbody",   PlayerModelParts.BODY);
		put("allbody",    PlayerModelParts.BODY);
		put("upperbody",  PlayerModelParts.BODY);
		put("mupperbody", PlayerModelParts.BODY);
		put("upbody",     PlayerModelParts.BODY);
		put("chest",      PlayerModelParts.BODY);
		put("mupbody",    PlayerModelParts.BODY);
		put("waist",      PlayerModelParts.BODY);
		put("belly",      PlayerModelParts.BODY);

		// Left arm
		put("leftarm",           PlayerModelParts.LEFT_ARM);
		put("larm",              PlayerModelParts.LEFT_ARM);
		put("leftarmlocator",    PlayerModelParts.LEFT_ARM);
		put("lefthand",          PlayerModelParts.LEFT_ARM);
		put("leftglove",         PlayerModelParts.LEFT_ARM);
		put("leftsleeve",        PlayerModelParts.LEFT_ARM);

		// Right arm
		put("rightarm",          PlayerModelParts.RIGHT_ARM);
		put("rarm",              PlayerModelParts.RIGHT_ARM);
		put("rightarmlocator",   PlayerModelParts.RIGHT_ARM);
		put("righthand",         PlayerModelParts.RIGHT_ARM);
		put("rightglove",        PlayerModelParts.RIGHT_ARM);
		put("rightsleeve",       PlayerModelParts.RIGHT_ARM);

		// Left leg
		put("leftleg",           PlayerModelParts.LEFT_LEG);
		put("lleg",              PlayerModelParts.LEFT_LEG);
		put("leftleglocator",    PlayerModelParts.LEFT_LEG);
		put("leftfoot",          PlayerModelParts.LEFT_LEG);
		put("leftboot",          PlayerModelParts.LEFT_LEG);
		put("leftshoe",          PlayerModelParts.LEFT_LEG);

		// Right leg
		put("rightleg",          PlayerModelParts.RIGHT_LEG);
		put("rleg",              PlayerModelParts.RIGHT_LEG);
		put("rightleglocator",   PlayerModelParts.RIGHT_LEG);
		put("rightfoot",         PlayerModelParts.RIGHT_LEG);
		put("rightboot",         PlayerModelParts.RIGHT_LEG);
		put("rightshoe",         PlayerModelParts.RIGHT_LEG);
	}

	/** YSM utility bone names that should stay with their parent tree.
	 *  These bones exist only for animation control and have no geometry. */
	private static final Set<String> UTILITY_BONES = Set.of(
		"mroot", "root", "molang", "controller"
	);

	private static void put(String name, PlayerModelParts part) {
		NAME_MAP.put(normalizeName(name), part);
	}

	private YsmBoneClassifier() {}

	// ---- Public API ----

	/**
	 * Full multi-strategy classification of a subtree.
	 * Tries strategies 1 → 2 → 3 → 4 → 6 (default).
	 *
	 * @param rootBone  the root bone of the YSM subtree
	 * @param allBones  all bones in the YSM model (for context)
	 * @param boneIndex name → bone lookup
	 * @return the best CPM root part for this subtree, never null
	 */
	public static PlayerModelParts classifySubtree(BedrockBone rootBone,
	                                                List<BedrockBone> allBones,
	                                                Map<String, BedrockBone> boneIndex) {
		// Strategy 1: exact name match
		PlayerModelParts part = matchByName(rootBone.name);
		if (part != null) return part;

		// Strategy 2: child name consensus
		Map<String, java.util.List<BedrockBone>> childrenMap = buildChildrenMap(allBones);
		part = classifyByChildren(rootBone, childrenMap);
		if (part != null) return part;

		// Strategy 3: ancestor chain inheritance
		part = classifyByAncestors(rootBone, boneIndex);
		if (part != null) return part;

		// Strategy 4: spatial position analysis
		part = classifyBySpatialPosition(rootBone, boneIndex, childrenMap);
		if (part != null) return part;

		// Strategy 6: default fallback
		return PlayerModelParts.BODY;
	}

	/**
	 * Quick classification using strategy 1 + 3 only.
	 * Used for subtree boundary detection (where we only need
	 * to know if two bones map to different parts).
	 *
	 * @param bone      the bone to classify
	 * @param allBones  all YSM bones
	 * @param boneIndex name → bone lookup
	 * @return best CPM part, or BODY if indeterminate
	 */
	public static PlayerModelParts classifyBoneQuick(BedrockBone bone,
	                                                  List<BedrockBone> allBones,
	                                                  Map<String, BedrockBone> boneIndex) {
		PlayerModelParts part = matchByName(bone.name);
		if (part != null) return part;
		part = classifyByAncestors(bone, boneIndex);
		if (part != null) return part;
		return PlayerModelParts.BODY;
	}

	/**
	 * Check whether a bone is a YSM utility bone (no geometry,
	 * exists only for animation control). Utility bones stay
	 * with their parent tree.
	 */
	public static boolean isUtilityBone(String boneName) {
		return boneName != null && UTILITY_BONES.contains(boneName.toLowerCase());
	}

	// ---- Strategy implementations ----

	/** Strategy 1: exact name match (case-insensitive). */
	public static PlayerModelParts matchByName(String boneName) {
		if (boneName == null) return null;
		if (isUtilityBone(boneName)) return null; // utility bones don't map independently
		return NAME_MAP.get(normalizeName(boneName));
	}

	private static String normalizeName(String name) {
		StringBuilder sb = new StringBuilder();
		String lower = name.toLowerCase();
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if (Character.isLetterOrDigit(c)) sb.append(c);
		}
		return sb.toString();
	}

	/** Strategy 2: classify by child name consensus.
	 *  If >50% of immediate children map to the same part, return that part. */
	public static PlayerModelParts classifyByChildren(BedrockBone bone,
	                                                   Map<String, java.util.List<BedrockBone>> childrenMap) {
		java.util.List<BedrockBone> children = childrenMap.get(bone.name);
		if (children == null || children.isEmpty()) return null;

		Map<PlayerModelParts, Integer> counts = new HashMap<>();
		for (BedrockBone child : children) {
			PlayerModelParts p = matchByName(child.name);
			if (p != null) {
				counts.merge(p, 1, Integer::sum);
			}
		}
		if (counts.isEmpty()) return null;

		// Find the part with the most children
		PlayerModelParts best = null;
		int bestCount = 0;
		int total = children.size();
		for (Map.Entry<PlayerModelParts, Integer> e : counts.entrySet()) {
			if (e.getValue() > bestCount) {
				bestCount = e.getValue();
				best = e.getKey();
			}
		}
		// Require >50% consensus
		return (best != null && bestCount > total / 2) ? best : null;
	}

	/** Strategy 3: walk up the YSM parent chain; inherit the first mapped part. */
	public static PlayerModelParts classifyByAncestors(BedrockBone bone,
	                                                    Map<String, BedrockBone> boneIndex) {
		String anc = bone.parent;
		while (anc != null) {
			PlayerModelParts part = matchByName(anc);
			if (part != null) return part;
			BedrockBone p = boneIndex.get(anc);
			anc = p != null ? p.parent : null;
		}
		return null;
	}

	/** Strategy 4: spatial position analysis using world-space bounding box. */
	public static PlayerModelParts classifyBySpatialPosition(BedrockBone bone,
	                                                          Map<String, BedrockBone> boneIndex,
	                                                          Map<String, java.util.List<BedrockBone>> childrenMap) {
		BoundingBox bbox = YsmCoordUtil.computeSubtreeBoundingBox(bone, boneIndex, childrenMap);
		if (bbox == null) {
			// No cubes in subtree — fall back to pivot position alone
			Vec3f worldPos = YsmCoordUtil.computeYsmWorldPosition(bone.name, boneIndex);
			return classifyByPivot(worldPos);
		}

		float centerY = (bbox.minY + bbox.maxY) / 2f;
		float centerX = (bbox.minX + bbox.maxX) / 2f;
		float extentZ = bbox.maxZ - bbox.minZ;

		// Head: high Y position
		if (centerY > 22) return PlayerModelParts.HEAD;

		// Arms: moderate Y, significant X offset, or forward extent
		if (centerY >= 8 && centerY <= 22) {
			if (centerX < -4) return PlayerModelParts.RIGHT_ARM;
			if (centerX > 4)  return PlayerModelParts.LEFT_ARM;
			if (extentZ > 6)  return PlayerModelParts.RIGHT_ARM; // forward-reach → arms
		}

		// Body: central X, moderate Y
		if (centerY >= 10 && centerY <= 22 && Math.abs(centerX) < 4) {
			return PlayerModelParts.BODY;
		}

		// Legs: low Y
		if (centerY < 8) {
			return centerX < 0 ? PlayerModelParts.RIGHT_LEG : PlayerModelParts.LEFT_LEG;
		}

		return null; // indeterminate
	}

	private static PlayerModelParts classifyByPivot(Vec3f worldPos) {
		float y = worldPos.y;
		float x = Math.abs(worldPos.x);
		if (y > 22) return PlayerModelParts.HEAD;
		if (y >= 10 && x < 4) return PlayerModelParts.BODY;
		if (y >= 8 && x >= 4) {
			return worldPos.x < 0 ? PlayerModelParts.RIGHT_ARM : PlayerModelParts.LEFT_ARM;
		}
		if (y < 8) {
			return worldPos.x < 0 ? PlayerModelParts.RIGHT_LEG : PlayerModelParts.LEFT_LEG;
		}
		return null;
	}

	// ---- Utility ----

	static Map<String, java.util.List<BedrockBone>> buildChildrenMap(List<BedrockBone> allBones) {
		Map<String, java.util.List<BedrockBone>> map = new HashMap<>();
		for (BedrockBone b : allBones) {
			String parent = b.parent != null ? b.parent : "";
			map.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(b);
		}
		return map;
	}
}
