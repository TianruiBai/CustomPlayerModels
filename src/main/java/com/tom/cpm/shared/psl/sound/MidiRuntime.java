package com.tom.cpm.shared.psl.sound;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Shared MIDI runtime — parses SMF Type 0/1 files and manages playback state.
 * Does NOT reference Minecraft types. Note scheduling is forwarded to IPslRuntime.
 */
public class MidiRuntime {

	/** Parsed MIDI sequence data (ticks → events) */
	public static class MidiTrack {
		public List<MidiNoteEvent> events = new ArrayList<>();
		public int resolution; // ticks per quarter note
		public long totalTicks;
	}

	public static class MidiNoteEvent {
		public long tick;
		public boolean noteOn;
		public int channel;
		public int note;       // MIDI note number (0–127)
		public int velocity;   // 0–127
		public int program;    // instrument program (0–127)
	}

	private MidiTrack track;
	private long playbackTick;
	private float timer;       // seconds accumulator
	private boolean playing;

	/**
	 * Parse a MIDI file from raw bytes.
	 */
	public boolean load(byte[] data) {
		try {
			Sequence seq = MidiSystem.getSequence(new ByteArrayInputStream(data));
			track = new MidiTrack();
			track.resolution = seq.getResolution();

			// Flatten all tracks into one event list, sorted by tick
			List<MidiNoteEvent> allEvents = new ArrayList<>();
			int[] currentProgram = new int[16]; // per-channel program

			for (Track t : seq.getTracks()) {
				for (int i = 0; i < t.size(); i++) {
					MidiEvent me = t.get(i);
					MidiMessage msg = me.getMessage();
					long tick = me.getTick();

					if (msg instanceof ShortMessage) {
						ShortMessage sm = (ShortMessage) msg;
						int cmd = sm.getCommand();
						int ch = sm.getChannel();
						int d1 = sm.getData1();
						int d2 = sm.getData2();

						if (cmd == ShortMessage.NOTE_ON && d2 > 0) {
							MidiNoteEvent e = new MidiNoteEvent();
							e.tick = tick; e.noteOn = true; e.channel = ch;
							e.note = d1; e.velocity = d2; e.program = currentProgram[ch];
							allEvents.add(e);
						} else if (cmd == ShortMessage.NOTE_OFF || (cmd == ShortMessage.NOTE_ON && d2 == 0)) {
							MidiNoteEvent e = new MidiNoteEvent();
							e.tick = tick; e.noteOn = false; e.channel = ch;
							e.note = d1; e.velocity = 0; e.program = currentProgram[ch];
							allEvents.add(e);
						} else if (cmd == ShortMessage.PROGRAM_CHANGE) {
							currentProgram[ch] = d1;
						}
					}
				}
			}

			allEvents.sort((a, b) -> Long.compare(a.tick, b.tick));
			track.events = allEvents;
			track.totalTicks = seq.getMicrosecondLength() > 0 ?
				seq.getTickLength() : allEvents.isEmpty() ? 0 : allEvents.get(allEvents.size() - 1).tick + 480;

			return true;
		} catch (InvalidMidiDataException | IOException e) {
			track = null;
			return false;
		}
	}

	/**
	 * Tick the MIDI playback. Returns a list of note events to play this tick.
	 * @param dt       Delta time in seconds
	 * @param tempo    Tempo multiplier (1.0 = normal)
	 * @return List of note-on/note-off events at the current position, or null if stopped
	 */
	public List<MidiNoteEvent> tick(float dt, float tempo) {
		if (track == null || track.events.isEmpty() || !playing) return null;

		// Convert ticks to seconds: tempo in BPM = 120 * tempo
		float bpm = 120 * tempo;
		float secondsPerTick = 60f / (bpm * track.resolution);
		timer += dt;

		List<MidiNoteEvent> result = new ArrayList<>();
		while (timer >= secondsPerTick && playbackTick <= track.totalTicks) {
			timer -= secondsPerTick;

			// Gather all events at this tick
			for (MidiNoteEvent e : track.events) {
				if (e.tick == playbackTick) {
					result.add(e);
				}
			}

			playbackTick++;
		}

		return result;
	}

	/**
	 * Start/resume playback.
	 */
	public void play() {
		playing = true;
	}

	/**
	 * Pause playback (keeps position).
	 */
	public void pause() {
		playing = false;
	}

	/**
	 * Stop and reset to beginning.
	 */
	public void stop() {
		playing = false;
		playbackTick = 0;
		timer = 0;
	}

	/**
	 * Check if the MIDI has reached its end (non-looping).
	 */
	public boolean isFinished() {
		return track == null || playbackTick > track.totalTicks;
	}

	/**
	 * Reset to beginning (for looping).
	 */
	public void resetToStart() {
		playbackTick = 0;
		timer = 0;
	}

	public long getPlaybackTick() { return playbackTick; }
	public boolean isPlaying() { return playing; }
	public boolean isLoaded() { return track != null; }
}
