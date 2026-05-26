package com.tom.cpm.shared.editor.ysm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.ItemSlot;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.TextureSlot;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.editor.anim.AnimationEncodingData;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.AnimFrame.FrameData;
import com.tom.cpm.shared.editor.anim.IElem;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockBone;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockCube;
import com.tom.cpm.shared.editor.ysm.BedrockAnimationParser.AnimationTarget;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.ItemRenderer;
import com.tom.cpm.shared.model.render.PerFaceUV;
import com.tom.cpm.shared.util.Log;

/**
 * Converts parsed YSM (Bedrock-format) model data into CPM editor state.
 *
 * <p><b>Conversion pipeline (matching cpm_plugin.js parity):</b>
 * <ol>
 *   <li>Parse Bedrock JSON using {@link BedrockModelParser}</li>
 *   <li>Build bone lookup and parent→children maps</li>
 *   <li>Classify YSM bones into CPM head/body anchors</li>
 *   <li>Cut head subtrees away from body control bones and attach them to
 *       the CPM head root; body, arm, and leg subtrees stay under body by default</li>
 *   <li>Recursively build each flattened subtree:
 *       <ul>
 *         <li>Bone pos = {@code [dx, -dy, dz]} where d = childPivot - parentPivot</li>
 *         <li>Cut subtree root pos = YSM pivot converted relative to the CPM part pivot</li>
 *         <li>Bone rotation = 1:1 copy (local rotation, coord transform via position)</li>
 *         <li>Cube offset = {@code [origin.x-pivot.x, pivot.y-(origin.y+size.y), origin.z-pivot.z]}</li>
 *         <li>Cube with own pivot: offset uses cube pivot; pos = rel to bone</li>
 *         <li>UV direction mapping = 1:1 (Bedrock "up" → CPM Direction.UP)</li>
 *       </ul>
 *   </li>
 * </ol>
 */
public class YsmToCpmConverter {
	private static final int SMALL_GRID_UV_SCALE = 16;

	public static void convert(YsmModelData ysmData, Editor editor) {
		Log.info("[YSM Import] Starting conversion of: " + ysmData.modelName);
		ysmData.uvScale = computeUvScale(ysmData);

		List<BedrockBone> mainBones = BedrockModelParser.parse(ysmData.mainModelJson, ysmData.uvScale);
		Log.info("[YSM Import] Parsed " + mainBones.size() + " main bones");

		// Build bone lookup (needed by both model and animation conversion)
		Map<String, BedrockBone> boneIndex = new LinkedHashMap<>();
		for (BedrockBone b : mainBones) { boneIndex.put(b.name, b); }

		ModelConversionResult model = convertModel(mainBones, boneIndex, editor,
			ysmData.flattenToAllPlayerParts);
		convertAnimations(ysmData, editor, model.elements, model.animationParentRotations, boneIndex, mainBones);
		setupGestures(ysmData, editor);
		loadTextures(ysmData, editor);
		stabilizeSkinType(editor);
		applyModelScale(ysmData, editor);
		setMetadata(ysmData, editor);

		Log.info("[YSM Import] Done — " + model.elements.size() + " elements, " +
			editor.animations.size() + " animations");
	}

	// ========================================================================
	// Model Conversion
	// ========================================================================

	private static ModelConversionResult convertModel(List<BedrockBone> allBones,
			Map<String, BedrockBone> boneIndex, Editor editor, boolean flattenToAllPlayerParts) {
		// Hide all vanilla CPM root parts
		for (ModelElement rootElem : editor.elements) {
			rootElem.hidden = true;
		}

		// Parent → children map
		Map<String, List<BedrockBone>> childrenMap = new LinkedHashMap<>();
		for (BedrockBone b : allBones) {
			String parentKey = b.parent != null && boneIndex.containsKey(b.parent) ? b.parent : "";
			childrenMap.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(b);
		}

		Map<PlayerModelParts, Vec3f> rootOffsets = buildRootOffsets(boneIndex, childrenMap, flattenToAllPlayerParts);
		Map<PlayerModelParts, ModelElement> rootParts = new EnumMap<>(PlayerModelParts.class);
		for (PlayerModelParts part : PlayerModelParts.VALUES) {
			if (part == PlayerModelParts.CUSTOM_PART) continue;
			ModelElement root = findRootElement(editor, part);
			if (root != null) {
				root.pos = new Vec3f(rootOffsets.getOrDefault(part, Vec3f.ZERO));
				root.rotation = new Vec3f();
				root.disableVanillaAnim = shouldDisableVanillaRootAnimation(part);
				rootParts.put(part, root);
			}
		}
		if (rootParts.isEmpty()) {
			Log.error("[YSM Import] No player root parts found, aborting");
			return new ModelConversionResult(new HashMap<>(), new HashMap<>());
		}
		// Root parts stay hidden so vanilla geometry does not render. Custom
		// children still render and inherit the native part transforms.

		// Find YSM root bones (no parent, or parent not in bone list)
		List<BedrockBone> ysmRoots = new ArrayList<>();
		for (BedrockBone bone : allBones) {
			if (bone.parent == null || !boneIndex.containsKey(bone.parent)) {
				ysmRoots.add(bone);
			}
		}

		// Reference pivot is logged for diagnostics; flattened roots use native
		// CPM part pivots as anchors.
		Vec3f refPivot = ysmRoots.isEmpty() ? Vec3f.ZERO : new Vec3f(ysmRoots.get(0).pivot);
		Log.info("[YSM Import] Ref pivot: " + refPivot + ", YSM roots: " + ysmRoots.size() +
			", flatten mode: " + (flattenToAllPlayerParts ? "all player parts" : "hybrid player roots"));

		Map<String, ModelElement> allElements = new HashMap<>();
		Map<String, Vec3f> animationParentRotations = new HashMap<>();
		Map<PlayerModelParts, Integer> partCounts = new EnumMap<>(PlayerModelParts.class);
		for (BedrockBone ysmRoot : ysmRoots) {
			buildBoneTree(ysmRoot, null, null, null, rootParts, rootOffsets, boneIndex, childrenMap,
				allBones, allElements, animationParentRotations, partCounts, editor,
				flattenToAllPlayerParts);
		}

		attachItemRendererAnchors(allElements, boneIndex, editor);

		Log.info("[YSM Import] Built " + allElements.size() + " flattened elements: " + partCounts);
		return new ModelConversionResult(allElements, animationParentRotations);
	}

	private static void attachItemRendererAnchors(Map<String, ModelElement> allElements,
			Map<String, BedrockBone> boneIndex, Editor editor) {
		attachItemRendererAnchor(allElements, boneIndex, editor, ItemSlot.LEFT_HAND, PlayerModelParts.LEFT_ARM, true);
		attachItemRendererAnchor(allElements, boneIndex, editor, ItemSlot.RIGHT_HAND, PlayerModelParts.RIGHT_ARM, false);
	}

	private static void attachItemRendererAnchor(Map<String, ModelElement> allElements,
			Map<String, BedrockBone> boneIndex, Editor editor, ItemSlot slot,
			PlayerModelParts fallbackPart, boolean left) {
		if (hasItemRenderer(editor, slot)) return;
		ModelElement parent = findBestItemAnchorParent(allElements, boneIndex, left);
		if (parent == null) parent = findRootElement(editor, fallbackPart);
		if (parent == null) return;

		ModelElement anchor = new ModelElement(editor);
		anchor.name = left ? "YSM Left Hand Item" : "YSM Right Hand Item";
		anchor.parent = parent;
		anchor.size = new Vec3f(0, 0, 0);
		anchor.texture = false;
		anchor.itemRenderer = new ItemRenderer(slot, 0);
		parent.children.add(anchor);
		Log.info("[YSM Import] Bound " + slot.name().toLowerCase() + " item transform to '" + parent.name + "'");
	}

	private static boolean hasItemRenderer(Editor editor, ItemSlot slot) {
		final boolean[] found = new boolean[1];
		Editor.walkElements(editor.elements, e -> {
			if (e.itemRenderer != null && e.itemRenderer.slot == slot) found[0] = true;
		});
		return found[0];
	}

	private static ModelElement findBestItemAnchorParent(Map<String, ModelElement> allElements,
			Map<String, BedrockBone> boneIndex, boolean left) {
		ModelElement best = null;
		int bestScore = Integer.MIN_VALUE;
		for (Map.Entry<String, ModelElement> entry : allElements.entrySet()) {
			String boneName = entry.getKey();
			int score = handAnchorScore(boneName, boneIndex.get(boneName), boneIndex, left, entry.getValue());
			if (score > bestScore) {
				bestScore = score;
				best = entry.getValue();
			}
		}
		return bestScore > 0 ? best : null;
	}

	private static int handAnchorScore(String boneName, BedrockBone bone,
			Map<String, BedrockBone> boneIndex, boolean left, ModelElement element) {
		String normalized = normalizeBoneName(boneName);
		if (!matchesHandSide(normalized, left)) return Integer.MIN_VALUE;
		int score = 0;
		PlayerModelParts namedPart = YsmBoneClassifier.matchByName(boneName);
		score += explicitHandLocatorScore(normalized, bone, boneIndex);
		if (namedPart == (left ? PlayerModelParts.LEFT_ARM : PlayerModelParts.RIGHT_ARM)) score += 40;
		if (normalized.equals(left ? "lefthand" : "righthand") || normalized.equals(left ? "lhand" : "rhand")) score += 160;
		if (normalized.contains("hand")) score += 120;
		if (normalized.contains("item") || normalized.contains("weapon")) score += 110;
		if (normalized.contains("wrist") || normalized.contains("palm")) score += 100;
		if (normalized.contains("glove")) score += 80;
		if (normalized.contains("forearm") || normalized.contains("lowerarm")) score += 40;
		if (normalized.contains("arm")) score += 20;
		if (normalized.contains("shoulder") || normalized.contains("waist") || normalized.contains("sheath") ||
				normalized.contains("viewlocator") || normalized.contains("backpack") ||
				normalized.contains("rifle") || normalized.contains("pistol") || normalized.contains("blade")) score -= 200;
		if (bone != null) score += Math.min(30, boneDepth(bone, boneIndex) * 3);
		if (element.hidden) score -= 80;
		return score;
	}

	private static int explicitHandLocatorScore(String normalized, BedrockBone bone,
			Map<String, BedrockBone> boneIndex) {
		if (normalized.contains("handlocator")) return 360;
		if (normalized.contains("itemlocator")) return 340;
		if (normalized.contains("locator") && (normalized.contains("hand") || hasHandAncestor(bone, boneIndex))) return 260;
		return 0;
	}

	private static boolean hasHandAncestor(BedrockBone bone, Map<String, BedrockBone> boneIndex) {
		BedrockBone current = bone;
		while (current != null && current.parent != null) {
			current = boneIndex.get(current.parent);
			if (current == null) break;
			String normalized = normalizeBoneName(current.name);
			if (normalized.contains("hand")) return true;
		}
		return false;
	}

	private static boolean matchesHandSide(String normalized, boolean left) {
		if (left) {
			return normalized.contains("left") || normalized.contains("lefthand") ||
				normalized.contains("handleft") || normalized.contains("lhand") ||
				((normalized.startsWith("l") || normalized.endsWith("l")) && containsHandAnchorToken(normalized));
		}
		return normalized.contains("right") || normalized.contains("righthand") ||
			normalized.contains("handright") || normalized.contains("rhand") ||
			((normalized.startsWith("r") || normalized.endsWith("r")) && containsHandAnchorToken(normalized));
	}

	private static boolean containsHandAnchorToken(String normalized) {
		return normalized.contains("hand") || normalized.contains("arm") || normalized.contains("wrist") ||
			normalized.contains("palm") || normalized.contains("glove") || normalized.contains("item") ||
			normalized.contains("weapon");
	}

	private static int boneDepth(BedrockBone bone, Map<String, BedrockBone> boneIndex) {
		int depth = 0;
		BedrockBone current = bone;
		while (current != null && current.parent != null && boneIndex.containsKey(current.parent)) {
			depth++;
			current = boneIndex.get(current.parent);
		}
		return depth;
	}

	private static class ModelConversionResult {
		final Map<String, ModelElement> elements;
		final Map<String, Vec3f> animationParentRotations;

		ModelConversionResult(Map<String, ModelElement> elements,
				Map<String, Vec3f> animationParentRotations) {
			this.elements = elements;
			this.animationParentRotations = animationParentRotations;
		}
	}

	/**
	 * Recursively create CPM elements for a bone and all its descendants.
	 * Bones with cubes get cube child elements. Pure container bones are kept
	 * but hidden if they have no geometry.
	 */
	private static void buildBoneTree(BedrockBone bone, ModelElement ysmParentElem,
			BedrockBone ysmParentBone, PlayerModelParts parentPart,
			Map<PlayerModelParts, ModelElement> rootParts,
			Map<PlayerModelParts, Vec3f> rootOffsets,
			Map<String, BedrockBone> boneIndex,
			Map<String, List<BedrockBone>> childrenMap,
			List<BedrockBone> allBones,
			Map<String, ModelElement> allElements,
			Map<String, Vec3f> animationParentRotations,
			Map<PlayerModelParts, Integer> partCounts,
			Editor editor,
			boolean flattenToAllPlayerParts) {

		PlayerModelParts part = classifyBoneForFlattening(bone, parentPart, allBones, boneIndex,
			flattenToAllPlayerParts);
		ModelElement cpmParent;
		boolean cutToRoot = ysmParentElem == null || part != parentPart;
		if (cutToRoot) {
			cpmParent = rootParts.get(part);
			if (cpmParent == null) cpmParent = rootParts.get(PlayerModelParts.BODY);
			if (cpmParent == null) cpmParent = rootParts.values().iterator().next();
		} else {
			cpmParent = ysmParentElem;
		}

		ModelElement elem = new ModelElement(editor);
		elem.name = bone.name;
		elem.parent = cpmParent;
		cpmParent.children.add(elem);
		allElements.put(bone.name, elem);
		animationParentRotations.put(bone.name,
			ysmParentBone == null ? new Vec3f() : new Vec3f(ysmParentBone.rotation));

		// Bone elements are containers, not renderable cubes.
		// ElementType.NORMAL defaults size to [1,1,1] — override to [0,0,0].
		elem.size = new Vec3f(0, 0, 0);
		elem.texture = true;
		elem.textureSize = 1;

		// --- Position ---
		if (cutToRoot) {
			elem.pos = positionRelativeToRootPart(bone.pivot, part, rootOffsets);
		} else {
			Vec3f d = bone.pivot.sub(ysmParentBone.pivot);
			elem.pos = new Vec3f(d.x, -d.y, d.z);
		}

		// --- Rotation: 1:1 (local bone rotation, coordinate transform handled by position) ---
		if (isNonZero(bone.rotation)) {
			elem.rotation = new Vec3f(bone.rotation);
		}

		// --- Visibility ---
		if (bone.neverRender) {
			elem.hidden = true;
		}
		if (bone.mirror) {
			elem.mirror = true;
		}

		List<BedrockBone> children = childrenMap.get(bone.name);
		boolean hasChildren = children != null && !children.isEmpty();

		// --- Create cube elements ---
		// If a bone has exactly 1 cube: inline it on the bone only when this won't
		// alter child hierarchy transforms. If the cube has its own pivot/rotation and
		// this bone has children, keep bone transform pure and emit a cube child instead.
		if (bone.cubes.size() == 1) {
			BedrockCube only = bone.cubes.get(0);
			boolean cubeHasOwnTransform = only.pivot != null || isNonZero(only.rotation);
			if (!cubeHasOwnTransform) {
				applyCubeToElement(only, bone, elem);
			} else {
				ModelElement cubeElem = createCubeElement(only, bone, elem, editor, 0);
				allElements.put(bone.name + "_cube_0", cubeElem);
			}
		} else {
			int cubeIdx = 0;
			for (BedrockCube cube : bone.cubes) {
				ModelElement cubeElem = createCubeElement(cube, bone, elem, editor, cubeIdx++);
				allElements.put(bone.name + "_cube_" + cubeIdx, cubeElem);
			}
		}

		// --- Recurse into children ---
		if (children != null) {
			for (BedrockBone child : children) {
				buildBoneTree(child, elem, bone, part, rootParts, rootOffsets, boneIndex, childrenMap,
					allBones, allElements, animationParentRotations, partCounts, editor,
					flattenToAllPlayerParts);
			}
		}
		partCounts.merge(part, 1, Integer::sum);
	}

	private static PlayerModelParts classifyBoneForFlattening(BedrockBone bone,
			PlayerModelParts parentPart, List<BedrockBone> allBones,
			Map<String, BedrockBone> boneIndex, boolean flattenToAllPlayerParts) {
		PlayerModelParts part = classifyBoneToPlayerPart(bone, parentPart, allBones, boneIndex);
		if (flattenToAllPlayerParts) return part;
		if (hasExplicitHeadBone(boneIndex)) {
			if (isInExplicitHeadSubtree(bone, boneIndex)) return PlayerModelParts.HEAD;
			if (isLimbPart(part)) return part;
			return PlayerModelParts.BODY;
		}
		return part == PlayerModelParts.HEAD || isLimbPart(part) ? part : PlayerModelParts.BODY;
	}

	private static boolean isLimbPart(PlayerModelParts part) {
		return part == PlayerModelParts.LEFT_ARM || part == PlayerModelParts.RIGHT_ARM ||
			part == PlayerModelParts.LEFT_LEG || part == PlayerModelParts.RIGHT_LEG;
	}

	private static boolean shouldDisableVanillaRootAnimation(PlayerModelParts part) {
		return isLimbPart(part);
	}

	private static boolean hasExplicitHeadBone(Map<String, BedrockBone> boneIndex) {
		for (String boneName : boneIndex.keySet()) {
			if (isExplicitHeadBone(boneName)) return true;
		}
		return false;
	}

	private static boolean isInExplicitHeadSubtree(BedrockBone bone,
			Map<String, BedrockBone> boneIndex) {
		BedrockBone cutRoot = findHeadCutRoot(boneIndex);
		if (cutRoot == null) return false;
		BedrockBone current = bone;
		while (current != null) {
			if (current == cutRoot || current.name.equals(cutRoot.name)) return true;
			current = current.parent != null ? boneIndex.get(current.parent) : null;
		}
		return false;
	}

	private static BedrockBone findHeadCutRoot(Map<String, BedrockBone> boneIndex) {
		BedrockBone head = findExplicitHeadBone(boneIndex);
		if (head == null || head.parent == null) return head;
		BedrockBone cutRoot = head;
		BedrockBone parent = boneIndex.get(head.parent);
		if (parent != null && isCuttableHeadControlBone(parent)) {
			cutRoot = parent;
		}
		return cutRoot;
	}

	private static boolean isCuttableHeadControlBone(BedrockBone bone) {
		if (bone == null || hasRenderableGeometry(bone)) return false;
		String normalized = normalizeBoneName(bone.name);
		return normalized.contains("headroot") ||
			normalized.contains("headbase") || normalized.contains("mhead") ||
			(normalized.contains("head") && !isExplicitHeadBone(bone.name));
	}

	private static boolean isHeadCarrierBone(BedrockBone bone) {
		if (bone == null) return false;
		String normalized = normalizeBoneName(bone.name);
		if (normalized.isEmpty() || isNeckBone(bone.name)) return false;
		if (normalized.contains("upperbody") || normalized.contains("upbody") ||
				normalized.contains("lowerbody") || normalized.contains("allbody") ||
				normalized.contains("body") || normalized.contains("torso") ||
				normalized.contains("chest") || normalized.contains("pelvis") ||
				normalized.contains("waist")) {
			return false;
		}
		return normalized.contains("allhead") || normalized.contains("headroot") ||
			normalized.contains("headbase") || normalized.contains("head") ||
			normalized.contains("brow") || normalized.contains("face");
	}

	private static boolean isExplicitHeadBone(String boneName) {
		return "head".equals(normalizeBoneName(boneName));
	}

	private static PlayerModelParts classifyBoneToPlayerPart(BedrockBone bone,
			PlayerModelParts parentPart, List<BedrockBone> allBones,
			Map<String, BedrockBone> boneIndex) {
		if (isNeckBone(bone.name)) return PlayerModelParts.BODY;
		if (parentPart != null && YsmBoneClassifier.isUtilityBone(bone.name)) return parentPart;
		if (parentPart == null && YsmBoneClassifier.isUtilityBone(bone.name)) return PlayerModelParts.BODY;
		PlayerModelParts named = YsmBoneClassifier.matchByName(bone.name);
		if (named != null) return named;
		if (parentPart != null) return parentPart;
		return YsmBoneClassifier.classifySubtree(bone, allBones, boneIndex);
	}

	private static boolean isNeckBone(String boneName) {
		return normalizeBoneName(boneName).contains("neck");
	}

	private static Map<PlayerModelParts, Vec3f> buildRootOffsets(
			Map<String, BedrockBone> boneIndex,
			Map<String, List<BedrockBone>> childrenMap,
			boolean flattenToAllPlayerParts) {
		Map<PlayerModelParts, Vec3f> rootOffsets = new EnumMap<>(PlayerModelParts.class);
		if (!flattenToAllPlayerParts) {
			HeadPivotSelection headPivot = findHeadPivotSelection(boneIndex, childrenMap);
			if (headPivot != null) {
				Vec3f offset = positionRelativeToVanillaPart(headPivot.pivot, PlayerModelParts.HEAD);
				if (isNonZero(offset)) {
					rootOffsets.put(PlayerModelParts.HEAD, offset);
					Log.info("[YSM Import] Head look pivot moved to '" + headPivot.boneName +
						"' (" + headPivot.reason + "): " + offset);
				}
			}
		}
		return rootOffsets;
	}

	private static HeadPivotSelection findHeadPivotSelection(Map<String, BedrockBone> boneIndex,
			Map<String, List<BedrockBone>> childrenMap) {
		BedrockBone head = findExplicitHeadBone(boneIndex);
		if (head == null || head.parent == null) return null;
		BedrockBone headParent = boneIndex.get(head.parent);
		BedrockBone bodyAnchor = findHeadBodyAnchorBone(headParent, boneIndex);

		List<BedrockBone> ancestors = new ArrayList<>();
		String parentName = head.parent;
		while (parentName != null) {
			BedrockBone parent = boneIndex.get(parentName);
			if (parent == null) break;
			ancestors.add(parent);
			parentName = parent.parent;
		}

		if (headParent != null && isSamePivot(head.pivot, headParent.pivot) && bodyAnchor != null) {
			return new HeadPivotSelection(bodyAnchor.name, new Vec3f(bodyAnchor.pivot),
				"head body seam pivot");
		}

		HeadPivotSelection directPivot = findDirectHeadControlPivot(headParent, ancestors);
		if (directPivot != null) return directPivot;

		for (BedrockBone ancestor : ancestors) {
			if (!isNeckBone(ancestor.name)) continue;
			Vec3f pivot = computeNonHeadSubtreeTopCenter(ancestor, childrenMap, true);
			if (pivot != null) return alignGeometryHeadAnchorToParentBand(
				new HeadPivotSelection(ancestor.name, pivot, "neck top center"), head, headParent);
		}

		for (BedrockBone ancestor : ancestors) {
			Vec3f pivot = computeNonHeadSubtreeTopCenter(ancestor, childrenMap, false);
			if (pivot != null) return alignGeometryHeadAnchorToParentBand(
				new HeadPivotSelection(ancestor.name, pivot, "nearest neck geometry top center"), head, headParent);
		}

		return headParent != null ? new HeadPivotSelection(headParent.name,
			new Vec3f(headParent.pivot), "direct head parent pivot") :
			(!ancestors.isEmpty() ? new HeadPivotSelection(ancestors.get(0).name,
				new Vec3f(ancestors.get(0).pivot), "nearest ancestor pivot") : null);
	}

	private static BedrockBone findHeadBodyAnchorBone(BedrockBone headParent,
			Map<String, BedrockBone> boneIndex) {
		String ancestorName = headParent != null ? headParent.parent : null;
		while (ancestorName != null) {
			BedrockBone ancestor = boneIndex.get(ancestorName);
			if (ancestor == null) break;
			if (isHeadBodyAnchorCandidate(ancestor)) return ancestor;
			ancestorName = ancestor.parent;
		}
		return null;
	}

	private static boolean isHeadBodyAnchorCandidate(BedrockBone bone) {
		return bone != null && (isNeckBone(bone.name) ||
				(hasRenderableGeometry(bone) && isHeadCarrierBone(bone)));
	}

	private static HeadPivotSelection findDirectHeadControlPivot(BedrockBone headParent,
			List<BedrockBone> ancestors) {
		if (headParent != null && isLikelyHeadPivotBone(headParent.name, true, headParent.cubes.isEmpty())) {
			return new HeadPivotSelection(headParent.name, new Vec3f(headParent.pivot), "direct head control pivot");
		}
		for (BedrockBone ancestor : ancestors) {
			if (headParent != null && ancestor == headParent) continue;
			if (isLikelyHeadPivotBone(ancestor.name, false, ancestor.cubes.isEmpty())) {
				return new HeadPivotSelection(ancestor.name, new Vec3f(ancestor.pivot), "head control ancestor pivot");
			}
		}
		if (headParent != null && headParent.cubes.isEmpty()) {
			return new HeadPivotSelection(headParent.name, new Vec3f(headParent.pivot), "direct head parent pivot");
		}
		return null;
	}

	private static boolean isLikelyHeadPivotBone(String boneName, boolean directParent, boolean noGeometry) {
		String normalized = normalizeBoneName(boneName);
		if (isNeckBone(boneName)) return true;
		if (normalized.contains("headroot") || normalized.contains("headbase") ||
				normalized.contains("allhead") || normalized.contains("mhead")) {
			return true;
		}
		if (directParent && normalized.contains("head") && !isExplicitHeadBone(boneName)) return true;
		return noGeometry && directParent;
	}

	private static boolean hasRenderableGeometry(BedrockBone bone) {
		for (BedrockCube cube : bone.cubes) {
			if (Math.abs(cube.size.x) > 0.001f || Math.abs(cube.size.y) > 0.001f ||
					Math.abs(cube.size.z) > 0.001f) return true;
		}
		return false;
	}

	private static HeadPivotSelection alignGeometryHeadAnchorToParentBand(HeadPivotSelection selection,
			BedrockBone head, BedrockBone headParent) {
		if (selection == null || headParent == null) return selection;
		float minY = Math.min(head.pivot.y, headParent.pivot.y);
		float maxY = Math.max(head.pivot.y, headParent.pivot.y);
		float y = Math.max(minY, Math.min(maxY, selection.pivot.y));
		String reason = selection.reason;
		if (Math.abs(y - selection.pivot.y) > 0.01f) reason += ", seam clamped";
		return new HeadPivotSelection(selection.boneName,
			new Vec3f(headParent.pivot.x, y, headParent.pivot.z),
			reason + ", aligned to head parent");
	}

	private static Vec3f computeNonHeadSubtreeTopCenter(BedrockBone root,
			Map<String, List<BedrockBone>> childrenMap, boolean neckOnly) {
		BoundsAccumulator bounds = new BoundsAccumulator();
		collectNonHeadBounds(root, childrenMap, bounds, neckOnly, true);
		return bounds.hasBounds() ? bounds.topCenter() : null;
	}

	private static void collectNonHeadBounds(BedrockBone bone,
			Map<String, List<BedrockBone>> childrenMap,
			BoundsAccumulator bounds, boolean neckOnly, boolean rootBone) {
		if (!rootBone && isExplicitHeadBone(bone.name)) return;
		if (!neckOnly || rootBone || isNeckBone(bone.name)) {
			for (BedrockCube cube : bone.cubes) {
				bounds.include(cube);
			}
		}
		List<BedrockBone> children = childrenMap.get(bone.name);
		if (children != null) {
			for (BedrockBone child : children) {
				collectNonHeadBounds(child, childrenMap, bounds, neckOnly, false);
			}
		}
	}

	private static class BoundsAccumulator {
		private float minX = Float.POSITIVE_INFINITY;
		private float minY = Float.POSITIVE_INFINITY;
		private float minZ = Float.POSITIVE_INFINITY;
		private float maxX = Float.NEGATIVE_INFINITY;
		private float maxY = Float.NEGATIVE_INFINITY;
		private float maxZ = Float.NEGATIVE_INFINITY;

		void include(BedrockCube cube) {
			if (Math.abs(cube.size.x) < 0.001f && Math.abs(cube.size.y) < 0.001f &&
					Math.abs(cube.size.z) < 0.001f) return;
			Vec3f origin = cube.origin;
			Vec3f size = cube.size;
			float inflate = cube.inflate;
			minX = Math.min(minX, origin.x - inflate);
			minY = Math.min(minY, origin.y - inflate);
			minZ = Math.min(minZ, origin.z - inflate);
			maxX = Math.max(maxX, origin.x + size.x + inflate);
			maxY = Math.max(maxY, origin.y + size.y + inflate);
			maxZ = Math.max(maxZ, origin.z + size.z + inflate);
		}

		boolean hasBounds() {
			return minX != Float.POSITIVE_INFINITY;
		}

		Vec3f topCenter() {
			return new Vec3f((minX + maxX) * 0.5f, maxY, (minZ + maxZ) * 0.5f);
		}
	}

	private static BedrockBone findExplicitHeadBone(Map<String, BedrockBone> boneIndex) {
		for (BedrockBone bone : boneIndex.values()) {
			if (isExplicitHeadBone(bone.name)) return bone;
		}
		return null;
	}

	private static class HeadPivotSelection {
		final String boneName;
		final Vec3f pivot;
		final String reason;

		HeadPivotSelection(String boneName, Vec3f pivot, String reason) {
			this.boneName = boneName;
			this.pivot = pivot;
			this.reason = reason;
		}
	}

	private static Vec3f positionRelativeToRootPart(Vec3f ysmPivot, PlayerModelParts part,
			Map<PlayerModelParts, Vec3f> rootOffsets) {
		Vec3f pos = positionRelativeToVanillaPart(ysmPivot, part);
		Vec3f rootOffset = rootOffsets.get(part);
		return rootOffset != null ? pos.sub(rootOffset) : pos;
	}

	private static Vec3f positionRelativeToVanillaPart(Vec3f ysmPivot, PlayerModelParts part) {
		Vec3f cpmPivot = new Vec3f(ysmPivot.x, 24f - ysmPivot.y, ysmPivot.z);
		Vec3f partPivot = YsmCoordUtil.getVanillaPartPosition(part);
		return cpmPivot.sub(partPivot);
	}

	/**
	 * Apply a single cube's data directly onto a bone element (no separate child).
	 * Used when a bone has exactly 1 cube — matches CPM-reference behavior.
	 */
	private static void applyCubeToElement(BedrockCube cube, BedrockBone bone, ModelElement elem) {
		elem.size = new Vec3f(cube.size);

		// Position & offset (same formulas as createCubeElement)
		if (cube.pivot != null) {
			Vec3f rel = cube.pivot.sub(bone.pivot);
			elem.pos = new Vec3f(rel.x, -rel.y, rel.z);
			elem.offset = cubeOffset(cube.origin, cube.size, cube.pivot);
			if (isNonZero(cube.rotation)) {
				elem.rotation = new Vec3f(cube.rotation);
			}
		} else if (isNonZero(cube.rotation)) {
			Vec3f rawOff = cubeOffset(cube.origin, cube.size, bone.pivot);
			Vec3f center = new Vec3f(cube.size).mul(0.5f);
			elem.pos = new Vec3f(center);
			elem.offset = rawOff.sub(center);
			elem.rotation = new Vec3f(cube.rotation);
		} else {
			elem.offset = cubeOffset(cube.origin, cube.size, bone.pivot);
		}

		// UV
		PerFaceUV pfUV = BedrockModelParser.convertPerFaceUV(cube);
		if (pfUV != null) {
			elem.faceUV = pfUV;
		} else {
			Vec2i primaryUV = BedrockModelParser.getPrimaryUV(cube);
			if (primaryUV != null) {
				elem.u = primaryUV.x;
				elem.v = primaryUV.y;
			}
		}

		// Inflate → meshScale
		if (cube.inflate != 0) {
			elem.meshScale = new Vec3f(
				safeMeshScale(cube.size.x, cube.inflate),
				safeMeshScale(cube.size.y, cube.inflate),
				safeMeshScale(cube.size.z, cube.inflate));
		}

		// Mirror (XOR bone and cube)
		elem.mirror = cube.mirror ^ bone.mirror;
	}

	/**
	 * Create a separate CPM cube child element (for bones with 2+ cubes).
	 *
	 * <p><b>Cube offset formula</b> (verified against CPM-reference):
	 * <pre>
	 *   offset.x = origin.x - pivot.x
	 *   offset.y = pivot.y - (origin.y + size.y)
	 *   offset.z = origin.z - pivot.z
	 * </pre>
	 */
	private static ModelElement createCubeElement(BedrockCube cube, BedrockBone bone,
			ModelElement parentElem, Editor editor, int index) {

		ModelElement ce = new ModelElement(editor);
		ce.name = bone.name + "_cube" + (index > 0 ? "_" + index : "");
		ce.parent = parentElem;
		parentElem.children.add(ce);
		ce.size = new Vec3f(cube.size);
		ce.texture = true;
		ce.textureSize = 1;

		// --- Position & Offset ---
		if (cube.pivot != null) {
			// Cube has its own pivot — position at cube pivot relative to bone,
			// offset geometry relative to cube pivot.
			Vec3f rel = cube.pivot.sub(bone.pivot);
			ce.pos = new Vec3f(rel.x, -rel.y, rel.z);
			ce.offset = cubeOffset(cube.origin, cube.size, cube.pivot);

			if (isNonZero(cube.rotation)) {
				ce.rotation = new Vec3f(cube.rotation);
			}
		} else if (isNonZero(cube.rotation)) {
			// Rotation without explicit pivot — rotate around geometric center.
			Vec3f rawOff = cubeOffset(cube.origin, cube.size, bone.pivot);
			Vec3f center = new Vec3f(cube.size).mul(0.5f);
			ce.pos = new Vec3f(center);
			ce.offset = rawOff.sub(center);
			ce.rotation = new Vec3f(cube.rotation);
		} else {
			// Normal case — offset from bone pivot.
			ce.offset = cubeOffset(cube.origin, cube.size, bone.pivot);
		}

		// --- UV Mapping ---
		PerFaceUV pfUV = BedrockModelParser.convertPerFaceUV(cube);
		if (pfUV != null) {
			ce.faceUV = pfUV;
		} else {
			Vec2i primaryUV = BedrockModelParser.getPrimaryUV(cube);
			if (primaryUV != null) {
				ce.u = primaryUV.x;
				ce.v = primaryUV.y;
			}
		}

		// --- Inflate → meshScale ---
		if (cube.inflate != 0) {
			ce.meshScale = new Vec3f(
				safeMeshScale(cube.size.x, cube.inflate),
				safeMeshScale(cube.size.y, cube.inflate),
				safeMeshScale(cube.size.z, cube.inflate));
		}

		// --- Mirror (XOR bone and cube) ---
		ce.mirror = cube.mirror ^ bone.mirror;

		return ce;
	}

	/** Plugin-parity cube offset.
	 * <pre>
	 *   offset.x = origin.x - pivot.x
	 *   offset.y = pivot.y - (origin.y + size.y)
	 *   offset.z = origin.z - pivot.z
	 * </pre>
	 * Verified against CPM-reference config: DownBody cube with non-zero pivot
	 * gives offset.x = -1.1 using origin.x - pivot.x, NOT pivot.x - (origin.x + size.x). */
	private static Vec3f cubeOffset(Vec3f origin, Vec3f size, Vec3f pivot) {
		return new Vec3f(
			origin.x - pivot.x,
			pivot.y - (origin.y + size.y),
			origin.z - pivot.z
		);
	}

	private static float safeMeshScale(float size, float inflate) {
		if (Math.abs(size) < 0.01f) return 1.0f;
		float s = 1.0f + 2.0f * inflate / size;
		return Math.max(0.1f, Math.min(10.0f, s));
	}

	private static boolean isNonZero(Vec3f v) {
		return v != null && (Math.abs(v.x) > 0.001f ||
			Math.abs(v.y) > 0.001f || Math.abs(v.z) > 0.001f);
	}

	private static boolean isSamePivot(Vec3f a, Vec3f b) {
		return a != null && b != null && Math.abs(a.x - b.x) < 0.01f &&
			Math.abs(a.y - b.y) < 0.01f && Math.abs(a.z - b.z) < 0.01f;
	}

	// ========================================================================
	// Helpers
	// ========================================================================

	private static ModelElement findRootElement(Editor editor, PlayerModelParts part) {
		for (ModelElement elem : editor.elements) {
			if (elem.type == ElementType.ROOT_PART && elem.typeData == part) {
				return elem;
			}
		}
		return null;
	}

	// ========================================================================
	// Animation Conversion
	// ========================================================================

	private static void convertAnimations(YsmModelData ysmData, Editor editor,
			Map<String, ModelElement> builtElements,
			Map<String, Vec3f> animationParentRotations,
			Map<String, BedrockBone> boneIndex,
			List<BedrockBone> allBones) {
		Map<String, List<AnimationTarget>> animationTargets = buildAnimationTargetMap(builtElements, boneIndex);

		Map<String, Vec3f> worldPositions = new LinkedHashMap<>();
		for (BedrockBone b : allBones) {
			worldPositions.put(b.name, YsmCoordUtil.computeYsmWorldPosition(b.name, boneIndex));
		}

		int total = 0;
		total += parseAnim(ysmData.mainAnimJson, editor, animationTargets,
			AnimationType.POSE, worldPositions, animationParentRotations, boneIndex, "main");
		total += parseAnim(ysmData.armAnimJson, editor, animationTargets,
			AnimationType.POSE, worldPositions, animationParentRotations, boneIndex, "arm");
		total += parseAnim(ysmData.extraAnimJson, editor, animationTargets,
			AnimationType.GESTURE, worldPositions, animationParentRotations, boneIndex, "extra");

		for (Map.Entry<String, String> e : ysmData.extraAnimFiles.entrySet()) {
			try {
				com.google.gson.JsonObject json = com.google.gson.JsonParser
					.parseString(e.getValue()).getAsJsonObject();
				// Extract source name from path like "animations/tac.animation.json" → "tac"
				String path = e.getKey();
				String fileName = path.substring(path.lastIndexOf('/') + 1);
				String srcName = fileName.replace(".animation.json", "").replace(".json", "");
				total += parseAnim(json, editor, animationTargets,
					AnimationType.GESTURE, worldPositions, animationParentRotations, boneIndex, srcName);
			} catch (Exception ex) {
				Log.warn("[YSM Import] Failed extra anim: " + e.getKey(), ex);
			}
		}
		Log.info("[YSM Import] Total animations: " + total);
	}

	private static Map<String, List<AnimationTarget>> buildAnimationTargetMap(
			Map<String, ModelElement> builtElements, Map<String, BedrockBone> boneIndex) {
		Map<String, List<AnimationTarget>> targets = new LinkedHashMap<>();
		for (String boneName : boneIndex.keySet()) {
			ModelElement elem = builtElements.get(boneName);
			registerTarget(targets, boneName, elem);
		}
		builtElements.forEach((name, elem) -> registerTarget(targets, name, elem));
		int before = targets.size();

		String rootName = findRootBoneName(boneIndex, builtElements);
		ModelElement root = elementForBone(builtElements, rootName);
		String bodyName = firstBoneName(boneIndex, builtElements,
			"AllBody", "Allbody", "UpBody", "Body", "body", "MAllBody");
		ModelElement body = elementForBone(builtElements, bodyName);
		if (body == null) body = root;
		String upperBodyName = firstBoneName(boneIndex, builtElements,
			"MUpperBody", "UpperBody", "Chest", "Torso", "UpBody");
		ModelElement upperBody = elementForBone(builtElements, upperBodyName);
		if (upperBody == null) upperBody = body;
		String lowerBodyName = firstBoneName(boneIndex, builtElements,
			"DownBody", "LowerBody", "Hips", "Pelvis", "Waist");
		ModelElement lowerBody = elementForBone(builtElements, lowerBodyName);
		if (lowerBody == null) lowerBody = body;

		String leftLegName = firstBoneName(boneIndex, builtElements,
			"LeftLeg", "left_leg", "LLeg", "LeftThigh", "thigh");
		String rightLegName = firstBoneName(boneIndex, builtElements,
			"RightLeg", "right_leg", "RLeg", "RightThigh", "thigh2");
		String leftLowerLegName = firstBoneName(boneIndex, builtElements,
			"LeftLowerLeg", "LeftCalf", "LeftShin");
		if (leftLowerLegName == null) {
			leftLowerLegName = descendantBoneName(boneIndex, leftLegName, "lowerleg", "calf", "shin");
		}
		String rightLowerLegName = firstBoneName(boneIndex, builtElements,
			"RightLowerLeg", "RightCalf", "RightShin");
		if (rightLowerLegName == null) {
			rightLowerLegName = descendantBoneName(boneIndex, rightLegName, "lowerleg", "calf", "shin");
		}
		String leftFootName = firstBoneName(boneIndex, builtElements, "LeftFoot");
		if (leftFootName == null) leftFootName = descendantBoneName(boneIndex, leftLegName, "foot");
		String rightFootName = firstBoneName(boneIndex, builtElements, "RightFoot");
		if (rightFootName == null) rightFootName = descendantBoneName(boneIndex, rightLegName, "foot");

		registerAlias(targets, "Root", root);
		registerAlias(targets, "root", root);
		registerAlias(targets, "MAllBody", root != null ? root : body);
		registerAlias(targets, "AllBody", body);
		registerAlias(targets, "Allbody", body);
		registerAlias(targets, "Body", body);
		registerAlias(targets, "body", body);
		registerAlias(targets, "MUpperBody", upperBody);
		registerAlias(targets, "UpperBody", upperBody);
		registerAlias(targets, "Arm", upperBody);
		registerAlias(targets, "DownBody", lowerBody);
		registerAlias(targets, "LeftLeg", elementForBone(builtElements, leftLegName));
		registerAlias(targets, "RightLeg", elementForBone(builtElements, rightLegName));
		registerAlias(targets, "LeftLowerLeg", elementForBone(builtElements, leftLowerLegName));
		registerAlias(targets, "RightLowerLeg", elementForBone(builtElements, rightLowerLegName));
		registerAlias(targets, "LeftFoot", elementForBone(builtElements, leftFootName));
		registerAlias(targets, "RightFoot", elementForBone(builtElements, rightFootName));
		registerAlias(targets, "LongHair", elementForBone(builtElements,
			firstBoneName(boneIndex, builtElements, "LongHair", "Hair", "hair")));
		registerAlias(targets, "LongHead", elementForBone(builtElements,
			firstBoneName(boneIndex, builtElements, "LongHead", "Head", "head")));
		registerInheritedCutTargets(targets, builtElements, boneIndex);
		registerInheritedBodyAliasesForCutTargets(targets, builtElements, boneIndex,
			"Root", "root", "MAllBody", "AllBody", "Allbody", "Body", "body",
			"MUpperBody", "UpperBody", "UpBody", "Arm", "DownBody", "LowerBody",
			"Hips", "Pelvis", "Waist");

		int aliases = targets.size() - before;
		if (aliases > 0) {
			Log.info("[YSM Import] Added " + aliases + " animation target aliases");
		}
		return targets;
	}

	private static void registerInheritedCutTargets(Map<String, List<AnimationTarget>> targets,
			Map<String, ModelElement> builtElements, Map<String, BedrockBone> boneIndex) {
		for (BedrockBone bone : boneIndex.values()) {
			ModelElement elem = builtElements.get(bone.name);
			if (elem == null || elem.parent == null || elem.parent.type != ElementType.ROOT_PART) continue;
			String targetBoneName = inheritedTargetBoneName(elem, boneIndex);
			String ancestor = bone.parent;
			while (ancestor != null && boneIndex.containsKey(ancestor)) {
				registerTarget(targets, ancestor, elem, targetBoneName, true);
				BedrockBone parent = boneIndex.get(ancestor);
				ancestor = parent != null ? parent.parent : null;
			}
		}
	}

	private static void registerInheritedBodyAliasesForCutTargets(
			Map<String, List<AnimationTarget>> targets,
			Map<String, ModelElement> builtElements,
			Map<String, BedrockBone> boneIndex,
			String... aliases) {
		for (ModelElement elem : builtElements.values()) {
			if (elem.parent == null || elem.parent.type != ElementType.ROOT_PART ||
				elem.parent.typeData == PlayerModelParts.BODY) continue;
			String targetBoneName = inheritedTargetBoneName(elem, boneIndex);
			for (String alias : aliases) {
				registerTarget(targets, alias, elem, targetBoneName, true);
			}
		}
	}

	private static void registerAlias(Map<String, List<AnimationTarget>> targets, String alias, ModelElement elem) {
		registerTarget(targets, alias, elem);
	}

	private static void registerTarget(Map<String, List<AnimationTarget>> targets, String name, ModelElement elem) {
		registerTarget(targets, name, elem, false);
	}

	private static void registerTarget(Map<String, List<AnimationTarget>> targets, String name,
			ModelElement elem, boolean inherited) {
		registerTarget(targets, name, elem, elem != null ? elem.name : null, inherited);
	}

	private static void registerTarget(Map<String, List<AnimationTarget>> targets, String name,
			ModelElement elem, String targetBoneName, boolean inherited) {
		if (name == null || elem == null) return;
		List<AnimationTarget> list = targets.computeIfAbsent(name, k -> new ArrayList<>());
		for (AnimationTarget target : list) {
			if (target.element == elem && target.inherited == inherited &&
					matchesBoneName(target.boneName, targetBoneName)) return;
		}
		list.add(new AnimationTarget(elem, targetBoneName != null ? targetBoneName : elem.name, inherited));
	}

	private static String inheritedTargetBoneName(ModelElement elem, Map<String, BedrockBone> boneIndex) {
		if (elem == null || elem.parent == null || elem.parent.type != ElementType.ROOT_PART) {
			return elem != null ? elem.name : null;
		}
		if (elem.parent.typeData == PlayerModelParts.HEAD) {
			BedrockBone bone = boneIndex.get(elem.name);
			String headAnchor = inheritedHeadAnchorBoneName(bone, boneIndex);
			if (headAnchor != null) return headAnchor;
		}
		return elem.name;
	}

	private static String inheritedHeadAnchorBoneName(BedrockBone bone,
			Map<String, BedrockBone> boneIndex) {
		if (bone == null) return null;
		BedrockBone head = findExplicitHeadBone(boneIndex);
		if (head == null || head.parent == null) return bone.name;
		BedrockBone headParent = boneIndex.get(head.parent);
		BedrockBone bodyAnchor = findHeadBodyAnchorBone(headParent, boneIndex);
		if (headParent != null && isSamePivot(head.pivot, headParent.pivot) && bodyAnchor != null) {
			return bodyAnchor.name;
		}
		if (isExplicitHeadBone(bone.name) && bone.parent != null && boneIndex.containsKey(bone.parent)) {
			return bone.parent;
		}
		return bone.name;
	}

	private static ModelElement elementForBone(Map<String, ModelElement> builtElements, String boneName) {
		return boneName != null ? builtElements.get(boneName) : null;
	}

	private static String findRootBoneName(Map<String, BedrockBone> boneIndex,
			Map<String, ModelElement> builtElements) {
		for (BedrockBone bone : boneIndex.values()) {
			if ((bone.parent == null || !boneIndex.containsKey(bone.parent)) &&
				builtElements.containsKey(bone.name)) {
				return bone.name;
			}
		}
		return null;
	}

	private static String firstBoneName(Map<String, BedrockBone> boneIndex,
			Map<String, ModelElement> builtElements, String... candidates) {
		for (String candidate : candidates) {
			for (String boneName : boneIndex.keySet()) {
				if (matchesBoneName(boneName, candidate) && builtElements.containsKey(boneName)) {
					return boneName;
				}
			}
		}
		return null;
	}

	private static String descendantBoneName(Map<String, BedrockBone> boneIndex,
			String ancestorName, String... normalizedTokens) {
		if (ancestorName == null) return null;
		for (String boneName : boneIndex.keySet()) {
			if (!isDescendantOf(boneIndex, boneName, ancestorName)) continue;
			String normalized = normalizeBoneName(boneName);
			for (String token : normalizedTokens) {
				if (normalized.contains(token)) return boneName;
			}
		}
		return null;
	}

	private static boolean isDescendantOf(Map<String, BedrockBone> boneIndex,
			String boneName, String ancestorName) {
		BedrockBone bone = boneIndex.get(boneName);
		while (bone != null && bone.parent != null) {
			if (bone.parent.equals(ancestorName)) return true;
			bone = boneIndex.get(bone.parent);
		}
		return false;
	}

	private static boolean matchesBoneName(String a, String b) {
		return a.equalsIgnoreCase(b) || normalizeBoneName(a).equals(normalizeBoneName(b));
	}

	private static String normalizeBoneName(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
		}
		return sb.toString();
	}

	private static int parseAnim(com.google.gson.JsonObject json, Editor editor,
			Map<String, List<AnimationTarget>> builtElements, AnimationType type,
			Map<String, Vec3f> worldPositions, Map<String, Vec3f> animationParentRotations,
			Map<String, BedrockBone> boneIndex,
			String source) {
		if (json == null) return 0;
		try {
			List<EditorAnim> anims = BedrockAnimationParser.parse(json, editor,
				builtElements, type, source, worldPositions, animationParentRotations, boneIndex);
			editor.animations.addAll(anims);
			if (!anims.isEmpty()) {
				Log.info("[YSM Import] " + source + ": " + anims.size() + " animations");
			}
			return anims.size();
		} catch (Exception e) {
			Log.error("[YSM Import] Animation parse failed (" + source + ")", e);
			return 0;
		}
	}

	private static void setupGestures(YsmModelData ysmData, Editor editor) {
		if (editor.animEnc == null) {
			editor.animEnc = new AnimationEncodingData();
		}
		if (ysmData.controllerJson != null) {
			Map<String, String> ctrlGestures = BedrockControllerParser
				.extractGestureMappings(ysmData.controllerJson);
			ctrlGestures.forEach((k, v) -> ysmData.extraAnimations.putIfAbsent(k, v));
		}
		for (Map.Entry<String, String> entry : ysmData.extraAnimations.entrySet()) {
			EditorAnim target = findYsmAnimation(editor, entry.getKey());
			if (target == null && entry.getValue() != null && !entry.getValue().startsWith("#")) {
				target = findYsmAnimation(editor, entry.getValue());
			}
			if (target == null) continue;
			String mapping = entry.getValue();
			if (mapping != null && mapping.startsWith("#")) {
				String controlId = mapping.substring(1);
				List<YsmModelData.ExtraAnimationControl> forms = ysmData.extraAnimationControlForms.get(controlId);
				if (forms != null && forms.size() > 1) {
					applyMultiFormControls(editor, target, forms, controlId, ysmData.controllerJson);
				} else {
					YsmModelData.ExtraAnimationControl control = ysmData.extraAnimationControls.get(controlId);
					applyExtraAnimationControl(target, control, controlId);
				}
			} else if (target.type != AnimationType.CUSTOM_POSE) {
				target.type = AnimationType.GESTURE;
				target.pose = null;
				target.layerControlled = false;
				if (mapping != null && !mapping.isEmpty()) target.displayName = mapping;
			}
		}

		// CPM encodes custom animation IDs into skin layers using bit encoding.
		// Slot 0 (blank) and slot all-bits-set (reset) are reserved.
		// 6 layers = 62 valid slots, enough for any YSM model's gestures.
		// Always initialize — some models ship gesture-only animations without explicit
		// extra_animation mappings, and the parser may assign GESTURE type at runtime.
		if (!editor.animations.isEmpty()) {
			for (com.tom.cpm.shared.editor.util.PlayerSkinLayer layer :
					com.tom.cpm.shared.editor.util.PlayerSkinLayer.VALUES) {
				editor.animEnc.freeLayers.add(layer);
			}
			Log.info("[YSM Import] Initialized " + editor.animEnc.freeLayers.size() +
				" encoding layers for " + editor.animations.size() + " animations");
		}
	}

	private static void applyMultiFormControls(Editor editor, EditorAnim backingAnimation,
			List<YsmModelData.ExtraAnimationControl> forms, String controlId, JsonObject controllerJson) {
		retireBackingControlAnimation(backingAnimation, controlId);
		Set<String> handledValueControls = new LinkedHashSet<>();
		for (YsmModelData.ExtraAnimationControl form : forms) {
			String type = form.type != null ? form.type : "checkbox";
			if (("range".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) &&
					form.value != null && hasRadioForm(forms, form.value) &&
					!"radio".equalsIgnoreCase(type)) {
				continue;
			}
			if (("range".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) &&
					form.value != null && !handledValueControls.add(form.value)) {
				continue;
			}
			if ("radio".equalsIgnoreCase(type) && !form.labels.isEmpty()) {
				createRadioControl(editor, form, controllerJson);
			} else {
				EditorAnim synthetic = createSyntheticControlAnimation(editor, form.name, type);
				if ("range".equalsIgnoreCase(type) && synthesizeValueControl(editor, synthetic, form, controllerJson)) {
					continue;
				}
				applyExtraAnimationControl(synthetic, form, form.name != null ? form.name : controlId);
			}
		}
	}

	private static void retireBackingControlAnimation(EditorAnim target, String controlId) {
		target.type = AnimationType.GESTURE;
		target.pose = null;
		target.layerControlled = false;
		target.loop = false;
		target.hidden = true;
		target.displayName = controlId;
		resetFrames(target, 1);
	}

	private static boolean hasRadioForm(List<YsmModelData.ExtraAnimationControl> forms, String value) {
		for (YsmModelData.ExtraAnimationControl form : forms) {
			if (value.equals(form.value) && "radio".equalsIgnoreCase(form.type) && !form.labels.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static void createRadioControl(Editor editor, YsmModelData.ExtraAnimationControl control,
			JsonObject controllerJson) {
		Map<Integer, String> valueAnimations = extractVariableAnimationMappings(controllerJson, control.value);
		String group = control.name != null && !control.name.isEmpty() ? control.name : control.id;
		int created = 0;
		for (Map.Entry<String, String> label : control.labels.entrySet()) {
			Integer value = parseAssignmentValue(label.getValue(), control.value);
			if (value == null) continue;
			EditorAnim layer = createSyntheticControlAnimation(editor, label.getKey(), "radio");
			layer.type = AnimationType.LAYER;
			layer.group = group;
			layer.layerDefault = value == 0 ? 1 : 0;
			String sourceName = valueAnimations.get(value);
			EditorAnim source = sourceName != null ? findYsmAnimation(editor, sourceName) : null;
			if (source != null) {
				copyFirstFrame(source, layer.getFrames().get(0));
				source.hidden = true;
			}
			created++;
		}
		if (created > 0) {
			Log.info("[YSM Import] Synthesized radio control '" + group + "' with " + created + " option(s)");
		}
	}

	private static boolean synthesizeValueControl(Editor editor, EditorAnim target,
			YsmModelData.ExtraAnimationControl control, JsonObject controllerJson) {
		Map<Integer, String> valueAnimations = extractVariableAnimationMappings(controllerJson, control.value);
		if (valueAnimations.isEmpty()) return false;
		int min = control.min;
		int max = Math.max(control.max, min + 1);
		resetFrames(target, Math.max(2, max - min + 1));
		applyExtraAnimationControl(target, control, control.name);
		target.interpolateValue = false;
		for (int value = min; value <= max; value++) {
			String sourceName = valueAnimations.get(value);
			if (sourceName == null) continue;
			EditorAnim source = findYsmAnimation(editor, sourceName);
			if (source == null) continue;
			copyFirstFrame(source, target.getFrames().get(value - min));
			source.hidden = true;
		}
		Log.info("[YSM Import] Synthesized value control '" + target.displayName + "' from controller variable " + control.value);
		return true;
	}

	private static Map<Integer, String> extractVariableAnimationMappings(JsonObject controllerJson, String variable) {
		Map<Integer, String> mappings = new LinkedHashMap<>();
		if (controllerJson == null || variable == null || variable.isEmpty()) return mappings;
		collectVariableAnimationMappings(controllerJson, variable, mappings);
		return mappings;
	}

	private static void collectVariableAnimationMappings(JsonElement element, String variable,
			Map<Integer, String> mappings) {
		if (element == null || element.isJsonNull()) return;
		if (element.isJsonObject()) {
			JsonObject obj = element.getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
				if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
					Integer value = parseEqualityValue(entry.getValue().getAsString(), variable);
					if (value != null) mappings.putIfAbsent(value, entry.getKey());
				}
				collectVariableAnimationMappings(entry.getValue(), variable, mappings);
			}
		} else if (element.isJsonArray()) {
			element.getAsJsonArray().forEach(child -> collectVariableAnimationMappings(child, variable, mappings));
		}
	}

	private static Integer parseEqualityValue(String expression, String variable) {
		String compact = expression.replace(" ", "");
		String marker = variable + "==";
		int index = compact.indexOf(marker);
		if (index < 0) return null;
		return parseLeadingInt(compact.substring(index + marker.length()));
	}

	private static Integer parseAssignmentValue(String expression, String variable) {
		String compact = expression.replace(" ", "");
		String marker = variable + "=";
		int index = compact.indexOf(marker);
		if (index < 0 || compact.startsWith(marker + "=")) return null;
		return parseLeadingInt(compact.substring(index + marker.length()));
	}

	private static Integer parseLeadingInt(String text) {
		int end = 0;
		while (end < text.length() && (Character.isDigit(text.charAt(end)) ||
				(end == 0 && text.charAt(end) == '-'))) {
			end++;
		}
		if (end == 0) return null;
		try {
			return Integer.parseInt(text.substring(0, end));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static EditorAnim createSyntheticControlAnimation(Editor editor, String name, String type) {
		EditorAnim anim = new EditorAnim(editor,
			"ysm_control_" + sanitizeControlFileName(name != null ? name : type) + ".json",
			AnimationType.LAYER, false);
		anim.displayName = name;
		anim.pose = null;
		anim.loop = true;
		anim.add = true;
		anim.layerControlled = true;
		anim.duration = 1000;
		resetFrames(anim, 1);
		editor.animations.add(anim);
		return anim;
	}

	private static String sanitizeControlFileName(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') sb.append(c);
			else sb.append('_');
		}
		return sb.length() > 0 ? sb.toString() : "control";
	}

	private static void resetFrames(EditorAnim anim, int count) {
		anim.getFrames().clear();
		for (int i = 0; i < count; i++) {
			anim.getFrames().add(new AnimFrame(anim));
		}
		if (!anim.getFrames().isEmpty()) anim.setSelectedFrame(anim.getFrames().get(0));
	}

	private static void copyFirstFrame(EditorAnim source, AnimFrame targetFrame) {
		if (source.getFrames().isEmpty()) return;
		AnimFrame sourceFrame = source.getFrames().get(0);
		for (ModelElement element : source.getComponentsFiltered()) {
			IElem src = sourceFrame.getData(element);
			if (src == null) continue;
			FrameData dst = targetFrame.makeData(element);
			dst.setPos(new Vec3f(src.getPosition()));
			dst.setRot(new Vec3f(src.getRotation()));
			dst.setScale(new Vec3f(src.getScale()));
			dst.setColor(new Vec3f(src.getColor()));
			dst.setShow(src.isVisible());
			dst.setTextureId(src.getTextureId());
		}
	}

	private static EditorAnim findYsmAnimation(Editor editor, String ysmName) {
		if (ysmName == null || ysmName.isEmpty()) return null;
		return editor.animations.stream()
			.filter(a -> a.displayName != null && matchesYsmAnimationName(a.displayName, ysmName))
			.findFirst().orElse(null);
	}

	private static boolean matchesYsmAnimationName(String displayName, String ysmName) {
		if (displayName.equals(ysmName)) return true;
		int sourceEnd = displayName.indexOf("] ");
		return sourceEnd >= 0 && sourceEnd + 2 < displayName.length() &&
			displayName.substring(sourceEnd + 2).equals(ysmName);
	}

	private static void applyExtraAnimationControl(EditorAnim target, YsmModelData.ExtraAnimationControl control, String fallbackName) {
		target.pose = null;
		target.loop = true;
		target.layerControlled = true;
		String displayName = control != null && control.name != null && !control.name.isEmpty() ? control.name : fallbackName;
		if (displayName != null && !displayName.isEmpty()) target.displayName = displayName;
		String type = control != null && control.type != null ? control.type : "checkbox";
		if ("range".equalsIgnoreCase(type)) {
			target.type = AnimationType.VALUE_LAYER;
			int min = control != null ? control.min : 0;
			int max = control != null ? control.max : 1;
			target.maxValue = Math.max(1, max - min);
			target.interpolateValue = true;
			target.layerDefault = defaultRangeLayerValue(control, displayName);
		} else if ("radio".equalsIgnoreCase(type)) {
			target.type = AnimationType.LAYER;
			target.group = displayName;
			target.layerDefault = 0;
		} else {
			target.type = AnimationType.LAYER;
			target.layerDefault = 0;
		}
		synthesizeEmptyControlVisibility(target, displayName, fallbackName, type, control);
	}

	private static float defaultRangeLayerValue(YsmModelData.ExtraAnimationControl control, String displayName) {
		if (control == null) return 0;
		int min = control.min;
		int max = Math.max(control.max, min + 1);
		String normalized = normalizeBoneName(displayName != null ? displayName : "");
		if ((normalized.contains("size") || normalized.contains("scale") ||
				normalized.contains("propeller") || normalized.contains("rotor") ||
				displayName != null && displayName.contains("大小")) && min <= 1 && max >= 1) {
			return (1f - min) / Math.max(1, max - min);
		}
		return 0;
	}

	private static void synthesizeEmptyControlVisibility(EditorAnim target,
			String displayName, String fallbackName, String type,
			YsmModelData.ExtraAnimationControl control) {
		if (target.getComponentsFiltered().size() > 0) return;
		List<ModelElement> elements = findVisibilityControlTargets(target.editor, displayName, fallbackName);
		if (elements.isEmpty()) return;
		if ("range".equalsIgnoreCase(type)) {
			synthesizeEmptyRangeControl(target, elements, control);
			return;
		}
		if (target.getFrames().isEmpty()) target.addFrame(false);
		boolean hideWhenActive = isHideWhenActiveControl(displayName, fallbackName);
		boolean showWhenActive = !hideWhenActive && !"range".equalsIgnoreCase(type);
		for (ModelElement element : elements) {
			if (showWhenActive) element.hidden = true;
			FrameData data = target.getFrames().get(0).makeData(element);
			data.setShow(showWhenActive);
		}
		Log.info("[YSM Import] Synthesized visibility " + target.type.name().toLowerCase() +
			" for empty YSM control '" + target.displayName + "' on " + elements.size() + " element(s)");
	}

	private static void synthesizeEmptyRangeControl(EditorAnim target, List<ModelElement> elements,
			YsmModelData.ExtraAnimationControl control) {
		int min = control != null ? control.min : 0;
		int max = Math.max(control != null ? control.max : 1, min + 1);
		resetFrames(target, Math.max(2, max - min + 1));
		for (int i = 0; i < target.getFrames().size(); i++) {
			int value = min + i;
			float scale = value <= 0 ? 0.01f : value;
			boolean visible = value > 0;
			for (ModelElement element : elements) {
				if (!visible && target.layerDefault <= 0.001f) element.hidden = true;
				FrameData data = target.getFrames().get(i).makeData(element);
				data.setScale(new Vec3f(scale, scale, scale));
				data.setShow(visible);
			}
		}
		Log.info("[YSM Import] Synthesized value-layer scale control '" + target.displayName +
			"' on " + elements.size() + " element(s)");
	}

	private static List<ModelElement> findVisibilityControlTargets(Editor editor,
			String displayName, String fallbackName) {
		Set<String> tokens = visibilityControlTokens(displayName, fallbackName);
		if (tokens.isEmpty()) return List.of();
		Set<ModelElement> candidates = new LinkedHashSet<>();
		Editor.walkElements(editor.elements, element -> {
			if (element.type == ElementType.ROOT_PART) return;
			String normalized = normalizeBoneName(element.name);
			for (String token : tokens) {
				if (normalized.contains(token)) {
					candidates.add(element);
					break;
				}
			}
		});
		List<ModelElement> roots = new ArrayList<>();
		for (ModelElement candidate : candidates) {
			if (!hasAncestorIn(candidate, candidates)) roots.add(candidate);
		}
		return roots;
	}

	private static boolean hasAncestorIn(ModelElement element, Set<ModelElement> candidates) {
		ModelElement parent = element.parent;
		while (parent != null) {
			if (candidates.contains(parent)) return true;
			parent = parent.parent;
		}
		return false;
	}

	private static Set<String> visibilityControlTokens(String displayName, String fallbackName) {
		Set<String> tokens = new LinkedHashSet<>();
		String combined = ((displayName != null ? displayName : "") + " " +
			(fallbackName != null ? fallbackName : "")).toLowerCase();
		String normalized = normalizeBoneName(combined);
		if (combined.contains("表情") || normalized.contains("expression") ||
				normalized.contains("meme") || normalized.contains("emoji") ||
				normalized.contains("biaoqing")) {
			tokens.add("expression");
			tokens.add("biaoqing");
			tokens.add("meme");
			tokens.add("emoji");
		}
		if (combined.contains("面具") || normalized.contains("mask") || normalized.contains("mianju")) {
			tokens.add("mask");
			tokens.add("mianju");
		}
		if (combined.contains("斗篷") || normalized.contains("cape") || normalized.contains("cloak") ||
				normalized.contains("doupeng")) {
			tokens.add("doupeng");
			tokens.add("cape");
			tokens.add("cloak");
		}
		if (combined.contains("盔甲") || normalized.contains("armor") || normalized.contains("armour")) {
			tokens.add("armor");
			tokens.add("armour");
		}
		if (combined.contains("螺旋桨") || normalized.contains("propeller") ||
				normalized.contains("rotor") || normalized.contains("luoxuanjiang")) {
			tokens.add("fengshan");
			tokens.add("feixingzujian");
			tokens.add("propeller");
			tokens.add("rotor");
		}
		if (combined.contains("背包") || normalized.contains("backpack") || normalized.contains("bag")) {
			tokens.add("backpack");
			tokens.add("bag");
		}
		if (combined.contains("翅") || normalized.contains("wing") || normalized.contains("elytra") ||
				normalized.contains("chibang")) {
			tokens.add("chibang");
			tokens.add("wing");
			tokens.add("elytra");
		}
		return tokens;
	}

	private static boolean isHideWhenActiveControl(String displayName, String fallbackName) {
		String combined = ((displayName != null ? displayName : "") + " " +
			(fallbackName != null ? fallbackName : "")).toLowerCase();
		String normalized = normalizeBoneName(combined);
		return combined.contains("不显示") || combined.contains("隐藏") ||
			normalized.contains("hide") || normalized.contains("hidden") ||
			normalized.contains("disable") || normalized.contains("notdisplay") ||
			normalized.contains("nodisplay") || normalized.contains("off");
	}

	// ========================================================================
	// Textures
	// ========================================================================

	private static void loadTextures(YsmModelData ysmData, Editor editor) {
		if (ysmData.textures.isEmpty()) return;

		editor.importedTextures = new LinkedHashMap<>(ysmData.textures);
		editor.textureSlots.clear();

		String defaultTex = ysmData.defaultTexture;
		// Fuzzy match: "g" should match "g.png", "不穿！" should match "不穿！.png"
		if (defaultTex != null && !ysmData.textures.containsKey(defaultTex)) {
			String withPng = defaultTex + ".png";
			if (ysmData.textures.containsKey(withPng)) {
				defaultTex = withPng;
			} else {
				// Try prefix match (in case name has case differences or variants)
				final String search = defaultTex.toLowerCase();
				defaultTex = ysmData.textures.keySet().stream()
					.filter(k -> k.toLowerCase().startsWith(search))
					.findFirst().orElse(null);
			}
		}
		if (defaultTex == null || !ysmData.textures.containsKey(defaultTex)) {
			defaultTex = ysmData.textures.keySet().stream()
				.filter(n -> !n.contains("NAF") && !n.contains("_e."))
				.findFirst()
				.orElse(ysmData.textures.keySet().iterator().next());
		}

		int loaded = 0;
		byte[] defPng = ysmData.textures.get(defaultTex);
		if (defPng != null) loaded += loadOneTexture(defPng, defaultTex, editor, ysmData);

		for (Map.Entry<String, byte[]> e : ysmData.textures.entrySet()) {
			if (!e.getKey().equals(defaultTex))
				loaded += loadOneTexture(e.getValue(), e.getKey(), editor, ysmData);
		}

		editor.activeTextureSlot = 0;
		Log.info("[YSM Import] Loaded " + loaded + " texture slots");

		if (!editor.textureSlots.isEmpty()) {
			TextureSlot slot = editor.textureSlots.get(0);
			ETextures skinTex = editor.textures.get(TextureSheetType.SKIN);
			if (skinTex != null && slot.image != null) {
				skinTex.setImage(new Image(slot.image));
				skinTex.provider.size = new Vec2i(slot.gridSize);
				skinTex.setEdited(true);
				skinTex.markDirty();
			}
		}
	}

	private static int loadOneTexture(byte[] pngData, String name, Editor editor, YsmModelData ysmData) {
		try {
			Image img = Image.loadFrom(new ByteArrayInputStream(pngData));
			if (img != null && img.getWidth() <= ETextures.MAX_TEX_SIZE &&
				img.getHeight() <= ETextures.MAX_TEX_SIZE) {
				Vec2i gridSize = computeTextureGrid(ysmData, img);
				boolean customGrid = gridSize.x != img.getWidth() || gridSize.y != img.getHeight();
				editor.textureSlots.add(new TextureSlot(name, img, gridSize, customGrid));
				return 1;
			}
		} catch (IOException e) {
			Log.error("[YSM Import] Texture load failed: " + name, e);
		}
		return 0;
	}

	private static Vec2i computeTextureGrid(YsmModelData ysmData, Image img) {
		int srcWidth = Math.max(1, ysmData.textureWidth);
		int srcHeight = Math.max(1, ysmData.textureHeight);
		int gridWidth = srcWidth * Math.max(1, ysmData.uvScale);
		int gridHeight = srcHeight * Math.max(1, ysmData.uvScale);
		if (gridWidth <= 0) gridWidth = img.getWidth();
		if (gridHeight <= 0) gridHeight = img.getHeight();
		gridWidth = Math.min(gridWidth, ETextures.MAX_TEX_SIZE);
		gridHeight = Math.min(gridHeight, ETextures.MAX_TEX_SIZE);
		return new Vec2i(gridWidth, gridHeight);
	}

	private static int computeUvScale(YsmModelData ysmData) {
		int width = Math.max(1, ysmData.textureWidth);
		int height = Math.max(1, ysmData.textureHeight);
		if (width <= 256 && height <= 256) {
			return SMALL_GRID_UV_SCALE;
		}
		return 1;
	}

	// ========================================================================
	// Model Scale & Metadata
	// ========================================================================

	private static void applyModelScale(YsmModelData ysmData, Editor editor) {
		boolean hasYsmScale = Math.abs(ysmData.heightScale - 1f) > 0.001f ||
			Math.abs(ysmData.widthScale - 1f) > 0.001f;
		if (ysmData.preserveYsmScale && hasYsmScale) {
			editor.scalingElem.enabled = true;
			editor.scalingElem.scale = new Vec3f(
				ysmData.widthScale, ysmData.heightScale, ysmData.widthScale);
			Log.info("[YSM Import] Applied YSM model scale by opt-in flag (h=" +
				ysmData.heightScale + ", w=" + ysmData.widthScale + ")");
			return;
		}

		// Keep geometry parity with BlockBench/plugin output by default.
		// YSM height/width scale is often an avatar/gameplay hint and may shrink/lift model.
		if (hasYsmScale) {
			Log.info("[YSM Import] Ignoring YSM model scale (h=" + ysmData.heightScale +
				", w=" + ysmData.widthScale + ") to preserve CPM geometry parity. " +
				"Set properties.cpm_preserve_scale=true to apply it.");
		}
		editor.scalingElem.enabled = false;
		editor.scalingElem.scale = new Vec3f();
		editor.scalingElem.pos = new Vec3f();
		editor.scalingElem.rotation = new Vec3f();
	}

	private static void stabilizeSkinType(Editor editor) {
		// YSM imports should be deterministic across players. If left implicit,
		// export may inherit current player skin type (e.g. slim), causing width/UV drift.
		editor.customSkinType = true;
		editor.skinType = SkinType.DEFAULT;
	}

	private static void setMetadata(YsmModelData ysmData, Editor editor) {
		if (ysmData.modelName == null || ysmData.modelName.isEmpty()) return;
		if (editor.description == null) {
			editor.description = new com.tom.cpm.shared.editor.util.ModelDescription();
		}
		editor.description.name = ysmData.modelName;

		StringBuilder sb = new StringBuilder();
		if (ysmData.description != null && !ysmData.description.isEmpty())
			sb.append(ysmData.description);
		if (!ysmData.authors.isEmpty()) {
			if (sb.length() > 0) sb.append("\n\n");
			sb.append("Authors: ").append(String.join(", ", ysmData.authors));
		}
		if (sb.length() > 0) editor.description.desc = sb.toString();
	}
}
