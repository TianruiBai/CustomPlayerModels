package com.tom.cpm.shared.editor.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.DropDownBox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.PopupPanel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.math.Box;
import com.tom.cpl.util.NamedElement;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;

public class PslRotationPopup extends PopupPanel {
	private final ParticleEmitter emitter;
	private final Runnable onChanged;

	private DropDownBox<NamedElement<ParticleEmitter.RotationMode>> modeDropDown;
	private List<NamedElement<ParticleEmitter.RotationMode>> modeOptions;
	private Spinner startX, startY, startZ;
	private Spinner endX, endY, endZ;
	private Spinner speedX, speedY, speedZ;
	private Checkbox randomStartCb;

	public PslRotationPopup(Frame frame, ParticleEmitter emitter, Runnable onChanged) {
		super(frame.getGui());
		this.emitter = emitter;
		this.onChanged = onChanged;
		setBounds(new Box(0, 0, 380, 220));

		Label title = new Label(gui, gui.i18nFormat("label.cpm.psl.popup.rotationTitle"));
		title.setBounds(new Box(8, 8, 360, 12));
		addElement(title);

		// Rotation mode dropdown
		Label modeLbl = new Label(gui, gui.i18nFormat("label.cpm.psl.particle.rotationMode"));
		modeLbl.setBounds(new Box(8, 30, 108, 12));
		addElement(modeLbl);

		modeOptions = new ArrayList<>();
		for (ParticleEmitter.RotationMode m : ParticleEmitter.RotationMode.VALUES)
			modeOptions.add(new NamedElement<>(m, Enum::name));
		modeDropDown = new DropDownBox<>(frame, modeOptions);
		modeDropDown.setBounds(new Box(124, 28, 236, 18));
		modeDropDown.setAction(this::applyMode);
		addElement(modeDropDown);

		// Start rotation XYZ
		int y = 56;
		addXyzRow("label.cpm.psl.particle.rotationStart", y, emitter.getRotationStartX(), emitter.getRotationStartY(), emitter.getRotationStartZ(),
				v -> emitter.setRotationStartX(v), v -> emitter.setRotationStartY(v), v -> emitter.setRotationStartZ(v));
		y += 24;
		addXyzRow("label.cpm.psl.particle.rotationEnd", y, emitter.getRotationEndX(), emitter.getRotationEndY(), emitter.getRotationEndZ(),
				v -> emitter.setRotationEndX(v), v -> emitter.setRotationEndY(v), v -> emitter.setRotationEndZ(v));
		y += 24;
		addXyzRow("label.cpm.psl.particle.rotationSpeed", y, emitter.getRotationSpeedX(), emitter.getRotationSpeedY(), emitter.getRotationSpeedZ(),
				v -> emitter.setRotationSpeedX(v), v -> emitter.setRotationSpeedY(v), v -> emitter.setRotationSpeedZ(v));

		// Random start
		randomStartCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.particle.randomRotationStart"));
		randomStartCb.setSelected(emitter.isRandomRotationStart());
		randomStartCb.setBounds(new Box(8, y + 28, 350, 16));
		randomStartCb.setAction(() -> {
			boolean selected = !randomStartCb.isSelected();
			randomStartCb.setSelected(selected);
			emitter.setRandomRotationStart(selected);
			changed();
		});
		addElement(randomStartCb);

		// Close button
		Button close = new Button(gui, gui.i18nFormat("button.cpm.ok"), this::close);
		close.setBounds(new Box(150, y + 52, 80, 20));
		addElement(close);

		// Init
		modeDropDown.setSelected(modeOptions.get(emitter.getRotationMode().ordinal()));
	}

	private void addXyzRow(String labelKey, int y, float x, float yv, float z,
			Consumer<Float> setX, Consumer<Float> setY, Consumer<Float> setZ) {
		Label lbl = new Label(gui, gui.i18nFormat(labelKey));
		lbl.setBounds(new Box(8, y + 3, 108, 12));
		addElement(lbl);

		Label xl = new Label(gui, "X");
		xl.setBounds(new Box(120, y + 3, 12, 12));
		addElement(xl);
		Spinner sx = mkSpinner(132, y, x, setX);
		if (labelKey.contains("Start")) startX = sx;
		else if (labelKey.contains("End")) endX = sx;
		else speedX = sx;

		Label yl = new Label(gui, "Y");
		yl.setBounds(new Box(202, y + 3, 12, 12));
		addElement(yl);
		Spinner sy = mkSpinner(214, y, yv, setY);
		if (labelKey.contains("Start")) startY = sy;
		else if (labelKey.contains("End")) endY = sy;
		else speedY = sy;

		Label zl = new Label(gui, "Z");
		zl.setBounds(new Box(284, y + 3, 12, 12));
		addElement(zl);
		Spinner sz = mkSpinner(296, y, z, setZ);
		if (labelKey.contains("Start")) startZ = sz;
		else if (labelKey.contains("End")) endZ = sz;
		else speedZ = sz;
	}

	private Spinner mkSpinner(int x, int y, float value, Consumer<Float> setter) {
		Spinner s = new Spinner(gui);
		s.setDp(1);
		s.setValue(value);
		s.setBounds(new Box(x, y, 62, 18));
		s.addChangeListener(() -> {
			setter.accept(s.getValue());
			changed();
		});
		addElement(s);
		return s;
	}

	private void applyMode() {
		NamedElement<ParticleEmitter.RotationMode> sel = modeDropDown.getSelected();
		if (sel != null) emitter.setRotationMode(sel.getElem());
		changed();
	}

	private void changed() {
		if (onChanged != null) onChanged.run();
	}
}
