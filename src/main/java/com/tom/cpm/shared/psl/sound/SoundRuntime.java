package com.tom.cpm.shared.psl.sound;

/**
 * Shared sound trigger logic — manages cooldown, one-shot state, and trigger evaluation.
 * Delegates actual audio playback to the platform-specific SoundPlayer.
 */
public class SoundRuntime {

	private boolean hasPlayed;
	private long lastTriggerTick;

	/**
	 * Determine whether the sound should play this tick.
	 * @param def          The sound emitter definition
	 * @param currentTick  Monotonically increasing tick counter
	 * @param triggerActive Whether the element's trigger is currently active
	 * @return true if the sound should fire now
	 */
	public boolean shouldPlay(SoundEmitter def, long currentTick, boolean triggerActive) {
		if (def == null || !triggerActive) return false;

		// One-shot: never play again after first trigger
		if (def.isOneShot() && hasPlayed) return false;

		// Cooldown: check minimum interval (cooldown in seconds → ticks)
		long cooldownTicks = (long) (def.getCooldown() * 20);
		if (cooldownTicks > 0 && currentTick - lastTriggerTick < cooldownTicks) {
			return false;
		}

		return true;
	}

	/**
	 * Mark the sound as having triggered this tick.
	 */
	public void markTriggered(long currentTick) {
		hasPlayed = true;
		lastTriggerTick = currentTick;
	}

	/**
	 * Reset one-shot state (e.g., when trigger deactivates and reactivates).
	 */
	public void reset() {
		hasPlayed = false;
		lastTriggerTick = 0;
	}
}
