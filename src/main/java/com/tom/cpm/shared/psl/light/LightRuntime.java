package com.tom.cpm.shared.psl.light;

/**
 * Shared light runtime — computes current intensity based on trigger state and flicker.
 * Does NOT reference Minecraft types.
 */
public class LightRuntime {

	/**
	 * Compute the current light intensity for a light emitter.
	 * @param def          The light emitter definition
	 * @param tickCounter  Monotonically increasing tick counter (for flicker animation)
	 * @param triggerActive Whether the element's trigger is currently active
	 * @return Current intensity (0.0–1.0), or 0 if inactive
	 */
	public float getCurrentIntensity(LightEmitter def, long tickCounter, boolean triggerActive) {
		if (def == null || !triggerActive) return 0;

		float base = def.getIntensity();
		if (def.isFlicker()) {
			float flicker = (float) Math.sin(tickCounter * def.getFlickerSpeed() * 0.15);
			base += flicker * def.getFlickerAmount();
			base = Math.max(0, Math.min(1, base));
		}
		return base;
	}
}
