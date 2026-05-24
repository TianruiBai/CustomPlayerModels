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
import com.tom.cpm.shared.animation.CustomPose;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
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
					buildBoneHierarchy(rootBone, mainBones, rootElem, editor, allBoneElements);
				}
			}
		}

		Log.info("[YSM Import] Created " + allBoneElements.size() + " bone elements");

		// 4. Process arm bones under their respective arm root parts
		processArmBones(armBones, mainBones, editor, allBoneElements);

		// 5. Convert animations
		if (ysmData.mainAnimJson != null) {
			List<EditorAnim> mainAnims = BedrockAnimationParser.parse(
				ysmData.mainAnimJson, editor, allBoneElements, AnimationType.POSE);
			editor.animations.addAll(mainAnims);
			Log.info("[YSM Import] Main animations: " + mainAnims.size());
		}
		if (ysmData.armAnimJson != null) {
			List<EditorAnim> armAnims = BedrockAnimationParser.parse(
				ysmData.armAnimJson, editor, allBoneElements, AnimationType.POSE);
			editor.animations.addAll(armAnims);
			Log.info("[YSM Import] Arm animations: " + armAnims.size());
		}
		if (ysmData.extraAnimJson != null) {
			List<EditorAnim> extraAnims = BedrockAnimationParser.parse(
				ysmData.extraAnimJson, editor, allBoneElements, AnimationType.GESTURE);
			editor.animations.addAll(extraAnims);
			Log.info("[YSM Import] Extra animations: " + extraAnims.size());
		}

		// 6. Load textures
		loadTextures(ysmData, editor);

		// 7. Set model metadata
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
	 */
	private static void buildBoneHierarchy(BedrockBone bone, List<BedrockBone> allBones,
	                                       ModelElement parent, Editor editor,
	                                       Map<String, ModelElement> allBoneElements) {
		// Create a ModelElement for this bone
		ModelElement elem = new ModelElement(editor);
		elem.name = bone.name;
		elem.parent = parent;
		parent.children.add(elem);
		allBoneElements.put(bone.name, elem);

		// Position = pivot (relative to parent bone's pivot)
		elem.pos = new Vec3f(bone.pivot);

		// Rotation from bone
		if (bone.rotation.x != 0 || bone.rotation.y != 0 || bone.rotation.z != 0) {
			elem.rotation = new Vec3f(bone.rotation);
		}

		// Hide the element if it has no cubes and is just a structural bone
		if (bone.cubes.isEmpty() && !bone.neverRender) {
			// Structural bone — just a container, keep visible for hierarchy
		}

		// Create cube elements for each Bedrock cube in this bone
		for (BedrockCube cube : bone.cubes) {
			ModelElement cubeElem = new ModelElement(editor);
			cubeElem.name = bone.name + "_cube";
			cubeElem.parent = elem;
			elem.children.add(cubeElem);
			allBoneElements.put(bone.name + "_cube_" + elem.children.size(), cubeElem);

			// Cube geometry
			cubeElem.size = new Vec3f(cube.size);
			cubeElem.offset = cube.origin.sub(bone.pivot);

			// UV mapping
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

			// Inflate → meshScale (approximate: inflate of 0.1 means 10% bigger in all directions)
			if (cube.inflate != 0) {
				float s = 1.0f + cube.inflate / Math.max(cube.size.x, Math.max(cube.size.y, cube.size.z));
				cubeElem.meshScale = new Vec3f(s, s, s);
			}

			cubeElem.mirror = cube.mirror;
		}

		// Process child bones
		for (BedrockBone child : allBones) {
			if (bone.name.equals(child.parent)) {
				buildBoneHierarchy(child, allBones, elem, editor, allBoneElements);
			}
		}
	}

	/**
	 * Map arm bones to the LEFT_ARM and RIGHT_ARM root parts.
	 * Arm bones in YSM have their own model file and need special handling.
	 */
	private static void processArmBones(List<BedrockBone> armBones, List<BedrockBone> mainBones,
	                                    Editor editor, Map<String, ModelElement> allBoneElements) {
		if (armBones.isEmpty()) return;

		// Find the LEFT_ARM and RIGHT_ARM root elements in the editor
		ModelElement leftArmRoot = findRootElement(editor, PlayerModelParts.LEFT_ARM);
		ModelElement rightArmRoot = findRootElement(editor, PlayerModelParts.RIGHT_ARM);

		// Try to find corresponding arm bones by name
		for (BedrockBone bone : armBones) {
			String lowerName = bone.name.toLowerCase();
			ModelElement targetRoot = null;

			if (lowerName.contains("left")) {
				targetRoot = leftArmRoot;
			} else if (lowerName.contains("right")) {
				targetRoot = rightArmRoot;
			}

			if (targetRoot != null && bone.parent == null) {
				// This is a root-level arm bone
				buildBoneHierarchy(bone, armBones, targetRoot, editor, allBoneElements);
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
