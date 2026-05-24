package com.tom.cpm.shared.editor.ysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.IPose;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.AnimFrame.FrameData;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.util.Log;

/**
 * Parses Minecraft Bedrock Edition animation JSON into CPM {@link EditorAnim} structures.
 *
 * <p>Bedrock animation format (as used by YSM):
 * <pre>{@code
 * {
 *   "animations": {
 *     "idle": {
 *       "loop": true,
 *       "animation_length": 3.0,
 *       "bones": {
 *         "Head": {
 *           "rotation": {
 *             "0.0": { "post": [0, 0, 0], "lerp_mode": "catmullrom" },
 *             "0.5": { "post": [5, 0, 0], "lerp_mode": "catmullrom" }
 *           },
 *           "position": { ... }
 *         }
 *       }
 *     }
 *   }
 * }
 * }</pre>
 */
public class BedrockAnimationParser {

	/** Maximum number of frames to generate per animation (safety limit) */
	private static final int MAX_FRAMES = 240;

	/**
	 * Parse all animations from a Bedrock animation JSON and create CPM EditorAnims.
	 *
	 * @param animJson          the parsed animation JSON object
	 * @param editor            the CPM editor instance
	 * @param boneNameToElement mapping from Bedrock bone name → CPM ModelElement
	 * @param defaultType       the AnimationType to use when not specified
	 * @return list of created EditorAnims
	 */
	public static List<EditorAnim> parse(JsonObject animJson, Editor editor,
	                                     Map<String, ModelElement> boneNameToElement,
	                                     AnimationType defaultType) {
		List<EditorAnim> results = new ArrayList<>();
		if (animJson == null) return results;

		JsonObject animations = animJson.getAsJsonObject("animations");
		if (animations == null) return results;

		for (String animName : animations.keySet()) {
			JsonObject animData = animations.getAsJsonObject(animName);
			if (animData == null) continue;

			EditorAnim anim = convertAnimation(animName, animData, editor, boneNameToElement, defaultType);
			if (anim != null) {
				results.add(anim);
			}
		}
		return results;
	}

	/**
	 * Convert a single Bedrock animation to a CPM EditorAnim.
	 */
	private static EditorAnim convertAnimation(String animName, JsonObject animData, Editor editor,
	                                           Map<String, ModelElement> boneNameToElement,
	                                           AnimationType defaultType) {
		// Determine animation type
		AnimationType type = defaultType;
		String filenamePrefix = "ysm_";

		// Try to map animation name to a VanillaPose
		IPose pose = null;
		for (VanillaPose vp : VanillaPose.VALUES) {
			if (animName.equalsIgnoreCase(vp.name())) {
				pose = vp;
				type = AnimationType.POSE;
				filenamePrefix = "v_" + vp.name().toLowerCase() + "_";
				break;
			}
		}

		// Animation-level properties
		boolean loop = animData.has("loop") && animData.get("loop").getAsBoolean();
		float animLength = animData.has("animation_length") ? animData.get("animation_length").getAsFloat() : 1.0f;

		String filename = filenamePrefix + sanitizeFilename(animName) + ".json";
		EditorAnim anim = new EditorAnim(editor, filename, type, false);
		anim.displayName = animName;
		anim.pose = pose;
		anim.loop = loop;
		anim.duration = Math.max(50, (int)(animLength * 1000)); // minimum 50ms
		anim.add = false; // Bedrock animations are absolute by default
		anim.priority = 0;
		anim.intType = InterpolatorType.POLY_LOOP; // closest to catmullrom

		// Parse bone keyframes
		JsonObject bonesObj = animData.getAsJsonObject("bones");
		if (bonesObj == null) return anim;

		// Collect all unique keyframe times across all bones and channels
		List<Float> keyframeTimes = collectKeyframeTimes(bonesObj);
		if (keyframeTimes.isEmpty()) {
			// No keyframes found — animation is likely just a placeholder
			anim.getFrames().add(new AnimFrame(anim));
			return anim;
		}

		// Create frames at each keyframe time
		for (Float time : keyframeTimes) {
			AnimFrame frame = new AnimFrame(anim);
			anim.getFrames().add(frame);
		}

		// Set the selected frame to the first one
		if (!anim.getFrames().isEmpty()) {
			anim.setSelectedFrame(anim.getFrames().get(0));
		}

		// Now populate frame data for each bone at each keyframe
		for (String boneName : bonesObj.keySet()) {
			ModelElement target = boneNameToElement.get(boneName);
			if (target == null) {
				// Try case-insensitive lookup
				target = boneNameToElement.entrySet().stream()
					.filter(e -> e.getKey().equalsIgnoreCase(boneName))
					.map(Map.Entry::getValue)
					.findFirst().orElse(null);
			}
			if (target == null) continue;

			JsonObject boneData = bonesObj.getAsJsonObject(boneName);

			// Process rotation keyframes
			if (boneData.has("rotation")) {
				processChannel(boneData.get("rotation"), target, anim, keyframeTimes, ChannelType.ROTATION);
			}
			// Process position keyframes
			if (boneData.has("position")) {
				processChannel(boneData.get("position"), target, anim, keyframeTimes, ChannelType.POSITION);
			}
			// Process scale keyframes
			if (boneData.has("scale")) {
				processChannel(boneData.get("scale"), target, anim, keyframeTimes, ChannelType.SCALE);
			}
		}

		return anim;
	}

	private enum ChannelType { ROTATION, POSITION, SCALE }

	/**
	 * Process a single animation channel (rotation/position/scale) for a bone.
	 */
	private static void processChannel(JsonElement channelData, ModelElement target,
	                                   EditorAnim anim, List<Float> keyframeTimes,
	                                   ChannelType channelType) {
		if (channelData == null || !channelData.isJsonObject()) {

			// Handle molang expressions: rotation can be an array of strings
			if (channelData != null && channelData.isJsonArray() && channelType == ChannelType.ROTATION) {
				JsonArray arr = channelData.getAsJsonArray();
				if (arr.size() > 0 && arr.get(0).isJsonPrimitive() && arr.get(0).getAsJsonPrimitive().isString()) {
					// Molang expression — skip for now, cannot convert
					return;
				}
			}
			// Simple value (same for all frames) — just set on all frames
			if (channelData != null && channelData.isJsonArray()) {
				JsonArray arr = channelData.getAsJsonArray();
				if (arr.size() >= 3 && arr.get(0).isJsonPrimitive() && arr.get(0).getAsJsonPrimitive().isNumber()) {
					Vec3f value = new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
					for (AnimFrame frame : anim.getFrames()) {
						FrameData fd = frame.makeData(target);
						setValue(fd, value, channelType);
					}
				}
			}
			return;
		}

		JsonObject keyframes = channelData.getAsJsonObject();

		// Iterate through each keyframe time in the channel
		for (String timeKey : keyframes.keySet()) {
			try {
				float time = Float.parseFloat(timeKey);
				JsonElement keyframeData = keyframes.get(timeKey);

				Vec3f value = extractPostValue(keyframeData);
				if (value == null) continue;

				// Find the matching frame index
				int frameIdx = findFrameIndex(keyframeTimes, time);
				if (frameIdx < 0 || frameIdx >= anim.getFrames().size()) continue;

				AnimFrame frame = anim.getFrames().get(frameIdx);
				FrameData fd = frame.makeData(target);
				setValue(fd, value, channelType);
			} catch (NumberFormatException e) {
				// Skip invalid time keys
			}
		}
	}

	/**
	 * Extract the "post" value from a keyframe data entry.
	 * Handles both formats:
	 * <pre>{@code
	 * "0.0": { "post": [0, 0, 0], "lerp_mode": "catmullrom" }
	 * "0.0": [0, 0, 0]  // shorthand: just the value
	 * }</pre>
	 */
	private static Vec3f extractPostValue(JsonElement keyframeData) {
		if (keyframeData == null) return null;

		if (keyframeData.isJsonObject()) {
			JsonObject obj = keyframeData.getAsJsonObject();
			// Check for "post" key
			JsonElement post = obj.get("post");
			if (post != null && post.isJsonArray()) {
				JsonArray arr = post.getAsJsonArray();
				if (arr.size() >= 3) {
					return new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
				}
			}
			// Check for "pre" key
			JsonElement pre = obj.get("pre");
			if (pre != null && pre.isJsonArray()) {
				JsonArray arr = pre.getAsJsonArray();
				if (arr.size() >= 3) {
					return new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
				}
			}
		} else if (keyframeData.isJsonArray()) {
			JsonArray arr = keyframeData.getAsJsonArray();
			if (arr.size() >= 3 && arr.get(0).isJsonPrimitive() && arr.get(0).getAsJsonPrimitive().isNumber()) {
				return new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
			}
		}
		return null;
	}

	/**
	 * Collect all unique keyframe times across all bones and channels,
	 * sorted ascending.
	 */
	private static List<Float> collectKeyframeTimes(JsonObject bonesObj) {
		List<Float> times = new ArrayList<>();
		for (String boneName : bonesObj.keySet()) {
			JsonObject boneData = bonesObj.getAsJsonObject(boneName);
			for (String channel : new String[]{"rotation", "position", "scale"}) {
				JsonElement channelData = boneData.get(channel);
				if (channelData != null && channelData.isJsonObject()) {
					JsonObject kfObj = channelData.getAsJsonObject();
					for (String timeKey : kfObj.keySet()) {
						try {
							float t = Float.parseFloat(timeKey);
							if (!times.contains(t)) times.add(t);
						} catch (NumberFormatException ignored) {}
					}
				}
			}
		}
		times.sort(Float::compare);
		return times;
	}

	private static int findFrameIndex(List<Float> keyframeTimes, float time) {
		for (int i = 0; i < keyframeTimes.size(); i++) {
			if (Math.abs(keyframeTimes.get(i) - time) < 0.0001f) return i;
		}
		return -1;
	}

	private static void setValue(FrameData fd, Vec3f value, ChannelType type) {
		switch (type) {
			case ROTATION:
				fd.setRot(new Vec3f(value));
				break;
			case POSITION:
				fd.setPos(new Vec3f(value));
				break;
			case SCALE:
				fd.setScale(new Vec3f(value));
				break;
		}
	}

	private static String sanitizeFilename(String name) {
		return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
	}
}
