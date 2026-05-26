package com.tom.cpm.shared.editor.ysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tom.cpm.shared.util.Log;

/**
 * Parses Bedrock animation controller JSON to extract gesture-to-animation
 * mappings and referenced animation names.
 *
 * <p>YSM animation controllers are state machines with molang transitions.
 * CPM does not have an equivalent system, so we extract what we can:
 * <ul>
 *   <li>Which animations are referenced by each controller</li>
 *   <li>Gesture name → animation name mappings (extracted from state names)</li>
 * </ul>
 *
 * <p>Full molang state machine conversion is not supported.
 */
public class BedrockControllerParser {

	/**
	 * Extract all animation names referenced across all controllers.
	 * @param controllerJson the parsed animation_controllers.json
	 * @return list of animation names referenced
	 */
	public static List<String> extractAnimationNames(JsonObject controllerJson) {
		List<String> names = new ArrayList<>();
		if (controllerJson == null) return names;

		JsonObject controllers = controllerJson.getAsJsonObject("animation_controllers");
		if (controllers == null) return names;

		for (String ctrlName : controllers.keySet()) {
			JsonObject controller = controllers.getAsJsonObject(ctrlName);
			if (controller == null) continue;

			JsonObject states = controller.getAsJsonObject("states");
			if (states == null) continue;

			for (String stateName : states.keySet()) {
				JsonObject state = states.getAsJsonObject(stateName);
				if (state == null) continue;

				JsonArray animations = state.getAsJsonArray("animations");
				if (animations == null) continue;

				for (JsonElement animElem : animations) {
					if (animElem.isJsonPrimitive()) {
						names.add(animElem.getAsString());
					} else if (animElem.isJsonObject()) {
						// Format: {"animName": "condition"}
						JsonObject animObj = animElem.getAsJsonObject();
						for (String key : animObj.keySet()) {
							names.add(key);
						}
					}
				}
			}
		}
		return names;
	}

	/**
	 * Extract gesture-to-animation mappings by analyzing controller states.
	 * Each state that references an animation is treated as a potential gesture trigger.
	 *
	 * @param controllerJson the parsed animation_controllers.json
	 * @return map of gesture name → animation name
	 */
	public static Map<String, String> extractGestureMappings(JsonObject controllerJson) {
		Map<String, String> mappings = new HashMap<>();
		if (controllerJson == null) return mappings;

		JsonObject controllers = controllerJson.getAsJsonObject("animation_controllers");
		if (controllers == null) return mappings;

		for (String ctrlName : controllers.keySet()) {
			JsonObject controller = controllers.getAsJsonObject(ctrlName);
			if (controller == null) continue;

			JsonObject states = controller.getAsJsonObject("states");
			if (states == null) continue;

			for (String stateName : states.keySet()) {
				JsonObject state = states.getAsJsonObject(stateName);
				if (state == null) continue;

				JsonArray animations = state.getAsJsonArray("animations");
				if (animations == null || animations.size() == 0) continue;

				// Use the first animation in the state as the gesture trigger
				JsonElement firstAnim = animations.get(0);
				String animName = null;
				if (firstAnim.isJsonPrimitive()) {
					animName = firstAnim.getAsString();
				} else if (firstAnim.isJsonObject()) {
					JsonObject animObj = firstAnim.getAsJsonObject();
					animName = animObj.keySet().iterator().next();
				}
				if (animName != null && !"emp".equals(animName)) {
					mappings.put(stateName, animName);
				}
			}
		}
		return mappings;
	}

	/**
	 * Check if an animation controller uses molang-based transitions
	 * (which cannot be converted to CPM's trigger system).
	 *
	 * @param controllerJson the parsed animation_controllers.json
	 * @return true if molang transitions are detected
	 */
	public static boolean hasMolangTransitions(JsonObject controllerJson) {
		if (controllerJson == null) return false;

		JsonObject controllers = controllerJson.getAsJsonObject("animation_controllers");
		if (controllers == null) return false;

		for (String ctrlName : controllers.keySet()) {
			JsonObject controller = controllers.getAsJsonObject(ctrlName);
			if (controller == null) continue;

			JsonObject states = controller.getAsJsonObject("states");
			if (states == null) continue;

			for (String stateName : states.keySet()) {
				JsonObject state = states.getAsJsonObject(stateName);
				if (state == null) continue;

				JsonArray transitions = state.getAsJsonArray("transitions");
				if (transitions != null && transitions.size() > 0) {
					for (JsonElement t : transitions) {
						if (t.isJsonObject()) {
							JsonObject transObj = t.getAsJsonObject();
							for (Map.Entry<String, JsonElement> entry : transObj.entrySet()) {
								JsonElement v = entry.getValue();
								if (v.isJsonPrimitive()) {
									String cond = v.getAsString();
									if (cond.contains("query.") || cond.contains("v.") || cond.contains("ctrl.")) {
										return true;
									}
								}
							}
						}
					}
				}
			}
		}
		return false;
	}
}
