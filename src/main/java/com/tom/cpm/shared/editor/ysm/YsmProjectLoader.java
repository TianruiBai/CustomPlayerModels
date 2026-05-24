package com.tom.cpm.shared.editor.ysm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tom.cpm.shared.util.Log;

/**
 * Loads a YSM project file (`.ysmproject` ZIP archive) and extracts all
 * model, animation, texture, and metadata into a {@link YsmModelData} DTO.
 *
 * <p>The .ysmproject format is a plain ZIP containing:
 * <ul>
 *   <li>{@code ysm.json} — metadata and file path references</li>
 *   <li>{@code models/main.json} — Bedrock body geometry</li>
 *   <li>{@code models/arm.json} — Bedrock arm geometry</li>
 *   <li>{@code animations/*.animation.json} — Bedrock animation keyframes</li>
 *   <li>{@code controller/*.animation_controllers.json} — state machine controllers</li>
 *   <li>{@code textures/*.png} — texture images</li>
 * </ul>
 */
public class YsmProjectLoader {

	/**
	 * Load a .ysmproject ZIP file and extract all data.
	 * @param ysmProjectFile the .ysmproject file
	 * @return populated YsmModelData, never null
	 * @throws IOException if the file cannot be read or is malformed
	 */
	public static YsmModelData load(File ysmProjectFile) throws IOException {
		YsmModelData data = new YsmModelData();

		try (ZipFile zip = new ZipFile(ysmProjectFile)) {
			// 1. Parse ysm.json metadata
			ZipEntry ysmEntry = zip.getEntry("ysm.json");
			if (ysmEntry == null) {
				throw new IOException("ysm.json not found in archive");
			}
			String ysmJsonStr = readEntryAsString(zip, ysmEntry);
			JsonObject ysmJson = JsonParser.parseString(ysmJsonStr).getAsJsonObject();
			parseMetadata(ysmJson, data);

			// 2. Read model files using paths from ysm.json
			JsonObject files = ysmJson.getAsJsonObject("files");
			if (files != null) {
				JsonObject playerFiles = files.getAsJsonObject("player");
				if (playerFiles != null) {
					// Models
					JsonObject modelFiles = playerFiles.getAsJsonObject("model");
					if (modelFiles != null) {
						data.mainModelJson = readJsonEntry(zip, getString(modelFiles, "main", "models/main.json"));
						data.armModelJson = readJsonEntry(zip, getString(modelFiles, "arm", "models/arm.json"));

						// P5: Read all additional model keys (beyond main/arm)
						for (Map.Entry<String, JsonElement> modelEntry : modelFiles.entrySet()) {
							String key = modelEntry.getKey();
							if ("main".equals(key) || "arm".equals(key)) continue;
							String path = modelEntry.getValue().getAsString();
							JsonObject extraModel = readJsonEntry(zip, path);
							if (extraModel != null) {
								data.extraModelJsons.put(key, extraModel);
								Log.info("[YSM Import] Extra model '" + key + "' → " + path);
							}
						}
					}

					// Animations
					JsonObject animFiles = playerFiles.getAsJsonObject("animation");
					if (animFiles != null) {
						data.mainAnimJson = readJsonEntry(zip, getString(animFiles, "main", "animations/main.animation.json"));
						data.armAnimJson = readJsonEntry(zip, getString(animFiles, "arm", "animations/arm.animation.json"));
						data.extraAnimJson = readJsonEntry(zip, getString(animFiles, "extra", "animations/extra.animation.json"));

						// Check for additional animation files (tac, carryon, parcool, swem, slashblade, tlm)
						String[] extraAnimKeys = {"tac", "carryon", "parcool", "swem", "slashblade", "tlm"};
						for (String key : extraAnimKeys) {
							String path = getString(animFiles, key, null);
							if (path != null) {
								JsonObject animObj = readJsonEntry(zip, path);
								if (animObj != null) {
									data.extraAnimFiles.put(path, animObj.toString());
								}
							}
						}
					}

					// Animation controllers
					JsonArray controllerFiles = playerFiles.getAsJsonArray("animation_controllers");
					if (controllerFiles != null && controllerFiles.size() > 0) {
						String controllerPath = controllerFiles.get(0).getAsString();
						data.controllerJson = readJsonEntry(zip, controllerPath);
					} else {
						// Fallback: try default controller path
						data.controllerJson = readJsonEntry(zip, "controller/main.animation_controllers.json");
					}

					// Textures — can be plain string paths or objects with "uv" key
					JsonArray textureArray = playerFiles.getAsJsonArray("texture");
					if (textureArray != null) {
						for (JsonElement texElem : textureArray) {
							if (texElem.isJsonPrimitive() && texElem.getAsJsonPrimitive().isString()) {
								// Plain string path: "textures/o.png"
								String texPath = texElem.getAsString();
								byte[] pngData = readEntryBytes(zip, texPath);
								if (pngData != null) {
									String fileName = texPath.substring(texPath.lastIndexOf('/') + 1);
									data.textures.put(fileName, pngData);
								}
							} else if (texElem.isJsonObject()) {
								// Object format: {"uv": "textures/o.png"}
								JsonObject texObj = texElem.getAsJsonObject();
								String uvPath = getString(texObj, "uv", null);
								if (uvPath != null) {
									byte[] pngData = readEntryBytes(zip, uvPath);
									if (pngData != null) {
										String fileName = uvPath.substring(uvPath.lastIndexOf('/') + 1);
										data.textures.put(fileName, pngData);
									}
								}
							}
						}
					}

					// Arrow model (optional)
					JsonObject arrowFiles = files.getAsJsonObject("arrow");
					if (arrowFiles != null) {
						// Arrow model data can be stored but not used by CPM directly
						Log.info("[YSM Import] Arrow model data found but not imported (CPM doesn't use arrow models)");
					}
				}
			} else {
				// No "files" section — try reading default paths directly
				Log.info("[YSM Import] No 'files' section in ysm.json, trying default paths");
				data.mainModelJson = readJsonEntry(zip, "models/main.json");
				data.armModelJson = readJsonEntry(zip, "models/arm.json");
				data.mainAnimJson = readJsonEntry(zip, "animations/main.animation.json");
				data.armAnimJson = readJsonEntry(zip, "animations/arm.animation.json");
				data.extraAnimJson = readJsonEntry(zip, "animations/extra.animation.json");
				data.controllerJson = readJsonEntry(zip, "controller/main.animation_controllers.json");

				// Read all PNG files from textures/ directory, plus sound files
				var entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();
					if (name.endsWith(".png")) {
						byte[] pngData = readEntryBytes(zip, name);
						if (pngData != null) {
							String fileName = name.substring(name.lastIndexOf('/') + 1);
							data.textures.put(fileName, pngData);
						}
					} else if (name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".mp3")) {
						byte[] sndData = readEntryBytes(zip, name);
						if (sndData != null) {
							String fileName = name.substring(name.lastIndexOf('/') + 1);
							data.sounds.put(fileName, sndData);
						}
					}
				}
			}
		}

		captureTextureGrid(data);

		// 3. Scan for sound files in sounds/ directory (for projects with explicit "files" section)
		if (!data.sounds.isEmpty()) {
			Log.info("[YSM Import] Found " + data.sounds.size() + " sound files");
		}

		return data;
	}

	/**
	 * Parse metadata from the ysm.json root object.
	 */
	private static void parseMetadata(JsonObject ysmJson, YsmModelData data) {
		// Model name
		JsonObject metadata = ysmJson.getAsJsonObject("metadata");
		if (metadata != null) {
			data.modelName = getString(metadata, "name", "Unnamed YSM Model");
			data.description = getString(metadata, "tips", "");

			// Authors
			JsonArray authors = metadata.getAsJsonArray("authors");
			if (authors != null) {
				for (JsonElement authorElem : authors) {
					if (authorElem.isJsonObject()) {
						JsonObject author = authorElem.getAsJsonObject();
						String name = getString(author, "name", null);
						if (name != null) data.authors.add(name);
					}
				}
			}
		}

		// Properties
		JsonObject properties = ysmJson.getAsJsonObject("properties");
		if (properties != null) {
			data.heightScale = getFloat(properties, "height_scale", 1.0f);
			data.widthScale = getFloat(properties, "width_scale", 1.0f);
			data.preserveYsmScale =
				getBoolean(properties, "cpm_preserve_scale", false) ||
				getBoolean(properties, "preserve_scale", false) ||
				getBoolean(properties, "import_scale_to_cpm", false);
			data.defaultTexture = getString(properties, "default_texture", null);

			// Extra animation mappings (gesture name → animation name)
			JsonObject extraAnim = properties.getAsJsonObject("extra_animation");
			if (extraAnim != null) {
				for (Map.Entry<String, JsonElement> entry : extraAnim.entrySet()) {
					String gestureName = entry.getKey();
					// Skip comment keys (starting with #)
					if (gestureName.startsWith("#")) continue;

					String animName = entry.getValue().getAsString();
					if (animName != null && !animName.isEmpty() && !animName.startsWith("#")) {
						data.extraAnimations.put(gestureName, animName);
					}
				}
			}

			// Extra animation classify (nested gesture groups)
			JsonArray classifyArray = properties.getAsJsonArray("extra_animation_classify");
			if (classifyArray != null) {
				for (JsonElement classifyElem : classifyArray) {
					if (classifyElem.isJsonObject()) {
						JsonObject classifyObj = classifyElem.getAsJsonObject();
						String classifyId = getString(classifyObj, "id", null);
						JsonObject innerExtraAnim = classifyObj.getAsJsonObject("extra_animation");
						if (innerExtraAnim != null) {
							for (Map.Entry<String, JsonElement> entry : innerExtraAnim.entrySet()) {
								String gestureName = entry.getKey();
								if (gestureName.startsWith("#")) continue;
								String animName = entry.getValue().getAsString();
								if (animName != null && !animName.isEmpty() && !animName.startsWith("#")) {
									data.extraAnimations.put(gestureName, animName);
									if (classifyId != null && !classifyId.isEmpty()) {
										data.gestureDescriptions.put(gestureName, classifyId);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private static void captureTextureGrid(YsmModelData data) {
		readTextureGrid(data.mainModelJson, data);
		readTextureGrid(data.armModelJson, data);
	}

	private static void readTextureGrid(JsonObject modelJson, YsmModelData data) {
		if (modelJson == null) return;
		JsonArray geometries = modelJson.getAsJsonArray("minecraft:geometry");
		if (geometries == null || geometries.size() == 0) return;
		JsonObject geometry = geometries.get(0).getAsJsonObject();
		JsonObject description = geometry.getAsJsonObject("description");
		if (description == null) return;
		data.textureWidth = Math.max(data.textureWidth, getInt(description, "texture_width", data.textureWidth));
		data.textureHeight = Math.max(data.textureHeight, getInt(description, "texture_height", data.textureHeight));
	}

	// ---- ZIP reading helpers ----

	/**
	 * Read a ZIP entry and parse it as JSON, returning null if the entry doesn't exist.
	 */
	private static JsonObject readJsonEntry(ZipFile zip, String path) {
		if (path == null) return null;
		ZipEntry entry = zip.getEntry(path);
		if (entry == null) {
			Log.info("[YSM Import] Entry not found: " + path);
			return null;
		}
		try {
			String jsonStr = readEntryAsString(zip, entry);
			if (jsonStr == null || jsonStr.trim().isEmpty()) return null;
			return JsonParser.parseString(jsonStr).getAsJsonObject();
		} catch (Exception e) {
			Log.warn("[YSM Import] Failed to parse JSON entry: " + path, e);
			return null;
		}
	}

	private static String readEntryAsString(ZipFile zip, ZipEntry entry) throws IOException {
		try (InputStream is = zip.getInputStream(entry);
		     Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
			StringBuilder sb = new StringBuilder();
			char[] buf = new char[8192];
			int n;
			while ((n = reader.read(buf)) != -1) {
				sb.append(buf, 0, n);
			}
			return sb.toString();
		}
	}

	/**
	 * Read a ZIP entry as raw bytes, returning null if the entry doesn't exist.
	 */
	static byte[] readEntryBytes(ZipFile zip, String path) {
		if (path == null) return null;
		ZipEntry entry = zip.getEntry(path);
		if (entry == null) return null;
		try {
			return readEntryBytes(zip, entry);
		} catch (IOException e) {
			Log.warn("[YSM Import] Failed to read entry: " + path, e);
			return null;
		}
	}

	private static byte[] readEntryBytes(ZipFile zip, ZipEntry entry) throws IOException {
		try (InputStream is = zip.getInputStream(entry);
		     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) != -1) {
				baos.write(buf, 0, n);
			}
			return baos.toByteArray();
		}
	}

	// ---- JSON helpers ----

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

	private static int getInt(JsonObject obj, String key, int def) {
		JsonElement e = obj.get(key);
		return e != null && !e.isJsonNull() ? e.getAsInt() : def;
	}
}
