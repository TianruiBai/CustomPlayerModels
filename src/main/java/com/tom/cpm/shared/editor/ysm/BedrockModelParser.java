package com.tom.cpm.shared.editor.ysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Direction;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.render.PerFaceUV;
import com.tom.cpm.shared.model.render.PerFaceUV.Face;
import com.tom.cpm.shared.util.Log;

/**
 * Parses Minecraft Bedrock Edition geometry JSON into CPM-compatible structures.
 *
 * <p>Bedrock model format (as used by YSM):
 * <pre>{@code
 * {
 *   "minecraft:geometry": [{
 *     "description": { "identifier": "...", "texture_width": 1024, "texture_height": 1024 },
 *     "bones": [
 *       { "name": "Head", "parent": "Body", "pivot": [0, 24, 0],
 *         "cubes": [{ "origin": [-4, 20, -4], "size": [8, 8, 8],
 *           "uv": { "north": {"uv": [0, 0], "uv_size": [8, 8]}, ... }
 *         }]
 *       }
 *     ]
 *   }]
 * }
 * }</pre>
 */
public class BedrockModelParser {

	/** Parsed representation of a Bedrock bone */
	public static class BedrockBone {
		public String name;
		public String parent;
		public Vec3f pivot = new Vec3f();
		public Vec3f rotation = new Vec3f();
		public List<BedrockCube> cubes = new ArrayList<>();
		public boolean neverRender;
		public boolean mirror;
	}

	/** Parsed representation of a Bedrock cube within a bone */
	public static class BedrockCube {
		public Vec3f origin = new Vec3f();
		public Vec3f size = new Vec3f(1, 1, 1);
		public float inflate;
		/** Per-face UV mappings: face name (north/south/east/west/up/down) → UV data */
		public Map<String, BedrockFaceUV> faces = new HashMap<>();
		public boolean mirror;
		/** Cube-level pivot (rotation anchor). null if not present in source JSON. */
		public Vec3f pivot;
		/** Cube-level rotation in degrees [pitch, yaw, roll]. null if not present. */
		public Vec3f rotation;
	}

	/** UV data for a single face of a Bedrock cube */
	public static class BedrockFaceUV {
		public int u, v;
		public int uvWidth, uvHeight;
	}

	// ---- Bone name → CPM root part heuristic mapping ----

	private static final Map<String, PlayerModelParts> BONE_NAME_TO_PART = new HashMap<>();
	static {
		// Head group
		BONE_NAME_TO_PART.put("head", PlayerModelParts.HEAD);
		BONE_NAME_TO_PART.put("mhead", PlayerModelParts.HEAD);
		BONE_NAME_TO_PART.put("allhead", PlayerModelParts.HEAD);
		BONE_NAME_TO_PART.put("hat", PlayerModelParts.HEAD);
		// Body group
		BONE_NAME_TO_PART.put("body", PlayerModelParts.BODY);
		BONE_NAME_TO_PART.put("mallbody", PlayerModelParts.BODY);
		BONE_NAME_TO_PART.put("allbody", PlayerModelParts.BODY);
		BONE_NAME_TO_PART.put("upperbody", PlayerModelParts.BODY);
		BONE_NAME_TO_PART.put("mupperbody", PlayerModelParts.BODY);
		BONE_NAME_TO_PART.put("upbody", PlayerModelParts.BODY);
		BONE_NAME_TO_PART.put("chest", PlayerModelParts.BODY);
		BONE_NAME_TO_PART.put("waist", PlayerModelParts.BODY);
		// Left arm
		BONE_NAME_TO_PART.put("leftarm", PlayerModelParts.LEFT_ARM);
		BONE_NAME_TO_PART.put("larm", PlayerModelParts.LEFT_ARM);
		// Right arm
		BONE_NAME_TO_PART.put("rightarm", PlayerModelParts.RIGHT_ARM);
		BONE_NAME_TO_PART.put("rarm", PlayerModelParts.RIGHT_ARM);
		// Left leg
		BONE_NAME_TO_PART.put("leftleg", PlayerModelParts.LEFT_LEG);
		BONE_NAME_TO_PART.put("lleg", PlayerModelParts.LEFT_LEG);
		// Right leg
		BONE_NAME_TO_PART.put("rightleg", PlayerModelParts.RIGHT_LEG);
		BONE_NAME_TO_PART.put("rleg", PlayerModelParts.RIGHT_LEG);
	}

	/**
	 * Parse a Bedrock geometry JSON object into a list of bones.
	 * @param modelJson the parsed JSON for a model file (e.g., main.json or arm.json)
	 * @return flat list of all bones in the geometry
	 */
	public static List<BedrockBone> parse(JsonObject modelJson) {
		List<BedrockBone> bones = new ArrayList<>();
		if (modelJson == null) return bones;

		JsonArray geometries = modelJson.getAsJsonArray("minecraft:geometry");
		if (geometries == null || geometries.size() == 0) return bones;

		JsonObject geometry = geometries.get(0).getAsJsonObject();
		JsonArray bonesArr = geometry.getAsJsonArray("bones");
		if (bonesArr == null) return bones;

		for (JsonElement elem : bonesArr) {
			JsonObject boneObj = elem.getAsJsonObject();
			BedrockBone bone = new BedrockBone();

			bone.name = getString(boneObj, "name", "");
			bone.parent = getString(boneObj, "parent", null);
			bone.pivot = readVec3f(boneObj, "pivot");
			bone.rotation = readVec3f(boneObj, "rotation");
			bone.neverRender = getBoolean(boneObj, "never_render", false);
			bone.mirror = getBoolean(boneObj, "mirror", false);

			// Parse cubes
			JsonArray cubesArr = boneObj.getAsJsonArray("cubes");
			if (cubesArr != null) {
				for (JsonElement cubeElem : cubesArr) {
					JsonObject cubeObj = cubeElem.getAsJsonObject();
					BedrockCube cube = new BedrockCube();

					cube.origin = readVec3f(cubeObj, "origin");
					cube.size = readVec3f(cubeObj, "size", new Vec3f(1, 1, 1));
					cube.inflate = getFloat(cubeObj, "inflate", 0f);
					cube.mirror = getBoolean(cubeObj, "mirror", false);

					// Parse cube-level pivot and rotation (for cubes with their own transform)
					if (cubeObj.has("pivot")) cube.pivot = readVec3f(cubeObj, "pivot");
					if (cubeObj.has("rotation")) cube.rotation = readVec3f(cubeObj, "rotation");

					// Parse UV — can be:
					// 1. JsonObject: per-face UV {"north": {"uv": [u,v], "uv_size": [w,h]}, ...}
					// 2. JsonArray: simple UV [u, v] applied to all faces
					JsonElement uvElem = cubeObj.get("uv");
					if (uvElem != null && uvElem.isJsonObject()) {
						JsonObject uvObj = uvElem.getAsJsonObject();
						for (String faceName : uvObj.keySet()) {
							JsonObject faceUV = uvObj.getAsJsonObject(faceName);
							if (faceUV != null) {
								BedrockFaceUV fuv = new BedrockFaceUV();
								JsonElement innerUvElem = faceUV.get("uv");
								if (innerUvElem != null && innerUvElem.isJsonArray()) {
									JsonArray uvArr = innerUvElem.getAsJsonArray();
									fuv.u = uvArr.get(0).getAsInt();
									fuv.v = uvArr.get(1).getAsInt();
								}
								JsonElement uvSizeElem = faceUV.get("uv_size");
								if (uvSizeElem != null && uvSizeElem.isJsonArray()) {
									JsonArray uvSizeArr = uvSizeElem.getAsJsonArray();
									fuv.uvWidth = uvSizeArr.get(0).getAsInt();
									fuv.uvHeight = uvSizeArr.get(1).getAsInt();
								} else {
									fuv.uvWidth = (int) cube.size.x;
									fuv.uvHeight = (int) cube.size.y;
								}
								cube.faces.put(faceName.toLowerCase(), fuv);
							}
						}
					} else if (uvElem != null && uvElem.isJsonArray()) {
						// Simple UV: [u, v] — apply to all faces as a single UV offset
						JsonArray uvArr = uvElem.getAsJsonArray();
						BedrockFaceUV fuv = new BedrockFaceUV();
						fuv.u = uvArr.get(0).getAsInt();
						fuv.v = uvArr.get(1).getAsInt();
						fuv.uvWidth = (int) cube.size.x;
						fuv.uvHeight = (int) cube.size.y;
						cube.faces.put("north", fuv);
					}
					bone.cubes.add(cube);
				}
			}
			bones.add(bone);
		}
		return bones;
	}

	/**
	 * Map a bone name to the closest CPM root part using heuristic matching.
	 * Returns {@code null} if no match is found (bone should be attached to another root).
	 */
	public static PlayerModelParts mapBoneToPart(String boneName) {
		if (boneName == null) return null;
		return BONE_NAME_TO_PART.get(boneName.toLowerCase());
	}

	/**
	 * Topology-based fallback mapping. Walks the ancestor chain of a bone;
	 * if any ancestor maps to a known CPM part via {@link #mapBoneToPart},
	 * inherit that mapping. For unmatched top-level bones, uses pivot position
	 * heuristics (high Y → HEAD, low Y with bilateral X → LEG, etc.).
	 *
	 * @return the best-guess PlayerModelParts, or null if completely indeterminate
	 */
	public static PlayerModelParts mapBoneByTopology(List<BedrockBone> allBones, BedrockBone bone) {
		// Walk ancestor chain
		String ancestor = bone.parent;
		while (ancestor != null) {
			PlayerModelParts part = mapBoneToPart(ancestor);
			if (part != null) return part;
			final String a = ancestor;
			ancestor = allBones.stream().filter(b -> b.name.equals(a)).findFirst().map(b -> b.parent).orElse(null);
		}
		// Position-based heuristics for unmatched top-level bones
		float y = bone.pivot.y;
		float x = Math.abs(bone.pivot.x);
		if (y > 22) return PlayerModelParts.HEAD;
		if (y > 10 && y <= 22 && x < 4) return PlayerModelParts.BODY;
		if (x > 4 && y > 8 && y <= 22) {
			return bone.pivot.x < 0 ? PlayerModelParts.LEFT_ARM : PlayerModelParts.RIGHT_ARM;
		}
		if (y <= 8) {
			return bone.pivot.x < 0 ? PlayerModelParts.LEFT_LEG : PlayerModelParts.RIGHT_LEG;
		}
		return null;
	}

	/**
	 * Find the root-level bone that best matches the given CPM root part.
	 * This is the topmost bone in the hierarchy that maps to this part.
	 */
	public static String findRootBoneForPart(List<BedrockBone> bones, PlayerModelParts part) {
		String best = null;
		int bestDepth = Integer.MAX_VALUE;

		for (BedrockBone bone : bones) {
			if (mapBoneToPart(bone.name) == part) {
				int depth = getBoneDepth(bones, bone);
				if (depth < bestDepth) {
					bestDepth = depth;
					best = bone.name;
				}
			}
		}
		return best;
	}

	private static int getBoneDepth(List<BedrockBone> bones, BedrockBone bone) {
		int depth = 0;
		String parent = bone.parent;
		while (parent != null) {
			depth++;
			final String p = parent;
			parent = bones.stream().filter(b -> b.name.equals(p)).findFirst().map(b -> b.parent).orElse(null);
		}
		return depth;
	}

	/**
	 * Convert Bedrock per-face UV to CPM's {@link PerFaceUV} structure.
	 * Returns {@code null} if all faces share the same UV coordinates (simple case).
	 * <p>
	 * Bedrock allows negative uv_size values to indicate texture flipping.
	 * CPM stores UVs as start/end coords where ex &gt; sx and ey &gt; sy.
	 */
	public static PerFaceUV convertPerFaceUV(BedrockCube cube) {
		if (cube.faces.isEmpty()) return null;

		// Check if all faces have the same UV — no need for PerFaceUV then
		BedrockFaceUV first = cube.faces.values().iterator().next();
		boolean allSame = cube.faces.values().stream().allMatch(f ->
			f.u == first.u && f.v == first.v && f.uvWidth == first.uvWidth && f.uvHeight == first.uvHeight
		);
		if (allSame) return null;

		PerFaceUV pfUV = new PerFaceUV();
		for (Map.Entry<String, BedrockFaceUV> entry : cube.faces.entrySet()) {
			Direction dir = bedrockFaceToDirection(entry.getKey());
			if (dir == null) continue;
			BedrockFaceUV fuv = entry.getValue();
			Face face = new Face();

			// Skip faces with zero-size UV region (degenerate faces)
			if (fuv.uvWidth == 0 || fuv.uvHeight == 0) continue;

			// Compute actual UV extent using SIGNED uv_size.
			// Negative uv_size means the texture is flipped in that axis.
			int uEnd = fuv.u + fuv.uvWidth;
			int vEnd = fuv.v + fuv.uvHeight;

			face.sx = Math.min(fuv.u, uEnd);
			face.ex = Math.max(fuv.u, uEnd);
			face.sy = Math.min(fuv.v, vEnd);
			face.ey = Math.max(fuv.v, vEnd);

			face.autoUV = false; // We provide explicit UVs, not auto-generated
			pfUV.faces.put(dir, face);
		}
		return pfUV;
	}

	/**
	 * Get the primary UV coordinates for a cube (from the first available face).
	 * Returns [u, v] as a Vec2i, or null if no faces have UV data.
	 */
	public static Vec2i getPrimaryUV(BedrockCube cube) {
		if (cube.faces.isEmpty()) return null;
		BedrockFaceUV first = cube.faces.values().iterator().next();
		return new Vec2i(first.u, first.v);
	}

	/** Map Bedrock face name to CPM Direction.
	 *  Verified against CPM-reference config: east↔west are swapped
	 *  (Bedrock "east" UV data appears on CPM Direction.WEST and vice versa). */
	static Direction bedrockFaceToDirection(String faceName) {
		switch (faceName.toLowerCase()) {
			case "north": return Direction.NORTH;
			case "south": return Direction.SOUTH;
			case "east":  return Direction.WEST;
			case "west":  return Direction.EAST;
			case "up":    return Direction.UP;
			case "down":  return Direction.DOWN;
			default:      return null;
		}
	}

	// ---- JSON helpers ----

	private static Vec3f readVec3f(JsonObject obj, String key) {
		return readVec3f(obj, key, new Vec3f());
	}

	private static Vec3f readVec3f(JsonObject obj, String key, Vec3f def) {
		JsonArray arr = obj.getAsJsonArray(key);
		if (arr == null || arr.size() < 3) return def;
		return new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
	}

	private static String getString(JsonObject obj, String key, String def) {
		JsonElement e = obj.get(key);
		return e != null && !e.isJsonNull() ? e.getAsString() : def;
	}

	private static float getFloat(JsonObject obj, String key, float def) {
		JsonElement e = obj.get(key);
		return e != null && !e.isJsonNull() ? e.getAsFloat() : def;
	}

	private static boolean getBoolean(JsonObject obj, String key, boolean def) {
		JsonElement e = obj.get(key);
		return e != null && !e.isJsonNull() ? e.getAsBoolean() : def;
	}
}
