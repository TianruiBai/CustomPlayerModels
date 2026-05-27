package com.tom.cpm.shared.psl.sound;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;

/**
 * Defines a MIDI player that plays sequenced musical notes from a .mid file.
 * Instruments are mapped to either Minecraft note block sounds or custom OGG samples.
 */
public class MidiEmitter extends PslElement {

	private String midiFile;
	private Map<Integer, String> instrumentMap = new HashMap<>();
	private float tempo = 1.0f;
	private float volume = 0.6f;
	private int transpose;
	private boolean loop;
	private float loopDelay;
	private SoundEmitter.SoundCategory category = SoundEmitter.SoundCategory.AMBIENT;
	private int polyphony = 8;
	private float noteFalloff = 0.3f;

	public MidiEmitter() {
	}

	public MidiEmitter(long id) {
		super(id, -1); // MIDI has no element attachment
	}

	@Override
	public PslElementType getType() {
		return PslElementType.MIDI;
	}

	@Override
	protected void writeData(IOHelper out) throws IOException {
		out.writeUTF(midiFile != null ? midiFile : "");
		out.writeVarInt(instrumentMap.size());
		for (Map.Entry<Integer, String> e : instrumentMap.entrySet()) {
			out.writeVarInt(e.getKey());
			out.writeUTF(e.getValue());
		}
		out.writeFloat(tempo);
		out.writeFloat(volume);
		out.writeVarInt(transpose);
		out.writeBoolean(loop);
		out.writeFloat(loopDelay);
		out.writeVarInt(category.ordinal());
		out.writeVarInt(polyphony);
		out.writeFloat(noteFalloff);
	}

	@Override
	protected void readData(IOHelper in) throws IOException {
		midiFile = in.readUTF();
		if (midiFile.isEmpty()) midiFile = null;
		int mapSize = in.readVarInt();
		instrumentMap.clear();
		for (int i = 0; i < mapSize; i++) {
			int program = in.readVarInt();
			String resource = in.readUTF();
			instrumentMap.put(program, resource);
		}
		tempo = in.readFloat();
		volume = in.readFloat();
		transpose = in.readVarInt();
		loop = in.readBoolean();
		loopDelay = in.readFloat();
		category = SoundEmitter.SoundCategory.VALUES[in.readVarInt()];
		polyphony = in.readVarInt();
		noteFalloff = in.readFloat();
	}

	// --- Getters/Setters ---

	public String getMidiFile() { return midiFile; }
	public void setMidiFile(String midiFile) { this.midiFile = midiFile; }
	public Map<Integer, String> getInstrumentMap() { return instrumentMap; }
	public void setInstrumentMap(Map<Integer, String> instrumentMap) { this.instrumentMap = instrumentMap; }
	public float getTempo() { return tempo; }
	public void setTempo(float tempo) { this.tempo = Math.max(0.5f, Math.min(2, tempo)); }
	public float getVolume() { return volume; }
	public void setVolume(float volume) { this.volume = Math.max(0, Math.min(1, volume)); }
	public int getTranspose() { return transpose; }
	public void setTranspose(int transpose) { this.transpose = Math.max(-24, Math.min(24, transpose)); }
	public boolean isLoop() { return loop; }
	public void setLoop(boolean loop) { this.loop = loop; }
	public float getLoopDelay() { return loopDelay; }
	public void setLoopDelay(float loopDelay) { this.loopDelay = loopDelay; }
	public SoundEmitter.SoundCategory getCategory() { return category; }
	public void setCategory(SoundEmitter.SoundCategory category) { this.category = category; }
	public int getPolyphony() { return polyphony; }
	public void setPolyphony(int polyphony) { this.polyphony = Math.max(1, Math.min(32, polyphony)); }
	public float getNoteFalloff() { return noteFalloff; }
	public void setNoteFalloff(float noteFalloff) { this.noteFalloff = noteFalloff; }
}
