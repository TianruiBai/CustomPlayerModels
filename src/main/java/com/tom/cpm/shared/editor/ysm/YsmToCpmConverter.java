package com.tom.cpm.shared.editor.ysm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Main orchestrator that converts parsed YSM data ({@link YsmModelData}) into
 * CPM editor state ({@link Editor}).
 *
 * <p>This is the bridge between Bedrock-format data and CPM's internal model
 * representation. It handles:
 * <ul>
 *   <li>Bone hierarchy → CPM {@link ModelElement} tree under root parts</li>
 *   <li>Bedrock cube geometry → CPM {@link ModelElement} children</li>
 *   <li>Per-face UV → CPM {@link PerFaceUV}</li>
 *   <li>Bedrock animations → CPM {@link EditorAnim}</li>
 *   <li>Texture loading → CPM {@link ETextures}</li>
 * </ul>
 */
public class YsmToCpmConverter {

	/**
	 * Convert parsed YSM data into the given CPM editor.
	 * The editor should already have been reset via {@link Editor#loadDefaultPlayerModel()}.
	 *
	 * @param ysmData the parsed YSM project data
	 * @param editor  the CPM editor to populate
	 */
	/** Skip arm.json import in Phase 6 to avoid first-person hand regressions. */
	private static final boolean IMPORT_ARM_MODEL = false;

	public static void convert(YsmModelData ysmData, Editor editor) {
		Log.info("[YSM Import] Starting conversion of: " + ysmData.modelName);

		// 1. Parse bones from model JSONs
		List<BedrockBone> mainBones = BedrockModelParser.parse(ysmData.mainModelJson);
		List<BedrockBone> armBones;
		if (IMPORT_ARM_MODEL) {
			armBones = BedrockModelParser.parse(ysmData.armModelJson);
			Log.info("[YSM Import] Main bones: " + mainBones.size() + ", Arm bones: " + armBones.size());
		} else {
			armBones = java.util.Collections.emptyList();
			Log.info("[YSM Import] Main bones: " + mainBones.size() + ", Arm bones: 0 (arm.json skipped by Phase 6 policy)");
		}

		// 2. Hide vanilla root part cubes (the default player model)
		for (ModelElement rootElem : editor.elements) {
			rootElem.hidden = true;
		}

		// 3. Build ModelElement hierarchy under each CPM root part
		Map<String, ModelElement> allBoneElements = new HashMap<>();

		for (PlayerModelParts part : PlayerModelParts.VALUES) {
			if (part == PlayerModelParts.CUSTOM_PART) continue;

			ModelElement rootElem = findRootElement(editor, part);
			if (rootElem == null) continue;

			// Find the top-level bone that maps to this part
			String rootBoneName = BedrockModelParser.findRootBoneForPart(mainBones, part);
			if (rootBoneName != null) {
				BedrockBone rootBone = findBoneByName(mainBones, rootBoneName);
				if (rootBone != null) {
					// Root-level bones: position relative to CPM root (0,0,0)
					Vec3f parentPivot = new Vec3f();
					buildBoneHierarchy(rootBone, mainBones, rootElem, editor, allBoneElements, parentPivot);
				}
			}
		}

		Log.info("[YSM Import] Created " + allBoneElements.size() + " bone elements");

		// 4. Process arm bones under their respective arm root parts
		processArmBones(armBones, editor, allBoneElements);

		// 5. Convert animations
		convertAnimations(ysmData, editor, allBoneElements);

		// 6. Setup gesture buttons from extra_animation + controller data
		setupGestures(ysmData, editor);

		// 7. Load textures
		loadTextures(ysmData, editor);

		// 8. Apply model scaling properties from ysm.json
		applyModelScale(ysmData, editor);

		// 9. Set model metadata (name, description, authors)
		if (ysmData.modelName != null && !ysmData.modelName.isEmpty()) {
			if (editor.description == null) {
				editor.description = new com.tom.cpm.shared.editor.util.ModelDescription();
			}
			editor.description.name = ysmData.modelName;

			// Build description with author info
			StringBuilder descBuilder = new StringBuilder();
			if (ysmData.description != null && !ysmData.description.isEmpty()) {
				descBuilder.append(ysmData.description);
			}
			if (!ysmData.authors.isEmpty()) {
				if (descBuilder.length() > 0) descBuilder.append("\n\n");
				descBuilder.append("Authors: ");
				descBuilder.append(String.join(", ", ysmData.authors));
			}
			if (descBuilder.length() > 0) {
				editor.description.desc = descBuilder.toString();
			}
			Log.info("[YSM Import] Model name: " + ysmData.modelName +
				(ysmData.authors.isEmpty() ? "" : ", authors: " + String.join(", ", ysmData.authors)));
		}

		Log.info("[YSM Import] Conversion complete — " + editor.elements.size() +
			" root elements, " + editor.animations.size() + " animations, " +
			(editor.textures.containsKey(TextureSheetType.SKIN) ? "with texture" : "no texture"));
	}

	/**
	 * Recursively build the CPM ModelElement hierarchy from a Bedrock bone tree.
	 * Bone positions are made relative to the parent bone's pivot.
	 *
	 * @param parentPivot the pivot position of the parent bone (for relative positioning)
	 */
	private static void buildBoneHierarchy(BedrockBone bone, List<BedrockBone> allBones,
	                                       ModelElement parent, Editor editor,
	                                       Map<String, ModelElement> allBoneElements,
	                                       Vec3f parentPivot) {
		ModelElement elem = new ModelElement(editor);
		elem.name = bone.name;
		elem.parent = parent;
		parent.children.add(elem);
		allBoneElements.put(bone.name, elem);

		// Position = bone pivot - parent pivot (relative to parent)
		elem.pos = new Vec3f(bone.pivot).sub(parentPivot);

		if (bone.rotation.x != 0 || bone.rotation.y != 0 || bone.rotation.z != 0) {
			elem.rotation = new Vec3f(bone.rotation);
		}

		// Apply bone-level mirror to this element
		if (bone.mirror) {
			elem.mirror = true;
		}

		// Create cube elements for each Bedrock cube in this bone
		for (BedrockCube cube : bone.cubes) {
			ModelElement cubeElem = new ModelElement(editor);
			cubeElem.name = bone.name + "_cube";
			cubeElem.parent = elem;
			elem.children.add(cubeElem);
			allBoneElements.put(bone.name + "_cube_" + elem.children.size(), cubeElem);

			cubeElem.size = new Vec3f(cube.size);
			// cube offset = cube origin - bone pivot (relative to bone position)
			cubeElem.offset = cube.origin.sub(bone.pivot);

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

			// Inflate → meshScale: Bedrock inflate grows cube by 'inflate' units
			// in all directions. CPM meshScale renders the cube at size*meshScale.
			// For a cube of size S with inflate I, rendered size = S + 2*I.
			// Therefore meshScale per axis = (S + 2*I) / S = 1 + 2*I/S.
			if (cube.inflate != 0) {
				float sx = cube.size.x != 0 ? 1.0f + 2.0f * cube.inflate / cube.size.x : 1.0f;
				float sy = cube.size.y != 0 ? 1.0f + 2.0f * cube.inflate / cube.size.y : 1.0f;
				float sz = cube.size.z != 0 ? 1.0f + 2.0f * cube.inflate / cube.size.z : 1.0f;
				cubeElem.meshScale = new Vec3f(sx, sy, sz);
			}

			// Cube-level mirror
			cubeElem.mirror = cube.mirror || bone.mirror;
		}

		// Process child bones with this bone's pivot as the new parentPivot
		for (BedrockBone child : allBones) {
			if (bone.name.equals(child.parent)) {
				buildBoneHierarchy(child, allBones, elem, editor, allBoneElements, bone.pivot);
			}
		}
	}

	/**
	 * Map arm bones to the LEFT_ARM and RIGHT_ARM root parts.
	 */
	private static void processArmBones(List<BedrockBone> armBones,
	                                    Editor editor, Map<String, ModelElement> allBoneElements) {
		if (armBones.isEmpty()) return;

		ModelElement leftArmRoot = findRootElement(editor, PlayerModelParts.LEFT_ARM);
		ModelElement rightArmRoot = findRootElement(editor, PlayerModelParts.RIGHT_ARM);

		for (BedrockBone bone : armBones) {
			String lowerName = bone.name.toLowerCase();
			ModelElement targetRoot = null;

			if (lowerName.contains("left")) {
				targetRoot = leftArmRoot;
			} else if (lowerName.contains("right")) {
				targetRoot = rightArmRoot;
			}

			if (targetRoot != null && bone.parent == null) {
				Vec3f parentPivot = new Vec3f(); // arm bones are relative to root
				buildBoneHierarchy(bone, armBones, targetRoot, editor, allBoneElements, parentPivot);
			}
		}
	}

	/**
	 * Convert all animations from YSM data into CPM EditorAnims.
	 * Each animation is parsed individually — one bad animation won't crash the entire import.
	 */
	private static void convertAnimations(YsmModelData ysmData, Editor editor,
	                                      Map<String, ModelElement> allBoneElements) {
		int totalAnims = 0;
		totalAnims += parseAnimJsonSafely(ysmData.mainAnimJson, editor, allBoneElements, AnimationType.POSE, "main");
		totalAnims += parseAnimJsonSafely(ysmData.armAnimJson, editor, allBoneElements, AnimationType.POSE, "arm");
		totalAnims += parseAnimJsonSafely(ysmData.extraAnimJson, editor, allBoneElements, AnimationType.GESTURE, "extra");

		// Also parse any additional animation files (tac, carryon, etc.)
		for (Map.Entry<String, String> extraAnimEntry : ysmData.extraAnimFiles.entrySet()) {
			try {
				com.google.gson.JsonObject extraAnimJson =
					com.google.gson.JsonParser.parseString(extraAnimEntry.getValue()).getAsJsonObject();
				int n = parseAnimJsonSafely(extraAnimJson, editor, allBoneElements, AnimationType.GESTURE, extraAnimEntry.getKey());
				totalAnims += n;
				Log.info("[YSM Import] Extra anim file '" + extraAnimEntry.getKey() + "': " + n + " animations");
			} catch (Exception e) {
				Log.warn("[YSM Import] Failed to parse extra animation: " + extraAnimEntry.getKey(), e);
			}
		}

		Log.info("[YSM Import] Total animations converted: " + totalAnims);
	}

	/** Parse an animation JSON safely, catching per-animation errors */
	private static int parseAnimJsonSafely(com.google.gson.JsonObject animJson, Editor editor,
	                                       Map<String, ModelElement> allBoneElements,
	                                       AnimationType type, String label) {
		if (animJson == null) return 0;
		try {
			List<EditorAnim> anims = BedrockAnimationParser.parse(animJson, editor, allBoneElements, type);
			editor.animations.addAll(anims);
			Log.info("[YSM Import] " + label + " animations: " + anims.size());
			return anims.size();
		} catch (Exception e) {
			Log.error("[YSM Import] Failed to parse " + label + " animations", e);
			return 0;
		}
	}

	/**
	 * Setup gesture buttons from ysm.json extra_animation and controller data.
	 * Maps YSM gesture names to their corresponding EditorAnim instances
	 * and logs the mappings for the user.
	 */
	private static void setupGestures(YsmModelData ysmData, Editor editor) {
		// Create AnimationEncodingData if not present
		if (editor.animEnc == null) {
			editor.animEnc = new AnimationEncodingData();
		}

		int mappedCount = 0;

		// Merge extra_animation mappings from ysm.json
		for (Map.Entry<String, String> entry : ysmData.extraAnimations.entrySet()) {
			String gestureName = entry.getKey();
			String animName = entry.getValue();

			// Find the corresponding EditorAnim
			EditorAnim targetAnim = editor.animations.stream()
				.filter(a -> a.displayName != null && a.displayName.equals(animName))
				.findFirst().orElse(null);

			if (targetAnim != null) {
				// Ensure the animation is typed as GESTURE for proper gesture handling
				if (targetAnim.type != AnimationType.GESTURE && targetAnim.type != AnimationType.CUSTOM_POSE) {
					targetAnim.type = AnimationType.GESTURE;
				}
				mappedCount++;
			} else {
				Log.info("[YSM Import] Gesture '" + gestureName + "' references unknown animation '" + animName + "'");
			}
		}

		Log.info("[YSM Import] Gesture mappings: " + mappedCount + " of " + ysmData.extraAnimations.size() + " gestures mapped");

		// Process controller data for additional gesture mappings
		if (ysmData.controllerJson != null) {
			Map<String, String> ctrlGestures =
				BedrockControllerParser.extractGestureMappings(ysmData.controllerJson);
			for (Map.Entry<String, String> entry : ctrlGestures.entrySet()) {
				if (!ysmData.extraAnimations.containsKey(entry.getKey())) {
					ysmData.extraAnimations.put(entry.getKey(), entry.getValue());
				}
			}
			Log.info("[YSM Import] Controller gestures: " + ctrlGestures.size() +
				" (molang transitions: " +
				BedrockControllerParser.hasMolangTransitions(ysmData.controllerJson) + ")");

			// Log referenced animation names
			List<String> refAnims = BedrockControllerParser.extractAnimationNames(ysmData.controllerJson);
			if (!refAnims.isEmpty()) {
				Log.info("[YSM Import] Controller references " + refAnims.size() + " animations");
			}
		}
	}

	/**
	 * Find the root ModelElement for a given player model part in the editor's element list.
	 */
	private static ModelElement findRootElement(Editor editor, PlayerModelParts part) {
		for (ModelElement elem : editor.elements) {
			if (elem.type == ElementType.ROOT_PART && elem.typeData == part) {
				return elem;
			}
		}
		return null;
	}

	private static BedrockBone findBoneByName(List<BedrockBone> bones, String name) {
		return bones.stream().filter(b -> b.name.equals(name)).findFirst().orElse(null);
	}

	/**
	 * Load textures from YSM data into the editor's texture slot system.
	 * The default texture becomes slot 0; all others become additional slots.
	 * Also stores raw bytes for backward compat via {@code editor.importedTextures}.
	 */
	private static void loadTextures(YsmModelData ysmData, Editor editor) {
		if (ysmData.textures.isEmpty()) {
			Log.info("[YSM Import] No textures found");
			return;
		}

		Log.info("[YSM Import] Found " + ysmData.textures.size() + " textures: " +
			String.join(", ", ysmData.textures.keySet()));

		// Backward compat: keep importedTextures populated
		editor.importedTextures = new HashMap<>(ysmData.textures);

		// Clear existing slots (keep slot 0 structure, we'll replace its image)
		editor.textureSlots.clear();

		// Determine which texture to use as slot 0 (the default/active one)
		String defaultTexName = ysmData.defaultTexture;
		if (defaultTexName != null && !ysmData.textures.containsKey(defaultTexName)) {
			Log.info("[YSM Import] Default texture '" + defaultTexName + "' not found, using first available");
			defaultTexName = null;
		}
		if (defaultTexName == null) {
			defaultTexName = ysmData.textures.keySet().stream()
				.filter(n -> !n.contains("NAF") && !n.contains("_e."))
				.findFirst()
				.orElse(ysmData.textures.keySet().iterator().next());
		}

		// Create texture slots: default texture first (slot 0), then the rest
		int loadedCount = 0;
		// Add default texture as slot 0
		byte[] defaultPng = ysmData.textures.get(defaultTexName);
		if (defaultPng != null) {
			try {
				Image img = Image.loadFrom(new ByteArrayInputStream(defaultPng));
				if (img != null && img.getWidth() <= ETextures.MAX_TEX_SIZE && img.getHeight() <= ETextures.MAX_TEX_SIZE) {
					TextureSlot slot = new TextureSlot(defaultTexName, img, new Vec2i(img.getWidth(), img.getHeight()), false);
					editor.textureSlots.add(slot);
					loadedCount++;
				}
			} catch (IOException e) {
				Log.error("[YSM Import] Failed to load default texture: " + defaultTexName, e);
			}
		}

		// Add remaining textures as additional slots
		for (Map.Entry<String, byte[]> entry : ysmData.textures.entrySet()) {
			if (entry.getKey().equals(defaultTexName)) continue;
			try {
				Image img = Image.loadFrom(new ByteArrayInputStream(entry.getValue()));
				if (img != null && img.getWidth() <= ETextures.MAX_TEX_SIZE && img.getHeight() <= ETextures.MAX_TEX_SIZE) {
					TextureSlot slot = new TextureSlot(entry.getKey(), img, new Vec2i(img.getWidth(), img.getHeight()), false);
					editor.textureSlots.add(slot);
					loadedCount++;
				}
			} catch (IOException e) {
				Log.warn("[YSM Import] Failed to load texture: " + entry.getKey(), e);
			}
		}

		editor.activeTextureSlot = 0;
		Log.info("[YSM Import] Loaded " + loadedCount + " texture slots" +
			(loadedCount > 1 ? " (use Skin Settings → Texture Slots to switch)" : ""));

		// Apply slot 0 as active SKIN texture
		if (!editor.textureSlots.isEmpty()) {
			TextureSlot activeSlot = editor.textureSlots.get(0);
			ETextures skinTex = editor.textures.get(TextureSheetType.SKIN);
			if (skinTex != null && activeSlot.image != null) {
				skinTex.setImage(new Image(activeSlot.image));
				skinTex.provider.size = new Vec2i(activeSlot.gridSize);
				skinTex.setEdited(true);
				skinTex.markDirty();
				Log.info("[YSM Import] Active texture: " + activeSlot.name +
					" (" + activeSlot.image.getWidth() + "x" + activeSlot.image.getHeight() + ")");
			}
		}
	}

	/**
	 * Apply model scaling from YSM height_scale and width_scale properties.
	 */
	private static void applyModelScale(YsmModelData ysmData, Editor editor) {
		float hs = ysmData.heightScale;
		float ws = ysmData.widthScale;

		if (Math.abs(hs - 1.0f) > 0.001f || Math.abs(ws - 1.0f) > 0.001f) {
			// Enable scaling and set values
			editor.scalingElem.enabled = true;
			editor.scalingElem.scale = new Vec3f(ws, hs, ws);
			Log.info("[YSM Import] Applied model scale: height=" + hs + ", width=" + ws);
		}
	}
}
