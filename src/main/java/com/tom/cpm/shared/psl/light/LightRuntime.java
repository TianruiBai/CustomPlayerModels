package com.tom.cpm.shared.psl.light;

/**
 * Shared light runtime — computes current intensity based on trigger state, flicker, and light type.
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

	/**
	 * Compute the light attenuation factor at a given distance from the emitter,
	 * based on the light type.
	 * @param def          The light emitter definition
	 * @param distance     Distance from light source in blocks
	 * @param angleRad     Angle from beam direction in radians (only used for SPOT)
	 * @return Attenuation factor (0.0–1.0)
	 */
	public float getAttenuation(LightEmitter def, float distance, float angleRad) {
		if (def == null) return 0;
		float radius = def.getRadius();
		if (distance >= radius) return 0;

		switch (def.getLightType()) {
			case POINT:
				// Inverse-square falloff, smooth at edges
				float d = distance / radius;
				return Math.max(0, 1 - d * d);
			case SPOT:
				// Cone: combine distance falloff with angular falloff
				float distFade = Math.max(0, 1 - (distance / radius));
				float halfAngle = (float) Math.toRadians(def.getSpotAngle() * 0.5f);
				float softness = def.getSpotSoftness();
				if (angleRad <= halfAngle * (1 - softness)) {
					return distFade;
				} else if (angleRad <= halfAngle) {
					float edgeT = (angleRad - halfAngle * (1 - softness)) / (halfAngle * softness);
					return distFade * (1 - edgeT);
				}
				return 0;
			case AREA:
				// Plane: soft linear falloff
				float dd = distance / radius;
				return Math.max(0, 1 - dd);
			default:
				return 0;
		}
	}
}
