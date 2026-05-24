package com.tom.cpm.shared.editor.ysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * Intermediate data transfer object holding all parsed data from a .ysmproject ZIP.
 * This is populated by {@link YsmProjectLoader} and consumed by {@link YsmToCpmConverter}.
 */
public class YsmModelData {
	public String modelName;
	public String description;
	public List<String> authors = new ArrayList<>();

	// Raw parsed model JSONs (Bedrock geometry format)
	public JsonObject mainModelJson;
	public JsonObject armModelJson;

	// Raw parsed animation JSONs (Bedrock animation format)
	public JsonObject mainAnimJson;
	public JsonObject armAnimJson;
	public JsonObject extraAnimJson;
	public JsonObject controllerJson;

	// Texture data: filename (e.g. "default.png") → raw PNG bytes
	public Map<String, byte[]> textures = new LinkedHashMap<>();

	// Model properties from ysm.json
	public float heightScale = 1.0f;
	public float widthScale = 1.0f;
	/** Source Bedrock texture grid declared by model description (before CPM UV normalization). */
	public int textureWidth = 64;
	public int textureHeight = 64;
	/** CPM UV scaling factor derived from the source model's texture regime. */
	public int uvScale = 1;
	/**
	 * Optional import flag from ysm.json properties.
	 * When true, map YSM height/width scale into CPM render_scale.
	 * Default false for BlockBench/plugin geometry parity.
	 */
	public boolean preserveYsmScale;
	public String defaultTexture;
	/** Gesture name → animation name mappings from extra_animation section */
	public Map<String, String> extraAnimations = new HashMap<>();

	/** Animation file path → raw JSON string (for animations not in standard locations) */
	public Map<String, String> extraAnimFiles = new HashMap<>();

	/** Animation name → display description from extra_animation_classify */
	public Map<String, String> gestureDescriptions = new HashMap<>();

	/** P5: Extra model files beyond main/arm, keyed by model key name (e.g. "arrow", "extra_01"). */
	public Map<String, JsonObject> extraModelJsons = new LinkedHashMap<>();

	/** Sound files from the YSM archive: filename (e.g. "sound.ogg") → raw bytes. */
	public Map<String, byte[]> sounds = new HashMap<>();
}
