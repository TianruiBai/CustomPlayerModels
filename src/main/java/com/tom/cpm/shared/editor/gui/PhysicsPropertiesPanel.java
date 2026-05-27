package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.physics.PhysicsBone;
import com.tom.cpm.shared.psl.physics.PhysicsBone.SimType;

/**
 * Properties panel for editing a PhysicsBone.
 * Shows gravity, damping, stiffness, mass, wind, collision, angular limits.
 */
public class PhysicsPropertiesPanel extends Panel {
	private Editor editor;
	private FlowLayout layout;

	private Spinner parentIdSpinner, simTypeSpinner;
	private Spinner gravitySpinner, dampingSpinner, stiffnessSpinner, massSpinner;
	private Spinner windSpinner, collisionSpinner, maxStretchSpinner;
	private Spinner limitX, limitY, limitZ;
	private Spinner iterationsSpinner;
	private Checkbox inheritCb;

	public PhysicsPropertiesPanel(IGui gui, EditorGui e) {
		super(gui);
		this.editor = e.getEditor();
		setBounds(new Box(0, 0, 170, 300));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 3, 1);

		addLabel("label.cpm.psl.physics.parentId");
		parentIdSpinner = addSpinInt(0, v -> get().setParentElementId(v));

		addLabel("label.cpm.psl.physics.simType");
		simTypeSpinner = mkSpin(0); simTypeSpinner.setDp(0);
		simTypeSpinner.addChangeListener(() -> {
			int idx = (int) simTypeSpinner.getValue();
			if (idx >= 0 && idx < SimType.VALUES.length) get().setSimType(SimType.VALUES[idx]);
		});
		addElement(simTypeSpinner);

		addLabel("label.cpm.psl.physics.gravity");
		gravitySpinner = addSpinFloat(0, v -> get().setGravity(v));

		addLabel("label.cpm.psl.physics.damping");
		dampingSpinner = addSpinFloat(0, v -> get().setDamping(Math.max(0, Math.min(1, v))));

		addLabel("label.cpm.psl.physics.stiffness");
		stiffnessSpinner = addSpinFloat(0, v -> get().setStiffness(Math.max(0, Math.min(1, v))));

		addLabel("label.cpm.psl.physics.mass");
		massSpinner = addSpinFloat(0, v -> get().setMass(v));

		addLabel("label.cpm.psl.physics.wind");
		windSpinner = addSpinFloat(0, v -> get().setWindInfluence(Math.max(0, Math.min(1, v))));

		addLabel("label.cpm.psl.physics.collision");
		collisionSpinner = addSpinFloat(0, v -> get().setCollisionRadius(v));

		addLabel("label.cpm.psl.physics.maxStretch");
		maxStretchSpinner = addSpinFloat(1, v -> get().setMaxStretch(v));

		addLabel("label.cpm.psl.physics.limits");
		Panel limRow = new Panel(gui);
		limRow.setBounds(new Box(0, 0, 170, 18));
		limitX = smallSpin(2, 0, v -> get().setLimitAngleX(v));
		limitY = smallSpin(42, 0, v -> get().setLimitAngleY(v));
		limitZ = smallSpin(82, 0, v -> get().setLimitAngleZ(v));
		limRow.addElement(limitX); limRow.addElement(limitY); limRow.addElement(limitZ);
		addElement(limRow);

		addLabel("label.cpm.psl.physics.iterations");
		iterationsSpinner = addSpinInt(3, v -> get().setIterations(v));

		inheritCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.physics.inherit"));
		inheritCb.setBounds(new Box(2, 0, 164, 16));
		inheritCb.setAction(() -> { if (get() != null) get().setInheritAnimation(inheritCb.isSelected()); });
		addElement(inheritCb);
	}

	private PhysicsBone get() {
		if (editor.selectedPslElement instanceof PhysicsBone)
			return (PhysicsBone) editor.selectedPslElement;
		return null;
	}

	public void refresh() {
		PhysicsBone b = get();
		boolean en = b != null;
		parentIdSpinner.setEnabled(en); if (en) parentIdSpinner.setValue(b.getParentElementId());
		simTypeSpinner.setEnabled(en); if (en) simTypeSpinner.setValue(b.getSimType().ordinal());
		gravitySpinner.setEnabled(en); if (en) gravitySpinner.setValue(b.getGravity());
		dampingSpinner.setEnabled(en); if (en) dampingSpinner.setValue(b.getDamping());
		stiffnessSpinner.setEnabled(en); if (en) stiffnessSpinner.setValue(b.getStiffness());
		massSpinner.setEnabled(en); if (en) massSpinner.setValue(b.getMass());
		windSpinner.setEnabled(en); if (en) windSpinner.setValue(b.getWindInfluence());
		collisionSpinner.setEnabled(en); if (en) collisionSpinner.setValue(b.getCollisionRadius());
		maxStretchSpinner.setEnabled(en); if (en) maxStretchSpinner.setValue(b.getMaxStretch());
		limitX.setEnabled(en); if (en) limitX.setValue(b.getLimitAngleX());
		limitY.setEnabled(en); if (en) limitY.setValue(b.getLimitAngleY());
		limitZ.setEnabled(en); if (en) limitZ.setValue(b.getLimitAngleZ());
		iterationsSpinner.setEnabled(en); if (en) iterationsSpinner.setValue(b.getIterations());
		inheritCb.setEnabled(en); if (en) inheritCb.setSelected(b.isInheritAnimation());
	}

	private void addLabel(String key) {
		Label l = new Label(gui, gui.i18nFormat(key));
		l.setBounds(new Box(2, 0, 164, 12));
		addElement(l);
	}

	private Spinner mkSpin(float def) {
		Spinner s = new Spinner(gui);
		s.setBounds(new Box(2, 0, 164, 18));
		s.setDp(2);
		s.setValue(def);
		addElement(s);
		return s;
	}

	private Spinner addSpinFloat(float def, java.util.function.Consumer<Float> c) {
		Spinner s = mkSpin(def);
		s.addChangeListener(() -> { if (get() != null) c.accept(s.getValue()); });
		return s;
	}

	private Spinner addSpinInt(int def, java.util.function.Consumer<Integer> c) {
		Spinner s = new Spinner(gui);
		s.setBounds(new Box(2, 0, 164, 18));
		s.setDp(0);
		s.setValue(def);
		s.addChangeListener(() -> { if (get() != null) c.accept((int) s.getValue()); });
		addElement(s);
		return s;
	}

	private Spinner smallSpin(int x, float def, java.util.function.Consumer<Float> c) {
		Spinner s = new Spinner(gui);
		s.setBounds(new Box(x, 0, 38, 18));
		s.setDp(1);
		s.setValue(def);
		s.addChangeListener(() -> { if (get() != null) c.accept(s.getValue()); });
		return s;
	}
}
