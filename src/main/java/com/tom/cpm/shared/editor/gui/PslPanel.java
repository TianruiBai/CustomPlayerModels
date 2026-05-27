package com.tom.cpm.shared.editor.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.GuiElement;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.PopupMenu;
import com.tom.cpl.gui.elements.Tooltip;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;
import com.tom.cpm.shared.psl.PslSystem;
import com.tom.cpm.shared.psl.light.LightEmitter;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.physics.PhysicsBone;
import com.tom.cpm.shared.psl.sound.MidiEmitter;
import com.tom.cpm.shared.psl.sound.SoundEmitter;

/**
 * Left-side scroll panel listing PSL elements with type filters and add/delete.
 */
public class PslPanel extends Panel {
	private Editor editor;
	private EditorGui frm;
	private FlowLayout layout;
	private Checkbox showP, showPh, showS, showL, showM;
	private static final Random ID_GEN = new Random();

	public PslPanel(IGui gui, EditorGui e) {
		super(gui);
		this.editor = e.getEditor();
		this.frm = e;
		setBounds(new Box(0, 0, 170, 400));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 4, 1);

		// Filter row
		Panel fRow = new Panel(gui);
		fRow.setBounds(new Box(0, 0, 170, 18));
		showP = mkFilter(fRow, "P", "label.cpm.psl.type.particle", 2);
		showPh = mkFilter(fRow, "Ph", "label.cpm.psl.type.physics", 24);
		showS = mkFilter(fRow, "S", "label.cpm.psl.type.sound", 50);
		showL = mkFilter(fRow, "L", "label.cpm.psl.type.light", 72);
		showM = mkFilter(fRow, "M", "label.cpm.psl.type.midi", 94);
		addElement(fRow);

		// Add/Del buttons
		Panel btnRow = new Panel(gui);
		btnRow.setBounds(new Box(0, 0, 170, 20));
		Button addBtn = new Button(gui, "+", () -> showAdd());
		addBtn.setBounds(new Box(2, 0, 80, 18));
		addBtn.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.add")));
		btnRow.addElement(addBtn);
		Button delBtn = new Button(gui, "-", () -> del());
		delBtn.setBounds(new Box(84, 0, 80, 18));
		delBtn.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.delete")));
		btnRow.addElement(delBtn);
		addElement(btnRow);

		// Rebuild when gui updates
		editor.updateGui.add(this::rebuild);
	}

	private Checkbox mkFilter(Panel p, String t, String key, int x) {
		Checkbox c = new Checkbox(gui, t);
		c.setSelected(true);
		c.setBounds(new Box(x, 1, 22, 14));
		c.setTooltip(new Tooltip(frm, gui.i18nFormat(key)));
		c.setAction(() -> rebuild());
		p.addElement(c);
		return c;
	}

	private void showAdd() {
		PopupMenu m = new PopupMenu(gui, frm);
		m.addButton(gui.i18nFormat("label.cpm.psl.add.particle"), () -> add(PslElementType.PARTICLE));
		m.addButton(gui.i18nFormat("label.cpm.psl.add.physics"), () -> add(PslElementType.PHYSICS));
		m.addButton(gui.i18nFormat("label.cpm.psl.add.sound"), () -> add(PslElementType.SOUND));
		m.addButton(gui.i18nFormat("label.cpm.psl.add.light"), () -> add(PslElementType.LIGHT));
		m.addButton(gui.i18nFormat("label.cpm.psl.add.midi"), () -> add(PslElementType.MIDI));
		m.display(0, 20);
	}

	private void del() {
		if (editor.selectedPslElement != null) {
			sys().removeElement(editor.selectedPslElement.getId());
			editor.selectedPslElement = null;
			editor.markDirty();
			rebuild();
		}
	}

	private PslSystem sys() {
		if (editor.pslSystem == null) editor.pslSystem = new PslSystem();
		return editor.pslSystem;
	}

	private void add(PslElementType t) {
		long id = Math.abs(ID_GEN.nextLong());
		PslElement el;
		switch (t) {
			case PARTICLE: el = new ParticleEmitter(id, -1); break;
			case PHYSICS: el = new PhysicsBone(id, -1); break;
			case SOUND: el = new SoundEmitter(id, -1); break;
			case LIGHT: el = new LightEmitter(id, -1); break;
			case MIDI: el = new MidiEmitter(id); break;
			default: return;
		}
		el.setName(dname(t));
		sys().addElement(el);
		editor.selectedPslElement = el;
		editor.markDirty();
		rebuild();
	}

	private String dname(PslElementType t) {
		switch (t) {
			case PARTICLE: return "Particle";
			case PHYSICS: return "Physics";
			case SOUND: return "Sound";
			case LIGHT: return "Light";
			case MIDI: return "MIDI";
			default: return "Element";
		}
	}

	private void rebuild() {
		// CPM panels don't support removeElement — we hide all children and rebuild text
		// Using a simpler approach: label-based list with visibility toggle
		// But labels don't have click handlers... Use buttons.

		// Actually, the simplest approach that works: clear and re-add children
		// Panel.getElements() returns a list we can clear
		getElements().clear();

		// Re-add static controls
		Panel fRow = new Panel(gui);
		fRow.setBounds(new Box(0, 0, 170, 18));
		showP = mkFilter(fRow, "P", "label.cpm.psl.type.particle", 2);
		showPh = mkFilter(fRow, "Ph", "label.cpm.psl.type.physics", 24);
		showS = mkFilter(fRow, "S", "label.cpm.psl.type.sound", 50);
		showL = mkFilter(fRow, "L", "label.cpm.psl.type.light", 72);
		showM = mkFilter(fRow, "M", "label.cpm.psl.type.midi", 94);
		addElement(fRow);

		Panel btnRow = new Panel(gui);
		btnRow.setBounds(new Box(0, 0, 170, 20));
		Button addBtn = new Button(gui, "+", () -> showAdd());
		addBtn.setBounds(new Box(2, 0, 80, 18));
		addBtn.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.add")));
		btnRow.addElement(addBtn);
		Button delBtn = new Button(gui, "-", () -> del());
		delBtn.setBounds(new Box(84, 0, 80, 18));
		delBtn.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.delete")));
		btnRow.addElement(delBtn);
		addElement(btnRow);

		PslSystem s = editor.pslSystem;
		int y = 0;
		if (s != null) {
			for (PslElement el : s.getElements()) {
				if (!vis(el.getType())) continue;
				String txt = (el.getName() != null ? el.getName() : el.getType().name())
					+ " [" + ch(el.getType()) + "]";
				Button b = new Button(gui, txt, () -> {
					editor.selectedPslElement = el;
					editor.updateGui.accept(null);
					rebuild();
				});
				b.setBounds(new Box(2, y, 164, 16));
				b.setTooltip(new Tooltip(frm, el.getTrigger().toString()));
				addElement(b);
				y += 18;
			}
		}
		if (y == 0) {
			Label l = new Label(gui, gui.i18nFormat("label.cpm.psl.empty"));
			l.setBounds(new Box(5, 0, 160, 14));
			addElement(l);
		}
		layout.reflow();
	}

	private boolean vis(PslElementType t) {
		switch (t) {
			case PARTICLE: return showP != null && showP.isSelected();
			case PHYSICS: return showPh != null && showPh.isSelected();
			case SOUND: return showS != null && showS.isSelected();
			case LIGHT: return showL != null && showL.isSelected();
			case MIDI: return showM != null && showM.isSelected();
			default: return true;
		}
	}

	private char ch(PslElementType t) {
		switch (t) {
			case PARTICLE: return 'P';
			case PHYSICS: return 'H';
			case SOUND: return 'S';
			case LIGHT: return 'L';
			case MIDI: return 'M';
			default: return '?';
		}
	}
}
