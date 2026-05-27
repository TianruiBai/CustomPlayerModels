package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.elements.TextField;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.sound.MidiEmitter;
import com.tom.cpm.shared.psl.sound.SoundEmitter.SoundCategory;

/**
 * Properties panel for editing a MidiEmitter (MIDI music playback).
 */
public class MidiPropertiesPanel extends Panel {
	private Editor editor;
	private FlowLayout layout;

	private TextField midiFileField;
	private Spinner tempoSpinner, volumeSpinner, transposeSpinner;
	private Spinner loopDelaySpinner, categorySpinner, polyphonySpinner, noteFalloffSpinner;
	private Checkbox loopCb;

	public MidiPropertiesPanel(IGui gui, EditorGui e) {
		super(gui);
		this.editor = e.getEditor();
		setBounds(new Box(0, 0, 170, 220));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 3, 1);

		addLbl("label.cpm.psl.midi.file");
		midiFileField = mkTf(v -> get().setMidiFile(v));

		addLbl("label.cpm.psl.midi.tempo");
		tempoSpinner = addSpin(1.0f, 2, v -> get().setTempo(Math.max(0.5f, Math.min(2, v))));

		addLbl("label.cpm.psl.midi.volume");
		volumeSpinner = addSpin(0.6f, 2, v -> get().setVolume(v));

		addLbl("label.cpm.psl.midi.transpose");
		transposeSpinner = mkSpin(0); transposeSpinner.setDp(0);
		transposeSpinner.addChangeListener(() -> { if (get() != null) get().setTranspose((int) transposeSpinner.getValue()); });
		addElement(transposeSpinner);

		addLbl("label.cpm.psl.midi.loopDelay");
		loopDelaySpinner = addSpin(0, 2, v -> get().setLoopDelay(v));

		addLbl("label.cpm.psl.midi.category");
		categorySpinner = mkSpin(1); categorySpinner.setDp(0);
		categorySpinner.addChangeListener(() -> {
			int idx = (int) categorySpinner.getValue();
			if (idx >= 0 && idx < SoundCategory.VALUES.length) get().setCategory(SoundCategory.VALUES[idx]);
		});
		addElement(categorySpinner);

		addLbl("label.cpm.psl.midi.polyphony");
		polyphonySpinner = mkSpin(8); polyphonySpinner.setDp(0);
		polyphonySpinner.addChangeListener(() -> { if (get() != null) get().setPolyphony((int) polyphonySpinner.getValue()); });
		addElement(polyphonySpinner);

		addLbl("label.cpm.psl.midi.noteFalloff");
		noteFalloffSpinner = addSpin(0.3f, 2, v -> get().setNoteFalloff(v));

		loopCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.midi.loop"));
		loopCb.setBounds(new Box(2, 0, 164, 16));
		loopCb.setAction(() -> { if (get() != null) get().setLoop(loopCb.isSelected()); });
		addElement(loopCb);
	}

	private MidiEmitter get() {
		return editor.selectedPslElement instanceof MidiEmitter ? (MidiEmitter) editor.selectedPslElement : null;
	}

	public void refresh() {
		MidiEmitter m = get();
		boolean en = m != null;
		midiFileField.setEnabled(en); if (en) midiFileField.setText(m.getMidiFile() != null ? m.getMidiFile() : "");
		tempoSpinner.setEnabled(en); if (en) tempoSpinner.setValue(m.getTempo());
		volumeSpinner.setEnabled(en); if (en) volumeSpinner.setValue(m.getVolume());
		transposeSpinner.setEnabled(en); if (en) transposeSpinner.setValue(m.getTranspose());
		loopDelaySpinner.setEnabled(en); if (en) loopDelaySpinner.setValue(m.getLoopDelay());
		categorySpinner.setEnabled(en); if (en) categorySpinner.setValue(m.getCategory().ordinal());
		polyphonySpinner.setEnabled(en); if (en) polyphonySpinner.setValue(m.getPolyphony());
		noteFalloffSpinner.setEnabled(en); if (en) noteFalloffSpinner.setValue(m.getNoteFalloff());
		loopCb.setEnabled(en); if (en) loopCb.setSelected(m.isLoop());
	}

	private void addLbl(String key) { Label l = new Label(gui, gui.i18nFormat(key)); l.setBounds(new Box(2, 0, 164, 12)); addElement(l); }

	private TextField mkTf(java.util.function.Consumer<String> c) {
		TextField tf = new TextField(gui); tf.setBounds(new Box(2, 0, 164, 18));
		tf.setEventListener(() -> { if (get() != null) c.accept(tf.getText()); });
		addElement(tf); return tf;
	}

	private Spinner mkSpin(float def) { Spinner s = new Spinner(gui); s.setBounds(new Box(2, 0, 164, 18)); s.setDp(2); s.setValue(def); addElement(s); return s; }

	private Spinner addSpin(float def, int dp, java.util.function.Consumer<Float> c) {
		Spinner s = mkSpin(def); s.setDp(dp);
		s.addChangeListener(() -> { if (get() != null) c.accept(s.getValue()); });
		return s;
	}
}
