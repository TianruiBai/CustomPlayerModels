package com.tom.cpm.shared.psl;

import com.tom.cpm.shared.animation.AnimationEngine;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.parts.anim.menu.AbstractGestureButtonData;
import com.tom.cpm.shared.parts.anim.menu.CustomPoseGestureButtonData;
import com.tom.cpm.shared.animation.AnimationRegistry;

/**
 * Bridges Minecraft player animation/gesture/pose state into PslTrigger evaluation.
 * Extracts current animation name, gesture activity, vanilla pose, and parameter values.
 */
public class PslTriggerStateImpl implements PslTriggerState {

	private final AnimationState animState;
	private final AnimationRegistry registry;
	private final AnimationEngine engine;

	private PslTriggerStateImpl(AnimationState animState, AnimationRegistry registry, AnimationEngine engine) {
		this.animState = animState;
		this.registry = registry;
		this.engine = engine;
	}

	public static PslTriggerState from(AnimationState animState, AnimationRegistry registry, AnimationEngine engine) {
		if(animState == null || registry == null || engine == null) return null;
		return new PslTriggerStateImpl(animState, registry, engine);
	}

	@Override
	public String getCurrentAnimation() {
		if(animState.currentPose != null) {
			if(animState.currentPose instanceof com.tom.cpm.shared.animation.CustomPose) {
				return ((com.tom.cpm.shared.animation.CustomPose) animState.currentPose).getName();
			}
			return animState.currentPose.toString();
		}
		return null;
	}

	@Override
	public boolean isGestureActive(String gestureName) {
		if(gestureName == null || gestureName.isEmpty()) return false;
		for(AbstractGestureButtonData btn : registry.getNamedActions()) {
			if(gestureName.equals(btn.getName())) {
				if(btn instanceof CustomPoseGestureButtonData) {
					CustomPoseGestureButtonData g = (CustomPoseGestureButtonData) btn;
					if(!g.isPose()) {
						return engine.getGestureValue(g.id) != 0;
					}
				}
				// For other gesture types (toggle, etc.), check via engine
				String keybindId = btn.getKeybindId();
				if(keybindId != null) {
					// Try parameter-based lookup
					for(AbstractGestureButtonData b2 : registry.getNamedActions()) {
						if(b2 instanceof CustomPoseGestureButtonData && gestureName.equals(b2.getName())) {
							CustomPoseGestureButtonData g2 = (CustomPoseGestureButtonData) b2;
							return engine.getGestureValue(g2.id) != 0;
						}
					}
				}
				return false;
			}
		}
		// Fallback: try encoded gesture
		if(animState.encodedState != 0) {
			CustomPoseGestureButtonData gesture = registry.getGestureEncoded(animState.encodedState);
			if(gesture != null && gestureName.equals(gesture.getName())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String getCurrentVanillaPose() {
		if(animState.sneaking) return "sneaking";
		if(animState.swimming) return "swimming";
		if(animState.retroSwimming) return "swimming";
		if(animState.crawling) return "crawling";
		if(animState.sleeping) return "sleeping";
		if(animState.dying) return "dying";
		if(animState.riding) return "riding";
		if(animState.elytraFlying) return "elytraFlying";
		if(animState.sprinting) return "sprinting";
		if(animState.tridentSpin) return "tridentSpin";
		return "standing";
	}

	@Override
	public boolean hasParameter(String name) {
		if(name == null) return false;
		for(AbstractGestureButtonData btn : registry.getNamedActions()) {
			if(name.equals(btn.getName())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public float getParameter(String name) {
		if(name == null) return 0;
		for(AbstractGestureButtonData btn : registry.getNamedActions()) {
			if(name.equals(btn.getName()) && btn instanceof CustomPoseGestureButtonData) {
				CustomPoseGestureButtonData g = (CustomPoseGestureButtonData) btn;
				return engine.getGestureValue(g.id);
			}
		}
		return 0;
	}

	@Override
	public boolean isEventActive(String eventName) {
		// Game events not yet integrated into AnimationState
		return false;
	}

	@Override
	public boolean isKeyframeTriggered() {
		// Keyframe triggering not yet exposed from animation engine
		return false;
	}

	@Override
	public boolean getGestureParam(int param, int mask) {
		if (engine == null || param < 0) return false;
		byte val = engine.getGestureValue(param);
		return (val & mask) != 0;
	}
}
