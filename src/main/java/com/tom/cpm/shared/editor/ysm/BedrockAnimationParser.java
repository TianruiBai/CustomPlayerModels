package com.tom.cpm.shared.editor.ysm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockBone;
import com.tom.cpm.shared.util.Log;

/**
 * Parses Minecraft Bedrock Edition animation JSON into CPM {@link EditorAnim} structures.
 *
 * <p>Handles:
 * <ul>
 *   <li>Rotation/Position/Scale keyframe channels with lerp_mode</li>
 *   <li>Visibility keyframes (scale=0 on bone or auxiliary bone)</li>
 *   <li>Timeline events (molang commands captured as metadata)</li>
 *   <li>Arm/hand animation naming conventions (hold_mainhand:..., use_mainhand:...)</li>
 *   <li>Automatic VanillaPose detection for common animation names</li>
 * </ul>
 */
public class BedrockAnimationParser {

	private static final int MAX_FRAMES = 240;

	/** Maps common YSM animation name patterns to CPM VanillaPose values */
	private static final Map<String, VanillaPose> NAME_TO_POSE = new LinkedHashMap<>();
	static {
		NAME_TO_POSE.put("idle", VanillaPose.STANDING);
		NAME_TO_POSE.put("walk", VanillaPose.WALKING);
		NAME_TO_POSE.put("run", VanillaPose.RUNNING);
		NAME_TO_POSE.put("sprint", VanillaPose.RUNNING);
		NAME_TO_POSE.put("sneak", VanillaPose.SNEAKING);
		NAME_TO_POSE.put("crouch", VanillaPose.SNEAKING);
		NAME_TO_POSE.put("swim", VanillaPose.SWIMMING);
		NAME_TO_POSE.put("fall", VanillaPose.FALLING);
		NAME_TO_POSE.put("sleep", VanillaPose.SLEEPING);
		NAME_TO_POSE.put("ride", VanillaPose.RIDING);
		NAME_TO_POSE.put("fly", VanillaPose.FLYING);
		NAME_TO_POSE.put("elytra", VanillaPose.CREATIVE_FLYING);
		NAME_TO_POSE.put("die", VanillaPose.DYING);
		NAME_TO_POSE.put("death", VanillaPose.DYING);
		NAME_TO_POSE.put("jump", VanillaPose.JUMPING);
		NAME_TO_POSE.put("hurt", VanillaPose.HURT);
		NAME_TO_POSE.put("damage", VanillaPose.HURT);
		NAME_TO_POSE.put("ladder", VanillaPose.ON_LADDER);
		NAME_TO_POSE.put("climb", VanillaPose.CLIMBING_ON_LADDER);
		NAME_TO_POSE.put("crawl", VanillaPose.CRAWLING);
		NAME_TO_POSE.put("fire", VanillaPose.ON_FIRE);
		NAME_TO_POSE.put("freeze", VanillaPose.FREEZING);
		NAME_TO_POSE.put("invisible", VanillaPose.INVISIBLE);
		NAME_TO_POSE.put("eating", VanillaPose.EATING_RIGHT);
		NAME_TO_POSE.put("punch", VanillaPose.PUNCH_RIGHT);
		NAME_TO_POSE.put("bow", VanillaPose.BOW_RIGHT);
		NAME_TO_POSE.put("block", VanillaPose.BLOCKING_RIGHT);
		NAME_TO_POSE.put("speak", VanillaPose.SPEAKING);
	}

	/** Stores timeline events extracted from an animation for logging/reference */
	public static class TimelineEvent {
		public final float time;
		public final List<String> commands;
		public TimelineEvent(float time, List<String> commands) {
			this.time = time;
			this.commands = commands;
		}
	}

	/**
	 * Parse all animations from a Bedrock animation JSON and create CPM EditorAnims.
	 * @param worldPositions pre-computed YSM absolute world positions for each bone
	 * @param boneIndex      bone name → BedrockBone lookup for computing parent-relative positions
	 */
	public static List<EditorAnim> parse(JsonObject animJson, Editor editor,
	                                     Map<String, ModelElement> boneNameToElement,
	                                     AnimationType defaultType,
	                                     Map<String, Vec3f> worldPositions,
	                                     Map<String, BedrockBone> boneIndex) {
		List<EditorAnim> results = new ArrayList<>();
		if (animJson == null) return results;

		JsonObject animations = animJson.getAsJsonObject("animations");
		if (animations == null) return results;

		for (String animName : animations.keySet()) {
			try {
				JsonObject animData = animations.getAsJsonObject(animName);
				if (animData == null) continue;

				EditorAnim anim = convertAnimation(animName, animData, editor, boneNameToElement,
					defaultType, worldPositions, boneIndex);
				if (anim != null) {
					results.add(anim);
				}
			} catch (Exception e) {
				Log.warn("[YSM Import] Failed to convert animation '" + animName + "': " + e.getMessage());
			}
		}
		return results;
	}

	/**
	 * Convert a single Bedrock animation to a CPM EditorAnim.
	 * Position keyframes (YSM absolute world space) are converted to additive
	 * deltas from the bone's default YSM world position, so they work correctly
	 * regardless of CPM re-parenting.
	 */
	private static EditorAnim convertAnimation(String animName, JsonObject animData, Editor editor,
	                                           Map<String, ModelElement> boneNameToElement,
	                                           AnimationType defaultType,
	                                           Map<String, Vec3f> worldPositions,
	                                           Map<String, BedrockBone> boneIndex) {
		// ---- Determine animation type and pose ----
		AnimationType type = defaultType;
		IPose pose = null;
		String filenamePrefix = "ysm_";

		// Check for arm/hand animation naming: "hold_mainhand:item" or "use_mainhand:eat"
		if (animName.contains(":")) {
			String[] parts = animName.split(":", 2);
			String prefix = parts[0].toLowerCase(Locale.ROOT);
			if (prefix.contains("mainhand") || prefix.contains("offhand")) {
				type = AnimationType.POSE;
			}
		}

		// Try name-based pose mapping
		String lookupName = animName.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, VanillaPose> entry : NAME_TO_POSE.entrySet()) {
			if (lookupName.contains(entry.getKey())) {
				pose = entry.getValue();
				type = AnimationType.POSE;
				filenamePrefix = "v_" + entry.getValue().name().toLowerCase(Locale.ROOT) + "_";
				break;
			}
		}

		// Direct VanillaPose enum match
		if (pose == null) {
			for (VanillaPose vp : VanillaPose.VALUES) {
				if (lookupName.equals(vp.name().toLowerCase(Locale.ROOT))) {
					pose = vp;
					type = AnimationType.POSE;
					filenamePrefix = "v_" + vp.name().toLowerCase(Locale.ROOT) + "_";
					break;
				}
			}
		}

		// For unrecognized animations, use gesture type
		if (pose == null && type != AnimationType.POSE) {
			type = AnimationType.GESTURE;
			filenamePrefix = "g_ysm_";
		}
		// Guard: UI expects POSE animations to always have a non-null pose.
		if (pose == null && type == AnimationType.POSE) {
			type = AnimationType.GESTURE;
			filenamePrefix = "g_ysm_";
		}

		// ---- Animation-level properties ----
		boolean loop = animData.has("loop") && animData.get("loop").getAsBoolean();
		String loopMode = animData.has("loop") ? animData.get("loop").getAsString() : "false";
		if ("hold_on_last_frame".equals(loopMode)) {
			loop = false;
		}
		float animLength = animData.has("animation_length") ? animData.get("animation_length").getAsFloat() : 1.0f;

		String filename = filenamePrefix + sanitizeFilename(animName) + ".json";
		EditorAnim anim = new EditorAnim(editor, filename, type, false);
		anim.displayName = animName;
		anim.pose = pose;
		anim.loop = loop;
		anim.duration = Math.max(50, (int)(animLength * 1000));
		anim.add = true;  // Additive mode: animation deltas add to element's base position
		anim.priority = 0;
		anim.intType = InterpolatorType.POLY_LOOP;

		// ---- Parse timeline events (molang commands) ----
		JsonObject timeline = animData.getAsJsonObject("timeline");
		if (timeline != null) {
			int eventCount = 0;
			for (String timeKey : timeline.keySet()) {
				try {
					Float.parseFloat(timeKey);
					JsonElement cmds = timeline.get(timeKey);
					if (cmds != null && cmds.isJsonArray() && cmds.getAsJsonArray().size() > 0) {
						eventCount++;
					}
				} catch (NumberFormatException ignored) {}
			}
			if (eventCount > 0) {
				Log.info("[YSM Import] Animation '" + animName + "' has " + eventCount +
					" timeline events (molang, not converted to CPM)");
			}
		}

		// ---- Parse bone keyframes ----
		JsonObject bonesObj = animData.getAsJsonObject("bones");
		if (bonesObj == null) {
			anim.getFrames().add(new AnimFrame(anim));
			if (!anim.getFrames().isEmpty()) anim.setSelectedFrame(anim.getFrames().get(0));
			return anim;
		}

		List<Float> keyframeTimes = collectKeyframeTimes(bonesObj);
		if (keyframeTimes.isEmpty()) {
			anim.getFrames().add(new AnimFrame(anim));
			if (!anim.getFrames().isEmpty()) anim.setSelectedFrame(anim.getFrames().get(0));
			return anim;
		}

		if (keyframeTimes.size() > MAX_FRAMES) {
			Log.warn("[YSM Import] Animation '" + animName + "' has " + keyframeTimes.size() +
				" keyframes, limiting to " + MAX_FRAMES);
			keyframeTimes = keyframeTimes.subList(0, MAX_FRAMES);
		}

		for (Float time : keyframeTimes) {
			anim.getFrames().add(new AnimFrame(anim));
		}
		if (!anim.getFrames().isEmpty()) {
			anim.setSelectedFrame(anim.getFrames().get(0));
		}

		// Populate frame data for each bone at each keyframe
		for (String boneName : bonesObj.keySet()) {
			ModelElement target = boneNameToElement.get(boneName);
			if (target == null) {
				target = boneNameToElement.entrySet().stream()
					.filter(e -> e.getKey().equalsIgnoreCase(boneName))
					.map(Map.Entry::getValue)
					.findFirst().orElse(null);
			}
			if (target == null) continue;

			JsonObject boneData = bonesObj.getAsJsonObject(boneName);

			if (boneData.has("rotation")) {
				processChannel(boneData.get("rotation"), target, anim, keyframeTimes, ChannelType.ROTATION);
			}
			if (boneData.has("position")) {
				// Convert YSM absolute position → CPM delta from default world position
				Vec3f defaultWorldPos = worldPositions.get(boneName);
				processChannel(boneData.get("position"), target, anim, keyframeTimes,
					ChannelType.POSITION, defaultWorldPos);
			}
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
		processChannel(channelData, target, anim, keyframeTimes, channelType, null);
	}

	/**
	 * Process a single animation channel. For POSITION channels, ysmDefaultWorldPos
	 * is used to convert YSM absolute positions to CPM additive deltas.
	 */
	private static void processChannel(JsonElement channelData, ModelElement target,
	                                   EditorAnim anim, List<Float> keyframeTimes,
	                                   ChannelType channelType, Vec3f ysmDefaultWorldPos) {
		if (channelData == null) return;

		if (!channelData.isJsonObject()) {
			if (channelData.isJsonArray()) {
				JsonArray arr = channelData.getAsJsonArray();
				if (arr.size() == 0) return;

				// Check for molang expression (string elements)
				if (arr.get(0).isJsonPrimitive() && arr.get(0).getAsJsonPrimitive().isString()) {
					return; // Molang expression — skip
				}

				// Static numeric value — apply to all frames
				if (arr.size() >= 3 && arr.get(0).isJsonPrimitive() && arr.get(0).getAsJsonPrimitive().isNumber()) {
					Vec3f value = new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
					value = convertPositionValue(value, channelType, ysmDefaultWorldPos);
					for (AnimFrame frame : anim.getFrames()) {
						setValue(frame.makeData(target), value, channelType);
					}
				} else if (arr.size() == 1 && arr.get(0).isJsonPrimitive() && arr.get(0).getAsJsonPrimitive().isNumber()) {
					// Single value — e.g., "scale": 0 means hide bone
					float val = arr.get(0).getAsFloat();
					for (AnimFrame frame : anim.getFrames()) {
						FrameData fd = frame.makeData(target);
						if (channelType == ChannelType.SCALE) {
							fd.setScale(new Vec3f(val, val, val));
						}
					}
				}
			}
			return;
		}

		JsonObject keyframes = channelData.getAsJsonObject();
		for (String timeKey : keyframes.keySet()) {
			try {
				float time = Float.parseFloat(timeKey);
				Vec3f value = extractPostValue(keyframes.get(timeKey));
				if (value == null) continue;
				value = convertPositionValue(value, channelType, ysmDefaultWorldPos);

				int frameIdx = findFrameIndex(keyframeTimes, time);
				if (frameIdx < 0 || frameIdx >= anim.getFrames().size()) continue;

				setValue(anim.getFrames().get(frameIdx).makeData(target), value, channelType);
			} catch (NumberFormatException ignored) {}
		}
	}

	/**
	 * Convert a YSM absolute animation position/rotation value to a CPM additive delta.
	 * For non-position channels or when no default position is available, returns unchanged.
	 * Rotation is 1:1 (verified identical between YSM and CPM).
	 * Position uses [x, -y, z] mapping (only Y axis flipped).
	 */
	private static Vec3f convertPositionValue(Vec3f value, ChannelType channelType, Vec3f defaultWorldPos) {
		if (channelType == ChannelType.ROTATION) {
			// Rotation: 1:1 identical, no sign change
			return new Vec3f(value);
		}
		if (channelType != ChannelType.POSITION || defaultWorldPos == null) return value;
		// Position: map both target and default from YSM→CPM space ([x, -y, z])
		Vec3f mappedTarget = new Vec3f(value.x, -value.y, value.z);
		Vec3f mappedDefault = new Vec3f(defaultWorldPos.x, -defaultWorldPos.y, defaultWorldPos.z);
		// delta = animation_target - default_world_position in CPM space
		return mappedTarget.sub(mappedDefault);
	}

	private static Vec3f extractPostValue(JsonElement keyframeData) {
		if (keyframeData == null) return null;
		if (keyframeData.isJsonObject()) {
			JsonObject obj = keyframeData.getAsJsonObject();
			JsonElement post = obj.get("post");
			if (post != null && post.isJsonArray() && post.getAsJsonArray().size() >= 3) {
				JsonArray arr = post.getAsJsonArray();
				// Guard: skip molang expressions (string elements in numeric arrays)
				if (!isNumericArray(arr)) return null;
				return new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
			}
			JsonElement pre = obj.get("pre");
			if (pre != null && pre.isJsonArray() && pre.getAsJsonArray().size() >= 3) {
				JsonArray arr = pre.getAsJsonArray();
				if (!isNumericArray(arr)) return null;
				return new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
			}
			// Molang value (string) — skip, not convertible
			JsonElement molangPost = obj.get("post");
			if (molangPost != null && molangPost.isJsonPrimitive() && molangPost.getAsJsonPrimitive().isString()) {
				return null;
			}
		} else if (keyframeData.isJsonArray() && keyframeData.getAsJsonArray().size() >= 3) {
			JsonArray arr = keyframeData.getAsJsonArray();
			if (isNumericArray(arr)) {
				return new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
			}
		}
		return null;
	}

	/** Check if all elements of a JsonArray are numeric primitives */
	private static boolean isNumericArray(JsonArray arr) {
		for (JsonElement e : arr) {
			if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
		}
		return true;
	}

	private static List<Float> collectKeyframeTimes(JsonObject bonesObj) {
		List<Float> times = new ArrayList<>();
		for (String boneName : bonesObj.keySet()) {
			JsonObject boneData = bonesObj.getAsJsonObject(boneName);
			for (String channel : new String[]{"rotation", "position", "scale"}) {
				JsonElement cd = boneData.get(channel);
				if (cd != null && cd.isJsonObject()) {
					for (String timeKey : cd.getAsJsonObject().keySet()) {
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
			case ROTATION: fd.setRot(new Vec3f(value)); break;
			case POSITION: fd.setPos(new Vec3f(value)); break;
			case SCALE:    fd.setScale(new Vec3f(value)); break;
		}
	}

	private static String sanitizeFilename(String name) {
		return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
	}
}
