package com.tom.cpm.shared.editor.ysm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.TextureSlot;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.editor.anim.AnimationEncodingData;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockBone;
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockCube;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.PerFaceUV;
import com.tom.cpm.shared.util.Log;

/**
 * Converts parsed YSM (Bedrock-format) model data into CPM editor state.
 *
 * <p><b>Conversion pipeline (matching cpm_plugin.js parity):</b>
 * <ol>
 *   <li>Parse Bedrock JSON using {@link BedrockModelParser}</li>
 *   <li>Build bone lookup and parent→children maps</li>
 *   <li>Place all content under the CPM HEAD root part</li>
 *   <li>Set the HEAD root pos using the first YSM root bone's pivot:
 *       {@code pos = [pivot.x, 24 - pivot.y, pivot.z]}</li>
 *   <li>Recursively build each YSM root bone's subtree:
 *       <ul>
 *         <li>Bone pos = {@code [dx, -dy, dz]} where d = childPivot - parentPivot</li>
 *         <li>Bone rotation = 1:1 copy (local rotation, coord transform via position)</li>
 *         <li>Cube offset = {@code [pivot.x-(origin.x+size.x), pivot.y-(origin.y+size.y), origin.z-pivot.z]}</li>
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

		Map<String, ModelElement> built = convertModel(mainBones, boneIndex, editor);
		convertAnimations(ysmData, editor, built, boneIndex, mainBones);
		setupGestures(ysmData, editor);
		loadTextures(ysmData, editor);
		stabilizeSkinType(editor);
		applyModelScale(ysmData, editor);
		setMetadata(ysmData, editor);

		Log.info("[YSM Import] Done — " + built.size() + " elements, " +
			editor.animations.size() + " animations");
	}

	// ========================================================================
	// Model Conversion
	// ========================================================================

	private static Map<String, ModelElement> convertModel(List<BedrockBone> allBones,
			Map<String, BedrockBone> boneIndex, Editor editor) {
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

		// Find the CPM HEAD root — matching BlockBench plugin behaviour
		ModelElement rootPart = findRootElement(editor, PlayerModelParts.HEAD);
		if (rootPart == null) {
			rootPart = findRootElement(editor, PlayerModelParts.BODY);
		}
		if (rootPart == null) {
			Log.error("[YSM Import] No HEAD or BODY root found, aborting");
			return new HashMap<>();
		}
		// Root part stays hidden (show=false) so vanilla geometry doesn't render.
		// Custom children render independently of the root part's hidden flag.

		// Find YSM root bones (no parent, or parent not in bone list)
		List<BedrockBone> ysmRoots = new ArrayList<>();
		for (BedrockBone bone : allBones) {
			if (bone.parent == null || !boneIndex.containsKey(bone.parent)) {
				ysmRoots.add(bone);
			}
		}

		// Reference pivot — the anchor for coordinate conversion
		Vec3f refPivot = ysmRoots.isEmpty() ? Vec3f.ZERO : new Vec3f(ysmRoots.get(0).pivot);
		Log.info("[YSM Import] Ref pivot: " + refPivot + ", YSM roots: " + ysmRoots.size());

		// Set root part position. CPM uses Y-down internally (like Blockbench).
		// 24 is the standard Minecraft head-height reference.
		rootPart.pos = new Vec3f(refPivot.x, 24f - refPivot.y, refPivot.z);

		// Recursively build the entire tree
		Map<String, ModelElement> allElements = new HashMap<>();
		for (BedrockBone ysmRoot : ysmRoots) {
			buildBoneTree(ysmRoot, rootPart, refPivot, boneIndex, childrenMap,
				allElements, editor);
		}

		Log.info("[YSM Import] Built " + allElements.size() + " elements under " +
			rootPart.typeData);
		return allElements;
	}

	/**
	 * Recursively create CPM elements for a bone and all its descendants.
	 * Bones with cubes get cube child elements. Pure container bones are kept
	 * but hidden if they have no geometry.
	 */
	private static void buildBoneTree(BedrockBone bone, ModelElement cpmParent,
			Vec3f refPivot, Map<String, BedrockBone> boneIndex,
			Map<String, List<BedrockBone>> childrenMap,
			Map<String, ModelElement> allElements, Editor editor) {

		ModelElement elem = new ModelElement(editor);
		elem.name = bone.name;
		elem.parent = cpmParent;
		cpmParent.children.add(elem);
		allElements.put(bone.name, elem);

		// Bone elements are containers, not renderable cubes.
		// ElementType.NORMAL defaults size to [1,1,1] — override to [0,0,0].
		elem.size = new Vec3f(0, 0, 0);
		elem.texture = true;
		elem.textureSize = 1;

		// --- Position: [dx, -dy, dz] ---
		if (bone.parent != null) {
			BedrockBone parentBone = boneIndex.get(bone.parent);
			Vec3f ref = parentBone != null ? parentBone.pivot : refPivot;
			Vec3f d = bone.pivot.sub(ref);
			elem.pos = new Vec3f(d.x, -d.y, d.z);
		} else {
			Vec3f d = bone.pivot.sub(refPivot);
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
				buildBoneTree(child, elem, refPivot, boneIndex, childrenMap,
					allElements, editor);
			}
		}
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
			Map<String, BedrockBone> boneIndex,
			List<BedrockBone> allBones) {

		Map<String, Vec3f> worldPositions = new LinkedHashMap<>();
		for (BedrockBone b : allBones) {
			worldPositions.put(b.name, new Vec3f(0, 0, 0));
		}

		int total = 0;
		total += parseAnim(ysmData.mainAnimJson, editor, builtElements,
			AnimationType.POSE, worldPositions, boneIndex, "main");
		total += parseAnim(ysmData.armAnimJson, editor, builtElements,
			AnimationType.POSE, worldPositions, boneIndex, "arm");
		total += parseAnim(ysmData.extraAnimJson, editor, builtElements,
			AnimationType.GESTURE, worldPositions, boneIndex, "extra");

		for (Map.Entry<String, String> e : ysmData.extraAnimFiles.entrySet()) {
			try {
				com.google.gson.JsonObject json = com.google.gson.JsonParser
					.parseString(e.getValue()).getAsJsonObject();
				// Extract source name from path like "animations/tac.animation.json" → "tac"
				String path = e.getKey();
				String fileName = path.substring(path.lastIndexOf('/') + 1);
				String srcName = fileName.replace(".animation.json", "").replace(".json", "");
				total += parseAnim(json, editor, builtElements,
					AnimationType.GESTURE, worldPositions, boneIndex, srcName);
			} catch (Exception ex) {
				Log.warn("[YSM Import] Failed extra anim: " + e.getKey(), ex);
			}
		}
		Log.info("[YSM Import] Total animations: " + total);
	}

	private static int parseAnim(com.google.gson.JsonObject json, Editor editor,
			Map<String, ModelElement> builtElements, AnimationType type,
			Map<String, Vec3f> worldPositions, Map<String, BedrockBone> boneIndex,
			String source) {
		if (json == null) return 0;
		try {
			List<EditorAnim> anims = BedrockAnimationParser.parse(json, editor,
				builtElements, type, source, worldPositions, boneIndex);
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
		for (Map.Entry<String, String> entry : ysmData.extraAnimations.entrySet()) {
			EditorAnim target = editor.animations.stream()
				.filter(a -> a.displayName != null && a.displayName.equals(entry.getValue()))
				.findFirst().orElse(null);
			if (target != null && target.type != AnimationType.GESTURE
				&& target.type != AnimationType.CUSTOM_POSE) {
				target.type = AnimationType.GESTURE;
			}
		}
		if (ysmData.controllerJson != null) {
			Map<String, String> ctrlGestures = BedrockControllerParser
				.extractGestureMappings(ysmData.controllerJson);
			ctrlGestures.forEach((k, v) -> ysmData.extraAnimations.putIfAbsent(k, v));
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
