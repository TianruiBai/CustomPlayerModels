package com.tom.cpm.shared.editor.gui;

import java.util.function.Consumer;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.PopupPanel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.math.Box;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;

public class PslWindPopup extends PopupPanel {
	private final ParticleEmitter emitter;
	private final Runnable onChanged;

	private Spinner strengthSpinner;
	private Spinner dirX, dirY, dirZ;

	public PslWindPopup(Frame frame, ParticleEmitter emitter, Runnable onChanged) {
		super(frame.getGui());
		this.emitter = emitter;
		this.onChanged = onChanged;
		setBounds(new Box(0, 0, 300, 150));

		Label title = new Label(gui, gui.i18nFormat("label.cpm.psl.popup.windTitle"));
		title.setBounds(new Box(8, 8, 280, 12));
		addElement(title);

		// Wind strength
		Label strLbl = new Label(gui, gui.i18nFormat("label.cpm.psl.particle.windStrength"));
		strLbl.setBounds(new Box(8, 30, 100, 12));
		addElement(strLbl);
		strengthSpinner = mkSpinner(116, 28, emitter.getWindStrength(), v -> emitter.setWindStrength(v));
		Label strHint = new Label(gui, "(m/s)");
		strHint.setBounds(new Box(190, 30, 100, 12));
		addElement(strHint);

		// Direction X/Y/Z
		Vec3f dir = emitter.getWindDirection();
		addDirRow("label.cpm.psl.particle.windDir", 54, dir.x, dir.y, dir.z,
				v -> { Vec3f d = emitter.getWindDirection(); emitter.setWindDirection(new Vec3f(v, d.y, d.z)); },
				v -> { Vec3f d = emitter.getWindDirection(); emitter.setWindDirection(new Vec3f(d.x, v, d.z)); },
				v -> { Vec3f d = emitter.getWindDirection(); emitter.setWindDirection(new Vec3f(d.x, d.y, v)); });

		// Close
		Button close = new Button(gui, gui.i18nFormat("button.cpm.ok"), this::close);
		close.setBounds(new Box(110, 100, 80, 20));
		addElement(close);
	}

	private void addDirRow(String labelKey, int y, float x, float yv, float z,
			Consumer<Float> setX, Consumer<Float> setY, Consumer<Float> setZ) {
		Label lbl = new Label(gui, gui.i18nFormat(labelKey));
		lbl.setBounds(new Box(8, y + 3, 100, 12));
		addElement(lbl);

		Label xl = new Label(gui, "X");
		xl.setBounds(new Box(116, y + 3, 12, 12));
		addElement(xl);
		dirX = mkSpinner(120, y, x, setX);

		Label yl = new Label(gui, "Y");
		yl.setBounds(new Box(184, y + 3, 12, 12));
		addElement(yl);
		dirY = mkSpinner(188, y, yv, setY);

		Label zl = new Label(gui, "Z");
		zl.setBounds(new Box(252, y + 3, 12, 12));
		addElement(zl);
		dirZ = mkSpinner(256, y, z, setZ);
	}

	private Spinner mkSpinner(int x, int y, float value, Consumer<Float> setter) {
		Spinner s = new Spinner(gui);
		s.setDp(2);
		s.setValue(value);
		s.setBounds(new Box(x, y, 56, 18));
		s.addChangeListener(() -> {
			setter.accept(s.getValue());
			changed();
		});
		addElement(s);
		return s;
	}

	private void changed() {
		if (onChanged != null) onChanged.run();
	}
}
