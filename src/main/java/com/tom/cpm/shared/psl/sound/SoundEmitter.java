package com.tom.cpm.shared.psl.sound;

import java.io.IOException;

import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;

/**
 * Defines a sound effect emitter that plays an OGG file from the project's sounds/ folder.
 */
public class SoundEmitter extends PslElement {

	public enum Attenuation {
		NONE,
		LINEAR,
		INVERSE,
		;
		public static final Attenuation[] VALUES = values();
	}

	public enum SoundCategory {
		PLAYER,
		AMBIENT,
		MASTER,
		;
		public static final SoundCategory[] VALUES = values();
	}

	private String soundFile;
	private float volume = 1.0f;
	private float pitch = 1.0f;
	private float pitchVariation;
	private boolean loop;
	private float loopDelay;
	private Attenuation attenuation = Attenuation.LINEAR;
	private float maxDistance = 16.0f;
	private SoundCategory category = SoundCategory.PLAYER;
	private float cooldown;
	private boolean oneShot = true;

	public SoundEmitter() {
	}

	public SoundEmitter(long id, int elementId) {
		super(id, elementId);
	}

	@Override
	public PslElementType getType() {
		return PslElementType.SOUND;
	}

	@Override
	protected void writeData(IOHelper out) throws IOException {
		out.writeUTF(soundFile != null ? soundFile : "");
		out.writeFloat(volume);
		out.writeFloat(pitch);
		out.writeFloat(pitchVariation);
		out.writeBoolean(loop);
		out.writeFloat(loopDelay);
		out.writeVarInt(attenuation.ordinal());
		out.writeFloat(maxDistance);
		out.writeVarInt(category.ordinal());
		out.writeFloat(cooldown);
		out.writeBoolean(oneShot);
	}

	@Override
	protected void readData(IOHelper in) throws IOException {
		soundFile = in.readUTF();
		if (soundFile.isEmpty()) soundFile = null;
		volume = in.readFloat();
		pitch = in.readFloat();
		pitchVariation = in.readFloat();
		loop = in.readBoolean();
		loopDelay = in.readFloat();
		attenuation = Attenuation.VALUES[in.readVarInt()];
		maxDistance = in.readFloat();
		category = SoundCategory.VALUES[in.readVarInt()];
		cooldown = in.readFloat();
		oneShot = in.readBoolean();
	}

	// --- Getters/Setters ---

	public String getSoundFile() { return soundFile; }
	public void setSoundFile(String soundFile) { this.soundFile = soundFile; }
	public float getVolume() { return volume; }
	public void setVolume(float volume) { this.volume = Math.max(0, Math.min(1, volume)); }
	public float getPitch() { return pitch; }
	public void setPitch(float pitch) { this.pitch = Math.max(0.5f, Math.min(2, pitch)); }
	public float getPitchVariation() { return pitchVariation; }
	public void setPitchVariation(float pitchVariation) { this.pitchVariation = pitchVariation; }
	public boolean isLoop() { return loop; }
	public void setLoop(boolean loop) { this.loop = loop; }
	public float getLoopDelay() { return loopDelay; }
	public void setLoopDelay(float loopDelay) { this.loopDelay = loopDelay; }
	public Attenuation getAttenuation() { return attenuation; }
	public void setAttenuation(Attenuation attenuation) { this.attenuation = attenuation; }
	public float getMaxDistance() { return maxDistance; }
	public void setMaxDistance(float maxDistance) { this.maxDistance = maxDistance; }
	public SoundCategory getCategory() { return category; }
	public void setCategory(SoundCategory category) { this.category = category; }
	public float getCooldown() { return cooldown; }
	public void setCooldown(float cooldown) { this.cooldown = cooldown; }
	public boolean isOneShot() { return oneShot; }
	public void setOneShot(boolean oneShot) { this.oneShot = oneShot; }
}
