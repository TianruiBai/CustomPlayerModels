package com.tom.cpm.shared.psl;

import java.util.Map;

/**
 * Holds the current runtime state used for evaluating PslTrigger conditions.
 * Implemented by the platform-specific runtime to provide animation/gesture/pose state.
 */
public interface PslTriggerState {

	/** Get the name of the currently playing animation, or null. */
	String getCurrentAnimation();

	/** Check if a specific gesture is currently active. */
	boolean isGestureActive(String gestureName);

	/** Get the name of the current vanilla pose (e.g., "sneaking", "swimming"), or null. */
	String getCurrentVanillaPose();

	/** Check if a named parameter exists. */
	boolean hasParameter(String name);

	/** Get the value of a named parameter. */
	float getParameter(String name);

	/** Check if a game event is currently active. */
	boolean isEventActive(String eventName);

	/** Check if the current animation just triggered a keyframe this tick. */
	boolean isKeyframeTriggered();
}
