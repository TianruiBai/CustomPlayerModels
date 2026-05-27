package com.tom.cpm.shared.editor.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.FileChooserPopup;
import com.tom.cpl.gui.elements.FileChooserPopup.FileFilter;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.ScrollPanel;
import com.tom.cpl.gui.elements.Tooltip;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;

public class AssetsPanel extends Panel {
	private final Editor editor;
	private final EditorGui frm;
	private final FlowLayout layout;
	private final Panel listPanel;
	private AssetCategory category = AssetCategory.PARTICLES;
	private String selectedPath;

	public AssetsPanel(IGui gui, EditorGui e, int width, int height) {
		super(gui);
		this.editor = e.getEditor();
		this.frm = e;
		setBounds(new Box(0, 0, width, height));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 4, 2);

		Label title = new Label(gui, gui.i18nFormat("label.cpm.assets.title"));
		title.setBounds(new Box(5, 0, width - 10, 12));
		addElement(title);

		Panel filters = new Panel(gui);
		filters.setBounds(new Box(0, 0, width, 22));
		int x = 2;
		for(AssetCategory c : AssetCategory.VALUES) {
			Button b = new Button(gui, gui.i18nFormat(c.label), () -> {
				category = c;
				selectedPath = null;
				rebuildList();
			});
			b.setBounds(new Box(x, 1, c.buttonWidth, 18));
			b.setEnabled(category != c);
			filters.addElement(b);
			x += c.buttonWidth + 2;
		}
		addElement(filters);

		Panel actions = new Panel(gui);
		actions.setBounds(new Box(0, 0, width, 22));
		Button importButton = new Button(gui, gui.i18nFormat("button.cpm.assets.import"), this::importAsset);
		importButton.setBounds(new Box(2, 1, 78, 18));
		actions.addElement(importButton);
		Button deleteButton = new Button(gui, gui.i18nFormat("button.cpm.assets.delete"), this::deleteAsset);
		deleteButton.setBounds(new Box(84, 1, 78, 18));
		deleteButton.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.assets.delete")));
		actions.addElement(deleteButton);
		addElement(actions);

		ScrollPanel scroll = new ScrollPanel(gui);
		scroll.setBounds(new Box(4, 0, width - 8, height - 72));
		scroll.setScrollBarSide(true);
		listPanel = new Panel(gui);
		listPanel.setBackgroundColor(gui.getColors().panel_background);
		scroll.setDisplay(listPanel);
		addElement(scroll);

		editor.updateGui.add(this::rebuildList);
		rebuildList();
	}

	private void rebuildList() {
		listPanel.getElements().clear();
		List<String> assets = listAssets(category);
		int y = 0;
		for(String path : assets) {
			String text = path.equals(selectedPath) ? "> " + path : "  " + path;
			Button b = new Button(gui, text, () -> selectedPath = path);
			b.setBounds(new Box(2, y, Math.max(220, getBounds().w - 34), 16));
			listPanel.addElement(b);
			y += 18;
		}
		if(assets.isEmpty()) {
			Label empty = new Label(gui, gui.i18nFormat("label.cpm.assets.empty"));
			empty.setBounds(new Box(4, 4, 220, 12));
			listPanel.addElement(empty);
			y = 20;
		}
		listPanel.setBounds(new Box(0, 0, Math.max(240, getBounds().w - 12), Math.max(180, y)));
		layout.reflow();
	}

	private List<String> listAssets(AssetCategory cat) {
		List<String> out = new ArrayList<>();
		if(cat == AssetCategory.SKIN) {
			if(editor.project.getEntry("skin.png") != null)out.add("skin.png");
			return out;
		}
		List<String> entries = editor.project.listEntires(cat.folder);
		if(entries == null)return out;
		for(String entry : entries) {
			String lower = entry.toLowerCase(Locale.ROOT);
			for(String ext : cat.exts) {
				if(lower.endsWith("." + ext)) {
					out.add(cat.folder + "/" + entry);
					break;
				}
			}
		}
		Collections.sort(out);
		return out;
	}

	private void importAsset() {
		FileChooserPopup fc = new FileChooserPopup(frm);
		fc.setFilter(new FileFilter(category.exts));
		fc.setButtonText(gui.i18nFormat("button.cpm.ok"));
		fc.setAccept(file -> {
			try {
				String target = category == AssetCategory.SKIN ? "skin.png" : category.folder + "/" + file.getName();
				editor.project.setEntry(target, Files.readAllBytes(file.toPath()));
				selectedPath = target;
				editor.markDirty();
				editor.updateGui.accept(null);
			} catch (IOException ex) {
				gui.displayMessagePopup(gui.i18nFormat("label.cpm.error"), ex.getMessage());
			}
		});
		frm.openPopup(fc);
	}

	private void deleteAsset() {
		if(selectedPath == null)return;
		if("skin.png".equals(selectedPath))return;
		editor.project.delete(selectedPath);
		selectedPath = null;
		editor.markDirty();
		editor.updateGui.accept(null);
	}

	private enum AssetCategory {
		SKIN("label.cpm.assets.skin", "", 52, "png"),
		TEXTURES("label.cpm.assets.textures", "textures", 72, "png"),
		ANIMATIONS("label.cpm.assets.animations", "animations", 82, "json"),
		PARTICLES("label.cpm.assets.particles", "particles", 72, "png"),
		SFX("label.cpm.assets.sfx", "sounds", 50, "ogg"),
		MIDI("label.cpm.assets.midi", "sounds", 54, "mid");

		private static final AssetCategory[] VALUES = values();
		private final String label;
		private final String folder;
		private final int buttonWidth;
		private final String[] exts;

		private AssetCategory(String label, String folder, int buttonWidth, String... exts) {
			this.label = label;
			this.folder = folder;
			this.buttonWidth = buttonWidth;
			this.exts = exts;
		}
	}
}
