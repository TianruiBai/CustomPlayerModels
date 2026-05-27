package com.tom.cpm.shared.editor.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.MouseEvent;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.PopupPanel;
import com.tom.cpl.gui.elements.ScrollPanel;
import com.tom.cpl.gui.elements.TextField;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.ParticleSource;

public class PslParticlePickerPopup extends PopupPanel {
	private final Editor editor;
	private final BiConsumer<ParticleSource, String> accept;
	private final List<Entry> allEntries = new ArrayList<>();
	private final Panel listPanel;
	private final TextField search;
	private Entry selected;

	public PslParticlePickerPopup(Frame frame, Editor editor, BiConsumer<ParticleSource, String> accept) {
		super(frame.getGui());
		this.editor = editor;
		this.accept = accept;
		setBounds(new Box(0, 0, 430, 265));

		Label title = new Label(gui, gui.i18nFormat("label.cpm.psl.particlePicker"));
		title.setBounds(new Box(8, 8, 300, 12));
		addElement(title);

		search = new TextField(gui);
		search.setBounds(new Box(8, 24, 250, 18));
		search.setEventListener(this::refreshList);
		addElement(search);

		ScrollPanel scroll = new ScrollPanel(gui);
		scroll.setBounds(new Box(8, 48, 250, 178));
		scroll.setScrollBarSide(true);
		listPanel = new Panel(gui);
		listPanel.setBackgroundColor(gui.getColors().panel_background);
		scroll.setDisplay(listPanel);
		addElement(scroll);

		Panel preview = new PreviewPanel(gui);
		preview.setBounds(new Box(268, 48, 154, 178));
		preview.setBackgroundColor(gui.getColors().panel_background);
		addElement(preview);

		Button use = new Button(gui, gui.i18nFormat("button.cpm.psl.useParticle"), () -> {
			if(selected != null) {
				accept.accept(selected.source, selected.id);
				close();
			}
		});
		use.setBounds(new Box(8, 236, 90, 20));
		addElement(use);

		Button cancel = new Button(gui, gui.i18nFormat("button.cpm.cancel"), this::close);
		cancel.setBounds(new Box(104, 236, 90, 20));
		addElement(cancel);

		loadEntries();
		refreshList();
	}

	private void loadEntries() {
		for(String id : PslParticleCatalog.customParticles(editor)) {
			allEntries.add(new Entry(ParticleSource.CUSTOM_SPRITE, id));
		}
		for(String id : PslParticleCatalog.minecraftParticles()) {
			allEntries.add(new Entry(ParticleSource.MINECRAFT_BUILTIN, id));
		}
		if(!allEntries.isEmpty())selected = allEntries.get(0);
	}

	private void refreshList() {
		listPanel.getElements().clear();
		String q = search.getText() != null ? search.getText().toLowerCase(Locale.ROOT) : "";
		int y = 0;
		for(Entry entry : allEntries) {
			if(!q.isEmpty() && !entry.id.toLowerCase(Locale.ROOT).contains(q))continue;
			String prefix = entry.source == ParticleSource.CUSTOM_SPRITE ? "[Project] " : "[MC] ";
			Button button = new Button(gui, prefix + entry.displayName(), () -> selected = entry);
			button.setBounds(new Box(2, y, 236, 16));
			listPanel.addElement(button);
			y += 18;
		}
		if(y == 0) {
			Label empty = new Label(gui, gui.i18nFormat("label.cpm.psl.asset.empty"));
			empty.setBounds(new Box(4, 4, 220, 12));
			listPanel.addElement(empty);
			y = 20;
		}
		listPanel.setBounds(new Box(0, 0, 248, Math.max(178, y)));
	}

	@Override
	public String getTitle() {
		return gui.i18nFormat("label.cpm.psl.particlePicker");
	}

	private class PreviewPanel extends Panel {
		public PreviewPanel(IGui gui) {
			super(gui);
		}

		@Override
		public void draw(MouseEvent event, float partialTicks) {
			super.draw(event, partialTicks);
			if(selected == null)return;
			gui.drawText(bounds.x + 8, bounds.y + 8, selected.source == ParticleSource.CUSTOM_SPRITE ? "Project Sprite" : "Minecraft Particle", gui.getColors().label_text_color);
			gui.drawText(bounds.x + 8, bounds.y + 22, selected.displayName(), gui.getColors().label_text_color);
			int cx = bounds.x + bounds.w / 2;
			int cy = bounds.y + 100;
			long time = System.currentTimeMillis() / 80L;
			int color = selected.source == ParticleSource.CUSTOM_SPRITE ? 0xff8bd3ff : 0xffffb347;
			for(int i = 0; i < 18; i++) {
				float a = (time + i * 17) * 0.18f;
				int x = cx + (int)(Math.cos(a) * (14 + i % 5 * 4));
				int y = cy + (int)(Math.sin(a * 0.7f) * (10 + i % 4 * 3));
				gui.drawBox(x, y, 3, 3, color);
			}
		}
	}

	private static class Entry {
		private final ParticleSource source;
		private final String id;

		private Entry(ParticleSource source, String id) {
			this.source = source;
			this.id = id;
		}

		private String displayName() {
			int slash = id.lastIndexOf('/');
			int colon = id.lastIndexOf(':');
			int idx = Math.max(slash, colon);
			return idx >= 0 ? id.substring(idx + 1) : id;
		}
	}
}
