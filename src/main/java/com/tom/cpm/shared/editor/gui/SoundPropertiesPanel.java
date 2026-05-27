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
import com.tom.cpm.shared.psl.sound.SoundEmitter;
import com.tom.cpm.shared.psl.sound.SoundEmitter.Attenuation;
import com.tom.cpm.shared.psl.sound.SoundEmitter.SoundCategory;

/**
 * Properties panel for editing a SoundEmitter (OGG sound effects).
 */
public class SoundPropertiesPanel extends Panel {
	private Editor editor;
	private FlowLayout layout;

	private TextField soundFileField;
	private Spinner volumeSpinner, pitchSpinner, pitchVarSpinner;
	private Spinner loopDelaySpinner, attenuationSpinner, maxDistSpinner;
	private Spinner categorySpinner, cooldownSpinner;
	private Checkbox loopCb, oneShotCb;

	public SoundPropertiesPanel(IGui gui, EditorGui e) {
		super(gui);
		this.editor = e.getEditor();
		setBounds(new Box(0, 0, 170, 260));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 3, 1);

		addLbl("label.cpm.psl.sound.file");
		soundFileField = mkTf(v -> get().setSoundFile(v));

		addLbl("label.cpm.psl.sound.volume");
		volumeSpinner = addSpin(1.0f, 2, v -> get().setVolume(v));

		addLbl("label.cpm.psl.sound.pitch");
		pitchSpinner = addSpin(1.0f, 2, v -> get().setPitch(Math.max(0.5f, Math.min(2, v))));

		addLbl("label.cpm.psl.sound.pitchVar");
		pitchVarSpinner = addSpin(0, 2, v -> get().setPitchVariation(v));

		addLbl("label.cpm.psl.sound.loopDelay");
		loopDelaySpinner = addSpin(0, 2, v -> get().setLoopDelay(v));

		addLbl("label.cpm.psl.sound.attenuation");
		attenuationSpinner = mkSpin(1); attenuationSpinner.setDp(0);
		attenuationSpinner.addChangeListener(() -> {
			int idx = (int) attenuationSpinner.getValue();
			if (idx >= 0 && idx < Attenuation.VALUES.length) get().setAttenuation(Attenuation.VALUES[idx]);
		});
		addElement(attenuationSpinner);

		addLbl("label.cpm.psl.sound.maxDist");
		maxDistSpinner = addSpin(16, 1, v -> get().setMaxDistance(v));

		addLbl("label.cpm.psl.sound.category");
		categorySpinner = mkSpin(0); categorySpinner.setDp(0);
		categorySpinner.addChangeListener(() -> {
			int idx = (int) categorySpinner.getValue();
			if (idx >= 0 && idx < SoundCategory.VALUES.length) get().setCategory(SoundCategory.VALUES[idx]);
		});
		addElement(categorySpinner);

		addLbl("label.cpm.psl.sound.cooldown");
		cooldownSpinner = addSpin(0, 2, v -> get().setCooldown(v));

		loopCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.sound.loop"));
		loopCb.setBounds(new Box(2, 0, 80, 16));
		loopCb.setAction(() -> { if (get() != null) get().setLoop(loopCb.isSelected()); });
		addElement(loopCb);

		oneShotCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.sound.oneShot"));
		oneShotCb.setBounds(new Box(84, 0, 80, 16));
		oneShotCb.setAction(() -> { if (get() != null) get().setOneShot(oneShotCb.isSelected()); });
		addElement(oneShotCb);
	}

	private SoundEmitter get() {
		return editor.selectedPslElement instanceof SoundEmitter ? (SoundEmitter) editor.selectedPslElement : null;
	}

	public void refresh() {
		SoundEmitter s = get();
		boolean en = s != null;
		soundFileField.setEnabled(en); if (en) soundFileField.setText(s.getSoundFile() != null ? s.getSoundFile() : "");
		volumeSpinner.setEnabled(en); if (en) volumeSpinner.setValue(s.getVolume());
		pitchSpinner.setEnabled(en); if (en) pitchSpinner.setValue(s.getPitch());
		pitchVarSpinner.setEnabled(en); if (en) pitchVarSpinner.setValue(s.getPitchVariation());
		loopDelaySpinner.setEnabled(en); if (en) loopDelaySpinner.setValue(s.getLoopDelay());
		attenuationSpinner.setEnabled(en); if (en) attenuationSpinner.setValue(s.getAttenuation().ordinal());
		maxDistSpinner.setEnabled(en); if (en) maxDistSpinner.setValue(s.getMaxDistance());
		categorySpinner.setEnabled(en); if (en) categorySpinner.setValue(s.getCategory().ordinal());
		cooldownSpinner.setEnabled(en); if (en) cooldownSpinner.setValue(s.getCooldown());
		loopCb.setEnabled(en); if (en) loopCb.setSelected(s.isLoop());
		oneShotCb.setEnabled(en); if (en) oneShotCb.setSelected(s.isOneShot());
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
