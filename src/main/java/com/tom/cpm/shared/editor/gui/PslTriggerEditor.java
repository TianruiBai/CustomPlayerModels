package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.elements.TextField;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslTrigger;
import com.tom.cpm.shared.psl.PslTrigger.TriggerType;

/**
 * Trigger editor — all fields created once, visibility toggled per type.
 */
public class PslTriggerEditor extends Panel {
	private Editor editor;
	private FlowLayout layout;

	private Spinner typeSpinner;
	private TextField animField, gestureField, poseField, paramField, eventField;
	private Spinner paramMinSpinner, paramMaxSpinner;
	private Label animLbl, gestureLbl, poseLbl, paramLbl, eventLbl, paramMinLbl, paramMaxLbl;

	public PslTriggerEditor(IGui gui, EditorGui e) {
		super(gui);
		this.editor = e.getEditor();
		setBounds(new Box(0, 0, 170, 160));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 3, 1);

		Label tLbl = new Label(gui, gui.i18nFormat("label.cpm.psl.trigger.type"));
		tLbl.setBounds(new Box(2, 0, 164, 12));
		addElement(tLbl);

		typeSpinner = mkSpinner(0, v -> chType(v.intValue()));
		typeSpinner.setDp(0);

		animLbl = mkLbl("label.cpm.psl.trigger.animName");
		animField = mkTf(v -> trg().setAnimName(v));

		gestureLbl = mkLbl("label.cpm.psl.trigger.gesture");
		gestureField = mkTf(v -> trg().setGestureName(v));

		poseLbl = mkLbl("label.cpm.psl.trigger.pose");
		poseField = mkTf(v -> trg().setVanillaPoseName(v));

		paramLbl = mkLbl("label.cpm.psl.trigger.param");
		paramField = mkTf(v -> trg().setParamName(v));
		paramMinLbl = mkLbl("label.cpm.psl.trigger.paramMin");
		paramMinSpinner = mkSpinner(0, v -> trg().setParamMin(v));
		paramMaxLbl = mkLbl("label.cpm.psl.trigger.paramMax");
		paramMaxSpinner = mkSpinner(0, v -> trg().setParamMax(v));

		eventLbl = mkLbl("label.cpm.psl.trigger.event");
		eventField = mkTf(v -> trg().setEventName(v));
	}

	private PslTrigger trg() { return editor.selectedPslElement != null ? editor.selectedPslElement.getTrigger() : null; }

	private void chType(int idx) {
		PslTrigger t = trg();
		if (t == null || idx < 0 || idx >= TriggerType.VALUES.length) return;
		t.setType(TriggerType.VALUES[idx]);
		editor.markDirty();
		refresh();
	}

	private Label mkLbl(String key) {
		Label l = new Label(gui, gui.i18nFormat(key));
		l.setBounds(new Box(2, 0, 164, 10));
		addElement(l);
		return l;
	}

	private TextField mkTf(java.util.function.Consumer<String> c) {
		TextField tf = new TextField(gui);
		tf.setBounds(new Box(2, 0, 164, 18));
		tf.setEventListener(() -> { c.accept(tf.getText()); editor.markDirty(); });
		addElement(tf);
		return tf;
	}

	private Spinner mkSpinner(float def, java.util.function.Consumer<Float> c) {
		Spinner s = new Spinner(gui);
		s.setBounds(new Box(2, 0, 164, 18));
		s.setDp(2);
		s.setValue(def);
		s.addChangeListener(() -> { c.accept(s.getValue()); editor.markDirty(); });
		addElement(s);
		return s;
	}

	public void refresh() {
		PslElement el = editor.selectedPslElement;
		if (el == null) { setVisible(false); return; }
		setVisible(true);

		PslTrigger t = el.getTrigger();
		typeSpinner.setValue(t.getType().ordinal());

		allOff();
		switch (t.getType()) {
			case ANIMATION: case KEYFRAME:
				animLbl.setVisible(true); animField.setVisible(true);
				animField.setText(t.getAnimName() != null ? t.getAnimName() : "");
				break;
			case GESTURE:
				gestureLbl.setVisible(true); gestureField.setVisible(true);
				gestureField.setText(t.getGestureName() != null ? t.getGestureName() : "");
				break;
			case VANILLA_POSE:
				poseLbl.setVisible(true); poseField.setVisible(true);
				poseField.setText(t.getVanillaPoseName() != null ? t.getVanillaPoseName() : "");
				break;
			case VALUE_RANGE:
				paramLbl.setVisible(true); paramField.setVisible(true);
				paramField.setText(t.getParamName() != null ? t.getParamName() : "");
				paramMinLbl.setVisible(true); paramMinSpinner.setVisible(true);
				paramMinSpinner.setValue(t.getParamMin());
				paramMaxLbl.setVisible(true); paramMaxSpinner.setVisible(true);
				paramMaxSpinner.setValue(t.getParamMax());
				break;
			case GAME_EVENT:
				eventLbl.setVisible(true); eventField.setVisible(true);
				eventField.setText(t.getEventName() != null ? t.getEventName() : "");
				break;
			default: break;
		}
		layout.reflow();
	}

	private void allOff() {
		animLbl.setVisible(false); animField.setVisible(false);
		gestureLbl.setVisible(false); gestureField.setVisible(false);
		poseLbl.setVisible(false); poseField.setVisible(false);
		paramLbl.setVisible(false); paramField.setVisible(false);
		paramMinLbl.setVisible(false); paramMinSpinner.setVisible(false);
		paramMaxLbl.setVisible(false); paramMaxSpinner.setVisible(false);
		eventLbl.setVisible(false); eventField.setVisible(false);
	}
}
