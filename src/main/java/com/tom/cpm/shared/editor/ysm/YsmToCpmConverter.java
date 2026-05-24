package com.tom.cpm.shared.editor.ysm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.TextureSlot;
import com.tom.cpm.shared.editor.anim.AnimationEncodingData;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockBone;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockCube;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.PerFaceUV;
import com.tom.cpm.shared.util.Log;

/**
 * Main orchestrator that converts parsed YSM data into CPM editor state.
 *
 * <p><b>v2 - Subtree-preserving approach:</b> YSM bone subtrees are kept intact
 * under a single CPM root part. Only the top-level bone position is adjusted
 * for the CPM root part vanilla Minecraft position.
 */
public class YsmToCpmConverter {

	private static final boolean IMPORT_ARM_MODEL = false;

	public static void convert(YsmModelData ysmData, Editor editor) {
		Log.info("[YSM Import] Starting conversion of: " + ysmData.modelName);

		List<BedrockBone> mainBones = BedrockModelParser.parse(ysmData.mainModelJson);
		List<BedrockBone> armBones = IMPORT_ARM_MODEL
			? BedrockModelParser.parse(ysmData.armModelJson) : Collections.emptyList();
		Log.info("[YSM Import] Main bones: " + mainBones.size() +
			", Arm bones: " + (IMPORT_ARM_MODEL ? armBones.size() : "0 (skipped)"));

		ModelConversionResult result = convertModel(mainBones, armBones, ysmData, editor);
		convertAnimations(ysmData, editor, result.allBoneElements, result.ysmWorldPositions, result.boneIndex);
		setupGestures(ysmData, editor);
		loadTextures(ysmData, editor);
		applyModelScale(ysmData, editor);
		setMetadata(ysmData, editor);

		Log.info("[YSM Import] Conversion complete - " + editor.elements.size() +
			" root elements, " + editor.animations.size() + " animations");
	}

	private static class ModelConversionResult {
		final Map<String, ModelElement> allBoneElements;
		final Map<String, Vec3f> ysmWorldPositions;
		final Map<String, BedrockBone> boneIndex;

		ModelConversionResult(Map<String, ModelElement> e, Map<String, Vec3f> w, Map<String, BedrockBone> b) {
			this.allBoneElements = e; this.ysmWorldPositions = w; this.boneIndex = b;
		}
	}

	// ========================================================================
	// Model Conversion (v2 - subtree-preserving)
	// ========================================================================

	private static ModelConversionResult convertModel(List<BedrockBone> mainBones,
			List<BedrockBone> armBones, YsmModelData ysmData, Editor editor) {
		for (ModelElement rootElem : editor.elements) {
			rootElem.hidden = true;
		}

		Map<String, BedrockBone> boneIndex = new LinkedHashMap<>();
		for (BedrockBone b : mainBones) { boneIndex.put(b.name, b); }
		Map<String, List<BedrockBone>> childrenMap = YsmBoneClassifier.buildChildrenMap(mainBones);

		Map<String, Vec3f> ysmWorldPositions = new LinkedHashMap<>();
		for (BedrockBone bone : mainBones) {
			ysmWorldPositions.put(bone.name, YsmCoordUtil.computeYsmWorldPosition(bone.name, boneIndex));
		}

		Map<PlayerModelParts, ModelElement> cpmRoots = new EnumMap<>(PlayerModelParts.class);
		for (PlayerModelParts part : PlayerModelParts.VALUES) {
			if (part == PlayerModelParts.CUSTOM_PART) continue;
			ModelElement r = findRootElement(editor, part);
			if (r != null) cpmRoots.put(part, r);
		}

		List<YsmSubtreeInfo> subtrees = identifySubtrees(mainBones, boneIndex);
		Log.info("[YSM Import] Identified " + subtrees.size() + " YSM subtrees");

		Map<String, ModelElement> allBoneElements = new HashMap<>();
		Map<PlayerModelParts, Integer> partCounts = new LinkedHashMap<>();

		for (YsmSubtreeInfo subtree : subtrees) {
			PlayerModelParts part = YsmBoneClassifier.classifySubtree(subtree.rootBone, mainBones, boneIndex);
			ModelElement cpmRoot = cpmRoots.getOrDefault(part, getOrCreateOrphanRoot(editor));
			Vec3f vanillaPos = YsmCoordUtil.getVanillaPartPosition(part);
			Vec3f adjustedPos = subtree.rootBone.pivot.sub(vanillaPos);

			buildSubtree(subtree.rootBone, mainBones, cpmRoot, adjustedPos,
				boneIndex, childrenMap, allBoneElements, editor, partCounts, part);
		}

		for (Map.Entry<PlayerModelParts, Integer> e : partCounts.entrySet()) {
			Log.info("[YSM Import] CPM part " + e.getKey().name() + ": " + e.getValue() + " bones");
		}

		importExtraModels(ysmData, editor, allBoneElements);
		processArmBones(armBones, editor, allBoneElements);

		Log.info("[YSM Import] Created " + allBoneElements.size() + " bone elements");
		return new ModelConversionResult(allBoneElements, ysmWorldPositions, boneIndex);
	}

	private static List<YsmSubtreeInfo> identifySubtrees(List<BedrockBone> allBones,
			Map<String, BedrockBone> boneIndex) {
		List<YsmSubtreeInfo> subtrees = new ArrayList<>();
		Map<String, PlayerModelParts> partCache = new HashMap<>();

		for (BedrockBone bone : allBones) {
			partCache.put(bone.name, YsmBoneClassifier.classifyBoneQuick(bone, allBones, boneIndex));
		}

		for (BedrockBone bone : allBones) {
			if (bone.parent == null) {
				subtrees.add(new YsmSubtreeInfo(bone, partCache.getOrDefault(bone.name, PlayerModelParts.BODY)));
			} else {
				PlayerModelParts myPart = partCache.get(bone.name);
				PlayerModelParts parentPart = partCache.get(bone.parent);
				if (myPart != null && parentPart != null && myPart != parentPart) {
					subtrees.add(new YsmSubtreeInfo(bone, myPart));
				}
			}
		}
		return subtrees;
	}

	private static void buildSubtree(BedrockBone bone, List<BedrockBone> allBones,
			ModelElement cpmParent, Vec3f topPos,
			Map<String, BedrockBone> boneIndex,
			Map<String, List<BedrockBone>> childrenMap,
			Map<String, ModelElement> allElements, Editor editor,
			Map<PlayerModelParts, Integer> partCounts, PlayerModelParts part) {
		ModelElement elem = new ModelElement(editor);
		elem.name = bone.name;
		elem.parent = cpmParent;
		cpmParent.children.add(elem);
		allElements.put(bone.name, elem);

		if (topPos != null) {
			elem.pos = new Vec3f(topPos);
		} else {
			BedrockBone parentBone = boneIndex.get(bone.parent);
			elem.pos = parentBone != null ? bone.pivot.sub(parentBone.pivot) : new Vec3f(bone.pivot);
		}

		if (YsmCoordUtil.isNonZero(bone.rotation)) {
			elem.rotation = new Vec3f(bone.rotation);
		}
		if (bone.neverRender || bone.cubes.isEmpty()) {
			elem.hidden = true;
		}
		if (bone.mirror) {
			elem.mirror = true;
		}

		partCounts.merge(part, 1, Integer::sum);

		for (BedrockCube cube : bone.cubes) {
			createCubeForBone(cube, bone, elem, editor, allElements);
		}

		List<BedrockBone> children = childrenMap.get(bone.name);
		if (children != null) {
			for (BedrockBone child : children) {
				buildSubtree(child, allBones, elem, null,
					boneIndex, childrenMap, allElements, editor, partCounts, part);
			}
		}
	}

	private static void createCubeForBone(BedrockCube cube, BedrockBone bone,
			ModelElement parentElem, Editor editor, Map<String, ModelElement> allElements) {
		ModelElement cubeElem = new ModelElement(editor);
		cubeElem.name = bone.name + "_cube";
		cubeElem.parent = parentElem;
		parentElem.children.add(cubeElem);
		allElements.put(bone.name + "_cube_" + parentElem.children.size(), cubeElem);
		cubeElem.size = new Vec3f(cube.size);

		if (cube.pivot != null && cube.rotation != null) {
			cubeElem.offset = cube.origin.sub(cube.pivot);
			cubeElem.pos = cube.pivot.sub(bone.pivot);
			cubeElem.rotation = new Vec3f(cube.rotation);
		} else if (cube.pivot != null) {
			cubeElem.offset = cube.origin.sub(cube.pivot);
			cubeElem.pos = cube.pivot.sub(bone.pivot);
		} else if (cube.rotation != null) {
			cubeElem.offset = cube.origin.sub(bone.pivot);
			Vec3f center = new Vec3f(cube.size).mul(0.5f);
			cubeElem.pos = new Vec3f(center);
			cubeElem.offset = cubeElem.offset.sub(center);
			cubeElem.rotation = new Vec3f(cube.rotation);
		} else {
			cubeElem.offset = cube.origin.sub(bone.pivot);
		}

		cubeElem.texture = true;
		cubeElem.textureSize = 1;

		PerFaceUV pfUV = BedrockModelParser.convertPerFaceUV(cube);
		if (pfUV != null) {
			cubeElem.faceUV = pfUV;
		} else {
			Vec2i primaryUV = BedrockModelParser.getPrimaryUV(cube);
			if (primaryUV != null) {
				cubeElem.u = primaryUV.x;
				cubeElem.v = primaryUV.y;
			}
		}

		if (cube.inflate != 0) {
			cubeElem.meshScale = new Vec3f(
				YsmCoordUtil.safeMeshScale(cube.size.x, cube.inflate),
				YsmCoordUtil.safeMeshScale(cube.size.y, cube.inflate),
				YsmCoordUtil.safeMeshScale(cube.size.z, cube.inflate));
		}

		cubeElem.mirror = cube.mirror ^ bone.mirror;
	}

	// ========================================================================
	// Extra Models
	// ========================================================================

	private static void importExtraModels(YsmModelData ysmData, Editor editor,
			Map<String, ModelElement> allBoneElements) {
		int extraModelCount = 0;
		for (Map.Entry<String, JsonObject> extraEntry : ysmData.extraModelJsons.entrySet()) {
			String modelKey = extraEntry.getKey();
			List<BedrockBone> extraBones = BedrockModelParser.parse(extraEntry.getValue());
			if (extraBones.isEmpty()) continue;

			ModelElement container = new ModelElement(editor);
			container.name = "YSM::" + modelKey;
			container.type = ElementType.NORMAL;
			container.parent = null;
			editor.elements.add(container);

			Map<String, BedrockBone> extraIndex = new LinkedHashMap<>();
			for (BedrockBone b : extraBones) { extraIndex.put(b.name, b); }
			Map<String, List<BedrockBone>> extraChildren = YsmBoneClassifier.buildChildrenMap(extraBones);

			for (BedrockBone bone : extraBones) {
				if (bone.parent != null) continue;
				buildSubtree(bone, extraBones, container, bone.pivot,
					extraIndex, extraChildren, allBoneElements, editor,
					new LinkedHashMap<>(), PlayerModelParts.CUSTOM_PART);
			}
			extraModelCount++;
		}
		if (extraModelCount > 0) {
			Log.info("[YSM Import] Imported " + extraModelCount + " extra model groups");
		}
	}

	// ========================================================================
	// Arm Bones
	// ========================================================================

	private static void processArmBones(List<BedrockBone> armBones,
			Editor editor, Map<String, ModelElement> allBoneElements) {
		if (armBones.isEmpty()) return;

		ModelElement leftArmRoot = findRootElement(editor, PlayerModelParts.LEFT_ARM);
		ModelElement rightArmRoot = findRootElement(editor, PlayerModelParts.RIGHT_ARM);
		Map<String, BedrockBone> armIndex = new LinkedHashMap<>();
		for (BedrockBone b : armBones) { armIndex.put(b.name, b); }
		Map<String, List<BedrockBone>> armChildren = YsmBoneClassifier.buildChildrenMap(armBones);

		for (BedrockBone bone : armBones) {
			if (bone.parent != null) continue;
			String lowerName = bone.name.toLowerCase();
			ModelElement targetRoot;
			PlayerModelParts part;

			if (lowerName.contains("left")) {
				targetRoot = leftArmRoot; part = PlayerModelParts.LEFT_ARM;
			} else if (lowerName.contains("right")) {
				targetRoot = rightArmRoot; part = PlayerModelParts.RIGHT_ARM;
			} else {
				continue;
			}

			if (targetRoot != null) {
				Vec3f adjustedPos = bone.pivot.sub(YsmCoordUtil.getVanillaPartPosition(part));
				buildSubtree(bone, armBones, targetRoot, adjustedPos,
					armIndex, armChildren, allBoneElements, editor, new LinkedHashMap<>(), part);
			}
		}
	}

	// ========================================================================
	// Animation Conversion
	// ========================================================================

	private static void convertAnimations(YsmModelData ysmData, Editor editor,
			Map<String, ModelElement> allBoneElements,
			Map<String, Vec3f> ysmWorldPositions, Map<String, BedrockBone> boneIndex) {
		int total = 0;
		total += parseAnim(ysmData.mainAnimJson, editor, allBoneElements, AnimationType.POSE, ysmWorldPositions, boneIndex);
		total += parseAnim(ysmData.armAnimJson, editor, allBoneElements, AnimationType.POSE, ysmWorldPositions, boneIndex);
		total += parseAnim(ysmData.extraAnimJson, editor, allBoneElements, AnimationType.GESTURE, ysmWorldPositions, boneIndex);

		for (Map.Entry<String, String> e : ysmData.extraAnimFiles.entrySet()) {
			try {
				com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(e.getValue()).getAsJsonObject();
				total += parseAnim(json, editor, allBoneElements, AnimationType.GESTURE, ysmWorldPositions, boneIndex);
			} catch (Exception ex) {
				Log.warn("[YSM Import] Failed extra anim: " + e.getKey(), ex);
			}
		}
		Log.info("[YSM Import] Total animations: " + total);
	}

	private static int parseAnim(com.google.gson.JsonObject json, Editor editor,
			Map<String, ModelElement> allBoneElements, AnimationType type,
			Map<String, Vec3f> ysmWorldPositions, Map<String, BedrockBone> boneIndex) {
		if (json == null) return 0;
		try {
			List<EditorAnim> anims = BedrockAnimationParser.parse(json, editor, allBoneElements, type, ysmWorldPositions, boneIndex);
			editor.animations.addAll(anims);
			return anims.size();
		} catch (Exception e) {
			Log.error("[YSM Import] Animation parse failed", e);
			return 0;
		}
	}

	// ========================================================================
	// Gestures, Helpers, Textures, Scale, Metadata
	// ========================================================================

	private static void setupGestures(YsmModelData ysmData, Editor editor) {
		if (editor.animEnc == null) editor.animEnc = new AnimationEncodingData();
		for (Map.Entry<String, String> entry : ysmData.extraAnimations.entrySet()) {
			EditorAnim target = editor.animations.stream()
				.filter(a -> a.displayName != null && a.displayName.equals(entry.getValue()))
				.findFirst().orElse(null);
			if (target != null && target.type != AnimationType.GESTURE && target.type != AnimationType.CUSTOM_POSE) {
				target.type = AnimationType.GESTURE;
			}
		}
		if (ysmData.controllerJson != null) {
			Map<String, String> ctrlGestures = BedrockControllerParser.extractGestureMappings(ysmData.controllerJson);
			ctrlGestures.forEach((k, v) -> ysmData.extraAnimations.putIfAbsent(k, v));
		}
	}

	private static ModelElement findRootElement(Editor editor, PlayerModelParts part) {
		for (ModelElement elem : editor.elements) {
			if (elem.type == ElementType.ROOT_PART && elem.typeData == part) return elem;
		}
		return null;
	}

	private static ModelElement getOrCreateOrphanRoot(Editor editor) {
		for (ModelElement elem : editor.elements) {
			if ("YSM_UNMAPPED".equals(elem.name) && elem.type == ElementType.NORMAL) return elem;
		}
		ModelElement r = new ModelElement(editor);
		r.name = "YSM_UNMAPPED"; r.type = ElementType.NORMAL; r.parent = null;
		editor.elements.add(r);
		return r;
	}

	private static void loadTextures(YsmModelData ysmData, Editor editor) {
		if (ysmData.textures.isEmpty()) return;
		editor.importedTextures = new HashMap<>(ysmData.textures);
		editor.textureSlots.clear();

		String defaultTex = ysmData.defaultTexture;
		if (defaultTex == null || !ysmData.textures.containsKey(defaultTex)) {
			defaultTex = ysmData.textures.keySet().stream()
				.filter(n -> !n.contains("NAF") && !n.contains("_e."))
				.findFirst().orElse(ysmData.textures.keySet().iterator().next());
		}

		int loaded = 0;
		byte[] defPng = ysmData.textures.get(defaultTex);
		if (defPng != null) loaded += loadOneTexture(defPng, defaultTex, editor);

		for (Map.Entry<String, byte[]> e : ysmData.textures.entrySet()) {
			if (!e.getKey().equals(defaultTex)) loaded += loadOneTexture(e.getValue(), e.getKey(), editor);
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

	private static int loadOneTexture(byte[] pngData, String name, Editor editor) {
		try {
			Image img = Image.loadFrom(new ByteArrayInputStream(pngData));
			if (img != null && img.getWidth() <= ETextures.MAX_TEX_SIZE && img.getHeight() <= ETextures.MAX_TEX_SIZE) {
				editor.textureSlots.add(new TextureSlot(name, img, new Vec2i(img.getWidth(), img.getHeight()), false));
				return 1;
			}
		} catch (IOException e) {
			Log.error("[YSM Import] Texture load failed: " + name, e);
		}
		return 0;
	}

	private static void applyModelScale(YsmModelData ysmData, Editor editor) {
		if (Math.abs(ysmData.heightScale - 1f) > 0.001f || Math.abs(ysmData.widthScale - 1f) > 0.001f) {
			editor.scalingElem.enabled = true;
			editor.scalingElem.scale = new Vec3f(ysmData.widthScale, ysmData.heightScale, ysmData.widthScale);
		}
	}

	private static void setMetadata(YsmModelData ysmData, Editor editor) {
		if (ysmData.modelName == null || ysmData.modelName.isEmpty()) return;
		if (editor.description == null) editor.description = new com.tom.cpm.shared.editor.util.ModelDescription();
		editor.description.name = ysmData.modelName;

		StringBuilder sb = new StringBuilder();
		if (ysmData.description != null && !ysmData.description.isEmpty()) sb.append(ysmData.description);
		if (!ysmData.authors.isEmpty()) {
			if (sb.length() > 0) sb.append("\n\n");
			sb.append("Authors: ").append(String.join(", ", ysmData.authors));
		}
		if (sb.length() > 0) editor.description.desc = sb.toString();
	}
}
