package com.tom.cpm.client.psl;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import com.tom.cpm.shared.psl.sound.MidiEmitter;
import com.tom.cpm.shared.psl.sound.MidiRuntime;
import com.tom.cpm.shared.psl.sound.MidiRuntime.MidiNoteEvent;

/**
 * Client-side MIDI player for NeoForge 1.21.
 * Uses MidiRuntime for parsing/timing, plays notes via Minecraft's SoundManager.
 */
public class MidiPlayer {

	private final Map<Long, MidiRuntime> runtimes = new HashMap<>();
	private final Map<String, String> noteBlockSounds = new HashMap<>();
	private final Map<String, ResourceLocation> soundCache = new HashMap<>();

	public MidiPlayer() {
		// Default note block instrument mapping
		String[] programs = {"harp", "bell", "flute", "guitar", "bass", "harp", "chime", "bell",
			"flute", "didgeridoo", "bit", "iron_xylophone", "xylophone", "banjo", "basedrum", "pling"};
		for (int i = 0; i < programs.length; i++) {
			noteBlockSounds.put("noteblock:" + programs[i], "minecraft:block.note_block." + programs[i]);
		}
	}

	/**
	 * Load raw MIDI bytes and start playback.
	 */
	public void loadAndPlay(MidiEmitter def, byte[] midiData) {
		MidiRuntime rt = new MidiRuntime();
		if (rt.load(midiData)) {
			rt.play();
			runtimes.put(def.getId(), rt);
		}
	}

	/**
	 * Tick one MIDI emitter. Called each game tick.
	 */
	public void tick(MidiEmitter def, float dt) {
		MidiRuntime rt = runtimes.get(def.getId());
		if (rt == null || !rt.isPlaying()) return;

		List<MidiNoteEvent> events = rt.tick(dt, def.getTempo());
		if (events == null) return;

		// Handle looping
		if (rt.isFinished()) {
			if (def.isLoop()) {
				if (def.getLoopDelay() > 0) {
					rt.pause();
					// Phase 6: proper delay timer between loops
					rt.resetToStart();
					rt.play();
				} else {
					rt.resetToStart();
				}
			} else {
				rt.stop();
				return;
			}
		}

		if (events.isEmpty()) return;

		SoundSource source = switch (def.getCategory()) {
			case AMBIENT -> SoundSource.AMBIENT;
			case MASTER -> SoundSource.MASTER;
			default -> SoundSource.PLAYERS;
		};

		for (MidiNoteEvent e : events) {
			if (e.noteOn) {
				playNote(def, e, source);
			}
			// NOTE_OFF: fade is handled by noteFalloff — future enhancement
		}
	}

	private void playNote(MidiEmitter def, MidiNoteEvent e, SoundSource source) {
		// Resolve instrument
		String resource = def.getInstrumentMap().get(e.program);
		if (resource == null) {
			// Default mapping based on program range
			int range = e.program / 8;
			String[] defaultInst = {"harp", "bell", "flute", "guitar", "bass", "harp", "chime", "bell",
				"flute", "didgeridoo", "bit", "iron_xylophone", "xylophone", "banjo", "basedrum", "pling"};
			String inst = range < defaultInst.length ? defaultInst[range] : "harp";
			resource = "noteblock:" + inst;
		}

		ResourceLocation soundId;
		if (resource.startsWith("noteblock:")) {
			String mcPath = noteBlockSounds.getOrDefault(resource, "minecraft:block.note_block.harp");
			soundId = ResourceLocation.parse(mcPath);
		} else if (resource.startsWith("sounds/")) {
			soundId = soundCache.computeIfAbsent(resource,
				r -> ResourceLocation.fromNamespaceAndPath("cpm", r));
		} else {
			soundId = ResourceLocation.parse(resource);
		}

		// MIDI note → pitch: A4 (69) = 1.0, each semitone = 2^(1/12)
		float pitch = (float) Math.pow(2.0, (e.note + def.getTranspose() - 69) / 12.0);
		pitch = Math.max(0.5f, Math.min(2.0f, pitch));

		float vol = def.getVolume() * (e.velocity / 127f);

		SoundInstance inst = new SimpleSoundInstance(
			soundId, source, vol, pitch,
			net.minecraft.util.RandomSource.create(),
			false, 0, SoundInstance.Attenuation.NONE,
			0, 0, 0, false
		);

		Minecraft.getInstance().getSoundManager().play(inst);
	}

	/**
	 * Stop and unload a MIDI player.
	 */
	public void stop(long id) {
		MidiRuntime rt = runtimes.remove(id);
		if (rt != null) rt.stop();
	}

	public void clear() {
		runtimes.values().forEach(MidiRuntime::stop);
		runtimes.clear();
		soundCache.clear();
	}
}
