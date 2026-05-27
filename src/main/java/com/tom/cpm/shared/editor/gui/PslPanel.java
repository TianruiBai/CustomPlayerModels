package com.tom.cpm.shared.editor.gui;

import java.util.Random;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.InputPopup;
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
	private PslElementType activeFilter;
	private static final Random ID_GEN = new Random();

	public PslPanel(IGui gui, EditorGui e) {
		super(gui);
		this.editor = e.getEditor();
		this.frm = e;
		setBounds(new Box(0, 0, 170, 400));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 4, 1);

		// Rebuild when gui updates
		editor.updateGui.add(this::rebuild);
		rebuild();
	}

	private Button mkFilter(Panel p, String t, String key, int x, int w, PslElementType filter) {
		Button b = new Button(gui, t, () -> {
			activeFilter = filter;
			rebuild();
		});
		b.setBounds(new Box(x, 0, w, 18));
		b.setEnabled(activeFilter != filter);
		b.setTooltip(new Tooltip(frm, gui.i18nFormat(key)));
		p.addElement(b);
		return b;
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

	private void rename() {
		PslElement selected = editor.selectedPslElement;
		if(selected == null)return;
		frm.openPopup(new InputPopup(frm, gui.i18nFormat("label.cpm.psl.rename"), gui.i18nFormat("label.cpm.psl.rename.desc"), name -> {
			selected.setName(name);
			editor.markDirty();
			editor.updateGui.accept(null);
		}, null));
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
		activeFilter = t;
		editor.markDirty();
		editor.updateGui.accept(null);
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
		getElements().clear();

		Label title = new Label(gui, gui.i18nFormat("label.cpm.psl.elements"));
		title.setBounds(new Box(5, 0, 160, 12));
		addElement(title);

		Panel fRow = new Panel(gui);
		fRow.setBounds(new Box(0, 0, 170, 18));
		mkFilter(fRow, gui.i18nFormat("label.cpm.psl.filter.all"), "label.cpm.psl.filter.all", 2, 36, null);
		mkFilter(fRow, "P", "label.cpm.psl.type.particle", 40, 24, PslElementType.PARTICLE);
		mkFilter(fRow, "H", "label.cpm.psl.type.physics", 66, 24, PslElementType.PHYSICS);
		mkFilter(fRow, "S", "label.cpm.psl.type.sound", 92, 24, PslElementType.SOUND);
		mkFilter(fRow, "L", "label.cpm.psl.type.light", 118, 24, PslElementType.LIGHT);
		mkFilter(fRow, "M", "label.cpm.psl.type.midi", 144, 24, PslElementType.MIDI);
		addElement(fRow);

		Panel btnRow = new Panel(gui);
		btnRow.setBounds(new Box(0, 0, 170, 20));
		Button addBtn = new Button(gui, gui.i18nFormat("button.cpm.psl.add"), () -> showAdd());
		addBtn.setBounds(new Box(2, 0, 52, 18));
		addBtn.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.add")));
		btnRow.addElement(addBtn);
		Button renameBtn = new Button(gui, gui.i18nFormat("button.cpm.psl.rename"), () -> rename());
		renameBtn.setBounds(new Box(56, 0, 56, 18));
		renameBtn.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.rename")));
		renameBtn.setEnabled(editor.selectedPslElement != null);
		btnRow.addElement(renameBtn);
		Button delBtn = new Button(gui, gui.i18nFormat("button.cpm.psl.delete"), () -> del());
		delBtn.setBounds(new Box(114, 0, 52, 18));
		delBtn.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.delete")));
		delBtn.setEnabled(editor.selectedPslElement != null);
		btnRow.addElement(delBtn);
		addElement(btnRow);

		PslSystem s = editor.pslSystem;
		int y = 0;
		if (s != null) {
			if(activeFilter == null) {
				for(PslElementType type : PslElementType.VALUES) {
					int count = count(s, type);
					if(count == 0)continue;
					Label group = new Label(gui, ch(type) + " " + gui.i18nFormat(typeKey(type)) + " (" + count + ")");
					group.setBounds(new Box(4, y, 160, 12));
					addElement(group);
					y += 13;
					for (PslElement el : s.getElements()) {
						if(el.getType() == type)y = addElementButton(el, y, 12);
					}
				}
			} else {
				for (PslElement el : s.getElements()) {
					if (vis(el.getType())) y = addElementButton(el, y, 0);
				}
			}
		}
		if (y == 0) {
			Label l = new Label(gui, gui.i18nFormat("label.cpm.psl.empty"));
			l.setBounds(new Box(5, 0, 160, 14));
			addElement(l);
		}
		layout.reflow();
	}

	private int addElementButton(PslElement el, int y, int indent) {
		String marker = el == editor.selectedPslElement ? "> " : "  ";
		String name = el.getName() != null && !el.getName().isEmpty() ? el.getName() : el.getType().name();
		String txt = marker + name;
		Button b = new Button(gui, txt, () -> {
			editor.selectedPslElement = el;
			editor.updateGui.accept(null);
		});
		b.setBounds(new Box(2 + indent, y, 164 - indent, 16));
		b.setTooltip(new Tooltip(frm, gui.i18nFormat("label.cpm.psl.target", PslUiUtil.describeTarget(editor, el.getElementId())) + "\\" + el.getTrigger().toString()));
		addElement(b);
		return y + 18;
	}

	private int count(PslSystem system, PslElementType type) {
		int count = 0;
		for(PslElement element : system.getElements()) {
			if(element.getType() == type)count++;
		}
		return count;
	}

	private String typeKey(PslElementType type) {
		switch (type) {
			case PARTICLE: return "label.cpm.psl.type.particle";
			case PHYSICS: return "label.cpm.psl.type.physics";
			case SOUND: return "label.cpm.psl.type.sound";
			case LIGHT: return "label.cpm.psl.type.light";
			case MIDI: return "label.cpm.psl.type.midi";
			default: return "label.cpm.psl.elements";
		}
	}

	private boolean vis(PslElementType t) {
		return activeFilter == null || activeFilter == t;
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
