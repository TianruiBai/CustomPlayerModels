package com.tom.cpm.shared.psl;

import java.io.IOException;

import com.tom.cpm.shared.io.IOHelper;

/**
 * Defines when a PSL element is active.
 * Shared across all PSL element types (Particle, Physics, Sound, Light, MIDI).
 */
public class PslTrigger {

	public enum TriggerType {
		/** Always active */
		ALWAYS,
		/** Active during a specific animation */
		ANIMATION,
		/** Active when a specific gesture is playing */
		GESTURE,
		/** Active during a specific vanilla pose (sneaking, swimming, etc.) */
		VANILLA_POSE,
		/** Active when a named parameter is within a range */
		VALUE_RANGE,
		/** Active on specific game events (damage, death, jump, etc.) */
		GAME_EVENT,
		/** Triggered at specific animation keyframes (one-shot) */
		KEYFRAME,
		/** Active when a CPM layer toggle (BoolParameterToggleButtonData) is ON */
		LAYER_TOGGLE,
		;
		public static final TriggerType[] VALUES = values();
	}

	private TriggerType type;
	private String animName;
	private String gestureName;
	private String vanillaPoseName;
	private String paramName;
	private float paramMin;
	private float paramMax;
	private String eventName;
	private int layerParam;
	private int layerMask;

	public PslTrigger() {
		this.type = TriggerType.ALWAYS;
	}

	public PslTrigger(TriggerType type) {
		this.type = type;
	}

	public void setType(TriggerType type) {
		this.type = type;
		// Clear old type-specific fields
		this.animName = null;
		this.gestureName = null;
		this.vanillaPoseName = null;
		this.paramName = null;
		this.paramMin = 0;
		this.paramMax = 0;
		this.eventName = null;
		this.layerParam = 0;
		this.layerMask = 0;
	}

	public TriggerType getType() {
		return type;
	}

	public String getAnimName() {
		return animName;
	}

	public void setAnimName(String animName) {
		this.animName = animName;
	}

	public String getGestureName() {
		return gestureName;
	}

	public void setGestureName(String gestureName) {
		this.gestureName = gestureName;
	}

	public String getVanillaPoseName() {
		return vanillaPoseName;
	}

	public void setVanillaPoseName(String vanillaPoseName) {
		this.vanillaPoseName = vanillaPoseName;
	}

	public String getParamName() {
		return paramName;
	}

	public void setParamName(String paramName) {
		this.paramName = paramName;
	}

	public float getParamMin() {
		return paramMin;
	}

	public void setParamMin(float paramMin) {
		this.paramMin = paramMin;
	}

	public float getParamMax() {
		return paramMax;
	}

	public void setParamMax(float paramMax) {
		this.paramMax = paramMax;
	}

	public String getEventName() {
		return eventName;
	}

	public void setEventName(String eventName) {
		this.eventName = eventName;
	}

	public int getLayerParam() {
		return layerParam;
	}

	public void setLayerParam(int layerParam) {
		this.layerParam = layerParam;
	}

	public int getLayerMask() {
		return layerMask;
	}

	public void setLayerMask(int layerMask) {
		this.layerMask = layerMask;
	}

	/**
	 * Evaluate whether this trigger is active given the current state.
	 */
	public boolean isActive(PslTriggerState state) {
		if (state == null) return type == TriggerType.ALWAYS;

		switch (type) {
			case ALWAYS:
				return true;
			case ANIMATION:
				return animName != null && animName.equals(state.getCurrentAnimation());
			case GESTURE:
				return gestureName != null && state.isGestureActive(gestureName);
			case VANILLA_POSE:
				return vanillaPoseName != null && vanillaPoseName.equals(state.getCurrentVanillaPose());
			case VALUE_RANGE:
				if (paramName != null && state.hasParameter(paramName)) {
					float val = state.getParameter(paramName);
					return val >= paramMin && val <= paramMax;
				}
				return false;
			case GAME_EVENT:
				return eventName != null && state.isEventActive(eventName);
			case KEYFRAME:
				return animName != null && animName.equals(state.getCurrentAnimation())
					&& state.isKeyframeTriggered();
			case LAYER_TOGGLE:
				if (layerMask == 0) return true; // unconfigured = always on
				return state.getGestureParam(layerParam, layerMask);
			default:
				return false;
		}
	}

	public void write(IOHelper out) throws IOException {
		out.writeVarInt(type.ordinal());
		switch (type) {
			case ANIMATION:
			case KEYFRAME:
				out.writeUTF(animName != null ? animName : "");
				break;
			case GESTURE:
				out.writeUTF(gestureName != null ? gestureName : "");
				break;
			case VANILLA_POSE:
				out.writeUTF(vanillaPoseName != null ? vanillaPoseName : "");
				break;
			case VALUE_RANGE:
				out.writeUTF(paramName != null ? paramName : "");
				out.writeFloat(paramMin);
				out.writeFloat(paramMax);
				break;
			case GAME_EVENT:
				out.writeUTF(eventName != null ? eventName : "");
				break;
			case LAYER_TOGGLE:
				out.writeVarInt(layerParam);
				out.write(layerMask);
				break;
			default:
				break;
		}
	}

	public static PslTrigger read(IOHelper in) throws IOException {
		TriggerType type = TriggerType.VALUES[in.readVarInt()];
		PslTrigger trigger = new PslTrigger(type);

		switch (type) {
			case ANIMATION:
			case KEYFRAME:
				trigger.animName = in.readUTF();
				if (trigger.animName.isEmpty()) trigger.animName = null;
				break;
			case GESTURE:
				trigger.gestureName = in.readUTF();
				if (trigger.gestureName.isEmpty()) trigger.gestureName = null;
				break;
			case VANILLA_POSE:
				trigger.vanillaPoseName = in.readUTF();
				if (trigger.vanillaPoseName.isEmpty()) trigger.vanillaPoseName = null;
				break;
			case VALUE_RANGE:
				trigger.paramName = in.readUTF();
				if (trigger.paramName.isEmpty()) trigger.paramName = null;
				trigger.paramMin = in.readFloat();
				trigger.paramMax = in.readFloat();
				break;
			case GAME_EVENT:
				trigger.eventName = in.readUTF();
				if (trigger.eventName.isEmpty()) trigger.eventName = null;
				break;
			case LAYER_TOGGLE:
				trigger.layerParam = in.readVarInt();
				trigger.layerMask = in.readUnsignedByte();
				break;
			default:
				break;
		}

		return trigger;
	}

	@Override
	public String toString() {
		switch (type) {
			case ALWAYS: return "Always";
			case ANIMATION: return "Anim: " + animName;
			case GESTURE: return "Gesture: " + gestureName;
			case VANILLA_POSE: return "Pose: " + vanillaPoseName;
			case VALUE_RANGE: return paramName + " [" + paramMin + "-" + paramMax + "]";
			case GAME_EVENT: return "Event: " + eventName;
			case KEYFRAME: return "Keyframe: " + animName;
			case LAYER_TOGGLE: return "Layer: p" + layerParam + " m" + layerMask;
			default: return type.name();
		}
	}
}
