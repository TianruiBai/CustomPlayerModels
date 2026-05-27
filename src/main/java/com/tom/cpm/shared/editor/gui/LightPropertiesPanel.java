package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.light.LightEmitter;

/**
 * Properties panel for editing a LightEmitter.
 * Color, intensity, radius, flicker, dynamic toggle, cast shadows.
 */
public class LightPropertiesPanel extends Panel {
	private Editor editor;
	private FlowLayout layout;

	private Spinner colorR, colorG, colorB;
	private Spinner intensitySpinner, radiusSpinner;
	private Spinner flickerSpeedSpinner, flickerAmountSpinner;
	private Checkbox flickerCb, dynamicCb, shadowsCb;

	public LightPropertiesPanel(IGui gui, EditorGui e) {
		super(gui);
		this.editor = e.getEditor();
		setBounds(new Box(0, 0, 170, 200));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 3, 1);

		addLbl("label.cpm.psl.light.color");
		Panel cRow = new Panel(gui);
		cRow.setBounds(new Box(0, 0, 170, 18));
		colorR = smallSpin(2, 255, v -> setColor());
		colorG = smallSpin(42, 255, v -> setColor());
		colorB = smallSpin(82, 255, v -> setColor());
		cRow.addElement(colorR); cRow.addElement(colorG); cRow.addElement(colorB);
		addElement(cRow);

		addLbl("label.cpm.psl.light.intensity");
		intensitySpinner = addSpin(0.7f, 2, v -> get().setIntensity(v));

		addLbl("label.cpm.psl.light.radius");
		radiusSpinner = addSpin(3, 1, v -> get().setRadius(v));

		flickerCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.light.flicker"));
		flickerCb.setBounds(new Box(2, 0, 164, 16));
		flickerCb.setAction(() -> { if (get() != null) get().setFlicker(flickerCb.isSelected()); });
		addElement(flickerCb);

		addLbl("label.cpm.psl.light.flickerSpeed");
		flickerSpeedSpinner = addSpin(1, 2, v -> get().setFlickerSpeed(v));
		addLbl("label.cpm.psl.light.flickerAmount");
		flickerAmountSpinner = addSpin(0.1f, 2, v -> get().setFlickerAmount(v));

		dynamicCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.light.dynamic"));
		dynamicCb.setBounds(new Box(2, 0, 164, 16));
		dynamicCb.setAction(() -> { if (get() != null) get().setDynamic(dynamicCb.isSelected()); });
		addElement(dynamicCb);

		shadowsCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.light.shadows"));
		shadowsCb.setBounds(new Box(2, 0, 164, 16));
		shadowsCb.setAction(() -> { if (get() != null) get().setCastShadows(shadowsCb.isSelected()); });
		addElement(shadowsCb);
	}

	private LightEmitter get() {
		return editor.selectedPslElement instanceof LightEmitter ? (LightEmitter) editor.selectedPslElement : null;
	}

	private void setColor() {
		LightEmitter l = get();
		if (l == null) return;
		int r = Math.max(0, Math.min(255, (int) colorR.getValue()));
		int g = Math.max(0, Math.min(255, (int) colorG.getValue()));
		int b = Math.max(0, Math.min(255, (int) colorB.getValue()));
		l.setColor((r << 16) | (g << 8) | b);
	}

	public void refresh() {
		LightEmitter l = get();
		boolean en = l != null;
		colorR.setEnabled(en); if (en) colorR.setValue((l.getColor() >> 16) & 0xFF);
		colorG.setEnabled(en); if (en) colorG.setValue((l.getColor() >> 8) & 0xFF);
		colorB.setEnabled(en); if (en) colorB.setValue(l.getColor() & 0xFF);
		intensitySpinner.setEnabled(en); if (en) intensitySpinner.setValue(l.getIntensity());
		radiusSpinner.setEnabled(en); if (en) radiusSpinner.setValue(l.getRadius());
		flickerCb.setEnabled(en); if (en) flickerCb.setSelected(l.isFlicker());
		flickerSpeedSpinner.setEnabled(en); if (en) flickerSpeedSpinner.setValue(l.getFlickerSpeed());
		flickerAmountSpinner.setEnabled(en); if (en) flickerAmountSpinner.setValue(l.getFlickerAmount());
		dynamicCb.setEnabled(en); if (en) dynamicCb.setSelected(l.isDynamic());
		shadowsCb.setEnabled(en); if (en) shadowsCb.setSelected(l.isCastShadows());
	}

	private void addLbl(String key) { Label l = new Label(gui, gui.i18nFormat(key)); l.setBounds(new Box(2, 0, 164, 12)); addElement(l); }

	private Spinner addSpin(float def, int dp, java.util.function.Consumer<Float> c) {
		Spinner s = new Spinner(gui); s.setBounds(new Box(2, 0, 164, 18)); s.setDp(dp); s.setValue(def);
		s.addChangeListener(() -> { if (get() != null) c.accept(s.getValue()); });
		addElement(s); return s;
	}

	private Spinner smallSpin(int x, float def, java.util.function.Consumer<Float> c) {
		Spinner s = new Spinner(gui); s.setBounds(new Box(x, 0, 38, 18)); s.setDp(0); s.setValue(def);
		s.addChangeListener(() -> { if (get() != null) c.accept(s.getValue()); });
		return s;
	}
}
