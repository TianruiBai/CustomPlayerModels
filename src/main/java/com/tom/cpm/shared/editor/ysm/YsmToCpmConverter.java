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
	public static void convert(YsmModelData ysmData, Editor editor) {
		Log.info("[YSM Import] Starting conversion of: " + ysmData.modelName);

		// 1. Parse bones from model JSONs
		List<BedrockBone> mainBones = BedrockModelParser.parse(ysmData.mainModelJson);
		List<BedrockBone> armBones = BedrockModelParser.parse(ysmData.armModelJson);

		Log.info("[YSM Import] Main bones: " + mainBones.size() + ", Arm bones: " + armBones.size());

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

		// 8. Set model metadata
		if (ysmData.modelName != null && !ysmData.modelName.isEmpty()) {
			if (editor.description == null) {
				editor.description = new com.tom.cpm.shared.editor.util.ModelDescription();
			}
			editor.description.name = ysmData.modelName;
			if (ysmData.description != null && !ysmData.description.isEmpty()) {
				editor.description.desc = ysmData.description;
			}
		}

		Log.info("[YSM Import] Conversion complete");
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

			if (cube.inflate != 0) {
				float maxDim = Math.max(cube.size.x, Math.max(cube.size.y, cube.size.z));
				if (maxDim > 0.001f) {
					float s = 1.0f + cube.inflate / maxDim;
					cubeElem.meshScale = new Vec3f(s, s, s);
				}
			}

			cubeElem.mirror = cube.mirror;
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
	 */
	private static void convertAnimations(YsmModelData ysmData, Editor editor,
	                                      Map<String, ModelElement> allBoneElements) {
		if (ysmData.mainAnimJson != null) {
			List<EditorAnim> anims = BedrockAnimationParser.parse(
				ysmData.mainAnimJson, editor, allBoneElements, AnimationType.POSE);
			editor.animations.addAll(anims);
			Log.info("[YSM Import] Main animations: " + anims.size());
		}
		if (ysmData.armAnimJson != null) {
			List<EditorAnim> anims = BedrockAnimationParser.parse(
				ysmData.armAnimJson, editor, allBoneElements, AnimationType.POSE);
			editor.animations.addAll(anims);
			Log.info("[YSM Import] Arm animations: " + anims.size());
		}
		if (ysmData.extraAnimJson != null) {
			List<EditorAnim> anims = BedrockAnimationParser.parse(
				ysmData.extraAnimJson, editor, allBoneElements, AnimationType.GESTURE);
			editor.animations.addAll(anims);
			Log.info("[YSM Import] Extra animations: " + anims.size());
		}
		// Also parse any additional animation files (tac, carryon, etc.)
		for (Map.Entry<String, String> extraAnimEntry : ysmData.extraAnimFiles.entrySet()) {
			try {
				com.google.gson.JsonObject extraAnimJson =
					com.google.gson.JsonParser.parseString(extraAnimEntry.getValue()).getAsJsonObject();
				List<EditorAnim> anims = BedrockAnimationParser.parse(
					extraAnimJson, editor, allBoneElements, AnimationType.GESTURE);
				editor.animations.addAll(anims);
				Log.info("[YSM Import] Extra anim file '" + extraAnimEntry.getKey() + "': " + anims.size() + " animations");
			} catch (Exception e) {
				Log.warn("[YSM Import] Failed to parse extra animation: " + extraAnimEntry.getKey(), e);
			}
		}
	}

	/**
	 * Setup gesture buttons from ysm.json extra_animation and controller data.
	 */
	private static void setupGestures(YsmModelData ysmData, Editor editor) {
		// Create AnimationEncodingData if not present
		if (editor.animEnc == null) {
			editor.animEnc = new AnimationEncodingData();
		}

		// Merge extra_animation mappings from ysm.json
		for (Map.Entry<String, String> entry : ysmData.extraAnimations.entrySet()) {
			String gestureName = entry.getKey();
			String animName = entry.getValue();

			// Find the corresponding EditorAnim
			EditorAnim targetAnim = editor.animations.stream()
				.filter(a -> a.displayName.equals(animName))
				.findFirst().orElse(null);

			if (targetAnim != null) {
				Log.info("[YSM Import] Gesture '" + gestureName + "' → animation '" + animName + "'");
			}
		}

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
	 * Load textures from YSM data into the editor.
	 * The first/default texture is loaded as the SKIN texture sheet.
	 */
	private static void loadTextures(YsmModelData ysmData, Editor editor) {
		if (ysmData.textures.isEmpty()) return;

		// Determine which texture to use as the main skin
		String textureName = ysmData.defaultTexture;
		if (textureName == null || !ysmData.textures.containsKey(textureName)) {
			// Use the first available texture
			textureName = ysmData.textures.keySet().iterator().next();
		}

		byte[] pngData = ysmData.textures.get(textureName);
		if (pngData == null) return;

		try {
			Image img = Image.loadFrom(new ByteArrayInputStream(pngData));
			if (img == null) return;

			if (img.getWidth() > ETextures.MAX_TEX_SIZE || img.getHeight() > ETextures.MAX_TEX_SIZE) {
				Log.warn("[YSM Import] Texture too large: " + textureName +
					" (" + img.getWidth() + "x" + img.getHeight() + ")");
				return;
			}

			// Set as the SKIN texture
			ETextures skinTex = editor.textures.get(TextureSheetType.SKIN);
			if (skinTex != null) {
				skinTex.setImage(img);
				skinTex.provider.size = new Vec2i(img.getWidth(), img.getHeight());
				skinTex.setEdited(true);
				skinTex.markDirty();
				Log.info("[YSM Import] Loaded texture: " + textureName +
					" (" + img.getWidth() + "x" + img.getHeight() + ")");
			}
		} catch (IOException e) {
			Log.error("[YSM Import] Failed to load texture: " + textureName, e);
		}
	}
}
