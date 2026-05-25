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
	 * @param source         source label for gesture grouping (e.g. "tac", "extra")
	 * @param worldPositions pre-computed YSM absolute world positions for each bone
	 * @param boneIndex      bone name → BedrockBone lookup for computing parent-relative positions
	 */
	public static List<EditorAnim> parse(JsonObject animJson, Editor editor,
	                                     Map<String, ModelElement> boneNameToElement,
	                                     AnimationType defaultType,
	                                     String source,
	                                     Map<String, Vec3f> worldPositions,
	                                     Map<String, BedrockBone> boneIndex) {
		List<EditorAnim> results = new ArrayList<>();
		if (animJson == null) return results;

		JsonObject animations = animJson.getAsJsonObject("animations");
		if (animations == null) return results;

		Map<String, Integer> missingTargets = new LinkedHashMap<>();

		for (String animName : animations.keySet()) {
			try {
				JsonObject animData = animations.getAsJsonObject(animName);
				if (animData == null) continue;

				EditorAnim anim = convertAnimation(animName, animData, editor, boneNameToElement,
					defaultType, source, worldPositions, boneIndex, missingTargets);
				if (anim != null) {
					results.add(anim);
				}
			} catch (Exception e) {
				Log.warn("[YSM Import] Failed to convert animation '" + animName + "': " + e.getMessage());
			}
		}
		if (!missingTargets.isEmpty()) {
			int skippedChannels = 0;
			StringBuilder sample = new StringBuilder();
			int shown = 0;
			for (Map.Entry<String, Integer> e : missingTargets.entrySet()) {
				skippedChannels += e.getValue();
				if (shown < 12) {
					if (sample.length() > 0) sample.append(", ");
					sample.append(e.getKey());
					shown++;
				}
			}
			if (missingTargets.size() > shown) sample.append(", ...");
			Log.info("[YSM Import] " + (source != null ? source : "animations") +
				": skipped " + skippedChannels + " channels for " + missingTargets.size() +
				" unmapped animation target bones (" + sample + ")");
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
	                                           String source,
	                                           Map<String, Vec3f> worldPositions,
	                                           Map<String, BedrockBone> boneIndex,
	                                           Map<String, Integer> missingTargets) {
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
		// Parse loop: can be boolean (true/false), string ("true"/"false"),
		// or "hold_on_last_frame" (play once, freeze at end).
		boolean loop = false;
		boolean mustFinish = false;
		if (animData.has("loop")) {
			JsonElement loopElem = animData.get("loop");
			if (loopElem.isJsonPrimitive()) {
				if (loopElem.getAsJsonPrimitive().isBoolean()) {
					loop = loopElem.getAsBoolean();
				} else if (loopElem.getAsJsonPrimitive().isString()) {
					String loopStr = loopElem.getAsString();
					if ("hold_on_last_frame".equals(loopStr)) {
						loop = false;
						mustFinish = true;
					} else {
						loop = Boolean.parseBoolean(loopStr);
					}
				}
			}
		}
		float animLength = animData.has("animation_length") ? animData.get("animation_length").getAsFloat() : 1.0f;

		// Parse optional blend/override settings
		boolean overridePrev = animData.has("override_previous_animation")
			&& animData.get("override_previous_animation").getAsBoolean();

		String filename = filenamePrefix + sanitizeFilename(animName) + ".json";
		EditorAnim anim = new EditorAnim(editor, filename, type, false);
		anim.displayName = (source != null ? "[" + source + "] " : "") + animName;
		anim.pose = pose;
		anim.loop = loop;
		anim.mustFinish = mustFinish;
		anim.duration = Math.max(50, (int)(animLength * 1000));
		anim.add = true;
		// YSM models can have dozens of gestures; CPM's layer-encoding only
		// supports 62 unique slots via 6 skin layers. Disable layer encoding
		// so all gestures can be registered without hitting the limit.
		if (type == AnimationType.GESTURE) {
			anim.layerControlled = false;
		}
		anim.priority = overridePrev ? 10 : 0;
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

		// Build uniform frame grid. CPM assumes equally-spaced frames
		// and maps real time → frame index via: (millis % duration) / duration * frameCount.
		// Non-uniform keyframe times would cause wrong interpolation.
		final int FPS = 20;
		int frameCount = Math.max(2, (int)(animLength * FPS));
		if (frameCount > MAX_FRAMES) frameCount = MAX_FRAMES;

		for (int i = 0; i < frameCount; i++) {
			anim.getFrames().add(new AnimFrame(anim));
		}
		if (!anim.getFrames().isEmpty()) {
			anim.setSelectedFrame(anim.getFrames().get(0));
		}

		// Populate each bone's channel data at uniform sample times
		boolean normalizeClosedPositionTracks = shouldNormalizeClosedPositionTracks(type, pose, loop, mustFinish);
		for (String boneName : bonesObj.keySet()) {
			ModelElement target = findTarget(boneName, boneNameToElement);
			if (target == null) {
				int channelCount = 0;
				JsonObject missingBoneData = bonesObj.getAsJsonObject(boneName);
				if (missingBoneData.has("rotation")) channelCount++;
				if (missingBoneData.has("position")) channelCount++;
				if (missingBoneData.has("scale")) channelCount++;
				missingTargets.merge(boneName, Math.max(1, channelCount), Integer::sum);
				continue;
			}

			JsonObject boneData = bonesObj.getAsJsonObject(boneName);

			if (boneData.has("rotation")) {
				sampleChannel(boneData.get("rotation"), boneName, target, anim, frameCount,
					animLength, loop, ChannelType.ROTATION, false, worldPositions, boneIndex);
			}
			if (boneData.has("position")) {
				sampleChannel(boneData.get("position"), boneName, target, anim, frameCount,
					animLength, loop, ChannelType.POSITION, normalizeClosedPositionTracks, worldPositions, boneIndex);
			}
			if (boneData.has("scale")) {
				sampleChannel(boneData.get("scale"), boneName, target, anim, frameCount,
					animLength, loop, ChannelType.SCALE, false, worldPositions, boneIndex);
			}
		}

		return anim;
	}

	private static ModelElement findTarget(String boneName, Map<String, ModelElement> boneNameToElement) {
		ModelElement target = boneNameToElement.get(boneName);
		if (target != null) return target;
		for (Map.Entry<String, ModelElement> e : boneNameToElement.entrySet()) {
			if (e.getKey().equalsIgnoreCase(boneName)) return e.getValue();
		}
		String normalized = normalizeBoneName(boneName);
		for (Map.Entry<String, ModelElement> e : boneNameToElement.entrySet()) {
			if (normalizeBoneName(e.getKey()).equals(normalized)) return e.getValue();
		}
		return null;
	}

	private static String normalizeBoneName(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
		}
		return sb.toString();
	}

	private enum ChannelType { ROTATION, POSITION, SCALE }

	private static boolean shouldNormalizeClosedPositionTracks(AnimationType type, IPose pose,
			boolean loop, boolean mustFinish) {
		return type == AnimationType.POSE && pose != null && !loop && !mustFinish;
	}

	/**
	 * Sample a bone channel at uniform time intervals and store FrameData.
	 * <p>
	 * CPM assumes equally-spaced animation frames. This method resamples
	 * the YSM keyframe data onto a uniform grid so CPM's time→frame mapping
	 * produces correct interpolation.
	 * <p>
	 * For keyframed channels, linear interpolation is used between keyframes.
	 * For static (array) channels, the same value is applied to all frames.
	 * For looping animations, the value wraps from last back to first keyframe.
	 */
	private static void sampleChannel(JsonElement channelData, String boneName, ModelElement target,
	                                  EditorAnim anim, int frameCount, float animLength,
	                                  boolean loop, ChannelType channelType,
	                                  boolean normalizeClosedPositionTracks,
	                                  Map<String, Vec3f> worldPositions,
	                                  Map<String, BedrockBone> boneIndex) {
		if (channelData == null) return;

		// --- Handle static array channels (non-keyframed) ---
		if (!channelData.isJsonObject()) {
			if (channelData.isJsonArray()) {
				JsonArray arr = channelData.getAsJsonArray();
				if (arr.size() == 0) return;
				if (!isNumericArray(arr)) return; // molang → skip

				if (arr.size() >= 3) {
					Vec3f value = new Vec3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
					List<float[]> rawSamples = new ArrayList<>();
					rawSamples.add(new float[]{0, value.x, value.y, value.z});
					boolean absolutePosition = channelType == ChannelType.POSITION &&
						looksLikeAbsolutePosition(rawSamples, worldPositions.get(boneName));
					value = toCpmDelta(value, channelType, boneName,
						worldPositions, boneIndex, absolutePosition, null);
					for (AnimFrame frame : anim.getFrames()) {
						setValue(frame.makeData(target), value, channelType);
					}
				} else if (arr.size() == 1) {
					float val = arr.get(0).getAsFloat();
					if (channelType == ChannelType.SCALE) {
						for (AnimFrame frame : anim.getFrames()) {
							frame.makeData(target).setScale(new Vec3f(val, val, val));
						}
					}
				}
			}
			return;
		}

		// --- Keyframed channel: build sorted [time, value] list ---
		JsonObject keyframes = channelData.getAsJsonObject();
		List<float[]> rawSamples = new ArrayList<>();
		for (String timeKey : keyframes.keySet()) {
			try {
				float time = Float.parseFloat(timeKey);
				Vec3f raw = extractPostValue(keyframes.get(timeKey));
				if (raw == null) continue;
				rawSamples.add(new float[]{time, raw.x, raw.y, raw.z});
			} catch (NumberFormatException ignored) {}
		}
		if (rawSamples.isEmpty()) return;

		// Sort by time
		rawSamples.sort((a, b) -> Float.compare(a[0], b[0]));

		boolean absolutePosition = channelType == ChannelType.POSITION &&
			looksLikeAbsolutePosition(rawSamples, worldPositions.get(boneName));
		Vec3f positionBaseline = null;
		if (channelType == ChannelType.POSITION && !absolutePosition && normalizeClosedPositionTracks &&
				isClosedPositionTrack(rawSamples)) {
			positionBaseline = sampleVec(rawSamples.get(0));
		}

		List<float[]> samples = new ArrayList<>();
		for (float[] rawSample : rawSamples) {
			Vec3f raw = sampleVec(rawSample);
			Vec3f delta = toCpmDelta(raw, channelType, boneName,
				worldPositions, boneIndex, absolutePosition, positionBaseline);
			samples.add(new float[]{rawSample[0], delta.x, delta.y, delta.z});
		}

		// --- Sample at each uniform frame time ---
		for (int fi = 0; fi < frameCount; fi++) {
			float sampleTime;
			if (frameCount == 1) {
				sampleTime = 0;
			} else if (loop) {
				// Looping: sample times wrap around
				sampleTime = (fi / (float) frameCount) * animLength;
			} else {
				sampleTime = (fi / (float) (frameCount - 1)) * animLength;
			}

			Vec3f interpolated = interpolateSamples(samples, sampleTime, animLength, loop);
			setValue(anim.getFrames().get(fi).makeData(target), interpolated, channelType);
		}
	}

	/**
	 * Linearly interpolate between two surrounding keyframes at the given sample time.
	 * For looping animations, wraps around from last to first keyframe.
	 */
	private static Vec3f interpolateSamples(List<float[]> samples, float sampleTime,
	                                        float animLength, boolean loop) {
		int n = samples.size();
		if (n == 1) {
			float[] s = samples.get(0);
			return new Vec3f(s[1], s[2], s[3]);
		}

		// Find surrounding keyframes
		for (int i = 0; i < n; i++) {
			float[] cur = samples.get(i);
			if (cur[0] > sampleTime) {
				// sampleTime is between samples[i-1] and samples[i]
				float[] prev = samples.get(i == 0 ? (loop ? n - 1 : 0) : i - 1);
				float t1 = prev[0];
				float t2 = cur[0];
				if (i == 0 && !loop) {
					// Before first keyframe, non-looping: use first value
					return new Vec3f(cur[1], cur[2], cur[3]);
				}
				if (loop && i == 0) {
					// Wrap: previous is last sample, shifted before 0
					t1 = prev[0] - animLength;
				}
				float range = t2 - t1;
				float alpha = range == 0 ? 0 : (sampleTime - t1) / range;
				return lerp(prev, cur, alpha);
			}
		}

		// After last keyframe
		float[] last = samples.get(n - 1);
		if (loop) {
			// Wrap to first keyframe
			float[] first = samples.get(0);
			float t1 = last[0];
			float t2 = first[0] + animLength;
			float range = t2 - t1;
			float alpha = range == 0 ? 0 : (sampleTime - t1) / range;
			return lerp(last, first, alpha);
		} else {
			return new Vec3f(last[1], last[2], last[3]);
		}
	}

	private static Vec3f lerp(float[] a, float[] b, float alpha) {
		return new Vec3f(
			a[1] + (b[1] - a[1]) * alpha,
			a[2] + (b[2] - a[2]) * alpha,
			a[3] + (b[3] - a[3]) * alpha
		);
	}

	/**
	 * Convert a Bedrock animation value to a CPM additive delta.
	 * <p>
	 * YSM rotation keyframes are additive offsets from the bone's initial
	 * rotation. The CPM model importer stores YSM bone rest rotations in the same
	 * rotation basis, so keep animation rotations as raw additive deltas too.
	 * <p>
	 * YSM position keyframes are usually offsets from the default pose, but some
	 * Bedrock exports store absolute pivot positions. Absolute-looking tracks are
	 * converted back to additive deltas from the source rest pivot; closed transient
	 * pose tracks can also subtract their repeated first/last offset.
	 * <p>
	 * Rotation deltas are kept as raw values (NOT wrapped to 0-360)
	 * to preserve correct interpolation direction; wrapping causes
	 * the interpolator to take the long way around when values cross
	 * the 0/360 boundary.
	 */
	private static Vec3f toCpmDelta(Vec3f ysmValue, ChannelType channelType, String boneName,
			Map<String, Vec3f> worldPositions, Map<String, BedrockBone> boneIndex,
			boolean absolutePosition, Vec3f positionBaseline) {
		if (channelType == ChannelType.ROTATION) {
			return new Vec3f(
				ysmValue.x,
				ysmValue.y,
				ysmValue.z
			);
		}
		if (channelType == ChannelType.POSITION) {
			Vec3f delta = new Vec3f(ysmValue);
			if (absolutePosition) {
				Vec3f rest = worldPositions.get(boneName);
				if (rest != null) delta = delta.sub(rest);
				BedrockBone bone = boneIndex.get(boneName);
				if (bone != null && bone.parent != null) {
					BedrockBone parent = boneIndex.get(bone.parent);
					if (parent != null) {
						delta = YsmCoordUtil.worldDeltaToParentLocal(delta, parent.rotation);
					}
				}
			} else if (positionBaseline != null) {
				delta = delta.sub(positionBaseline);
			}
			return new Vec3f(delta.x, -delta.y, delta.z);
		}
		// Scale: 1:1
		return ysmValue;
	}

	private static boolean looksLikeAbsolutePosition(List<float[]> rawSamples, Vec3f defaultWorldPos) {
		if (defaultWorldPos == null || isZero(defaultWorldPos) || rawSamples.isEmpty()) return false;
		float tolerance = Math.max(1.0f, magnitude(defaultWorldPos) * 0.08f);
		int close = 0;
		for (float[] sample : rawSamples) {
			if (distance(sampleVec(sample), defaultWorldPos) <= tolerance) close++;
		}
		return close > 0 && close * 2 >= rawSamples.size();
	}

	private static boolean isClosedPositionTrack(List<float[]> rawSamples) {
		if (rawSamples.size() < 2) return false;
		Vec3f first = sampleVec(rawSamples.get(0));
		Vec3f last = sampleVec(rawSamples.get(rawSamples.size() - 1));
		return !isZero(first) && distance(first, last) <= 0.01f;
	}

	private static Vec3f sampleVec(float[] sample) {
		return new Vec3f(sample[1], sample[2], sample[3]);
	}

	private static float magnitude(Vec3f v) {
		return (float) Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
	}

	private static float distance(Vec3f a, Vec3f b) {
		float dx = a.x - b.x;
		float dy = a.y - b.y;
		float dz = a.z - b.z;
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static boolean isZero(Vec3f v) {
		return Math.abs(v.x) <= 0.001f && Math.abs(v.y) <= 0.001f && Math.abs(v.z) <= 0.001f;
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
