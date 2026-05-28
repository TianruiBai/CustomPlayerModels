package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.PopupPanel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;

public class PslAnimPopup extends PopupPanel {
	private final ParticleEmitter emitter;
	private final Runnable onChanged;

	private Spinner frameCountSpin, frameTimeSpin;
	private Checkbox horizCb;

	public PslAnimPopup(Frame frame, ParticleEmitter emitter, Runnable onChanged) {
		super(frame.getGui());
		this.emitter = emitter;
		this.onChanged = onChanged;
		setBounds(new Box(0, 0, 320, 150));

		Label title = new Label(gui, gui.i18nFormat("label.cpm.psl.popup.animTitle"));
		title.setBounds(new Box(8, 8, 300, 12));
		addElement(title);

		// Frame count
		Label fcLbl = new Label(gui, gui.i18nFormat("label.cpm.psl.particle.frameCount"));
		fcLbl.setBounds(new Box(8, 32, 108, 12));
		addElement(fcLbl);
		frameCountSpin = new Spinner(gui);
		frameCountSpin.setDp(0);
		frameCountSpin.setValue(emitter.getFrameCount());
		frameCountSpin.setBounds(new Box(124, 30, 180, 18));
		frameCountSpin.addChangeListener(() -> {
			emitter.setFrameCount((int) frameCountSpin.getValue());
			changed();
		});
		addElement(frameCountSpin);

		// Frame time (ms)
		Label ftLbl = new Label(gui, gui.i18nFormat("label.cpm.psl.particle.frameTimeMs"));
		ftLbl.setBounds(new Box(8, 58, 108, 12));
		addElement(ftLbl);
		frameTimeSpin = new Spinner(gui);
		frameTimeSpin.setDp(0);
		frameTimeSpin.setValue(emitter.getFrameTimeMs());
		frameTimeSpin.setBounds(new Box(124, 56, 180, 18));
		frameTimeSpin.addChangeListener(() -> {
			emitter.setFrameTimeMs((int) frameTimeSpin.getValue());
			changed();
		});
		addElement(frameTimeSpin);

		// Horizontal layout
		horizCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.particle.animHorizontal"));
		horizCb.setSelected(emitter.isAnimHorizontal());
		horizCb.setBounds(new Box(8, 84, 300, 16));
		horizCb.setAction(() -> {
			boolean selected = !horizCb.isSelected();
			horizCb.setSelected(selected);
			emitter.setAnimHorizontal(selected);
			changed();
		});
		addElement(horizCb);

		Button close = new Button(gui, gui.i18nFormat("button.cpm.ok"), this::close);
		close.setBounds(new Box(110, 114, 100, 20));
		addElement(close);
	}

	private void changed() {
		if(onChanged != null)onChanged.run();
	}
}
