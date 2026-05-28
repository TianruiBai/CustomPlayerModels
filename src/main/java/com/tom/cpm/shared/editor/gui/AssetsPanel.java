package com.tom.cpm.shared.editor.gui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.MouseEvent;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.FileChooserPopup;
import com.tom.cpl.gui.elements.FileChooserPopup.FileFilter;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.ScrollPanel;
import com.tom.cpl.gui.elements.Tooltip;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.skin.TextureProvider;

public class AssetsPanel extends Panel {
	private final Editor editor;
	private final EditorGui frm;
	private final FlowLayout layout;
	private final Panel listPanel;
	private final ScrollPanel scroll;
	private AssetCategory category = AssetCategory.PARTICLES;
	private String selectedPath;
	private boolean gridView = true;
	private Button viewToggle;

	private static final int GRID_CELL_W = 72;
	private static final int GRID_CELL_H = 76;

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
		importButton.setBounds(new Box(2, 1, 60, 18));
		actions.addElement(importButton);
		Button deleteButton = new Button(gui, gui.i18nFormat("button.cpm.assets.delete"), this::deleteAsset);
		deleteButton.setBounds(new Box(66, 1, 60, 18));
		deleteButton.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.assets.delete")));
		actions.addElement(deleteButton);
		Button viewToggle = new Button(gui, gui.i18nFormat("button.cpm.assets.listView"), () -> {
			gridView = !gridView;
			rebuildList();
		});
		viewToggle.setBounds(new Box(136, 1, 44, 18));
		this.viewToggle = viewToggle;
		actions.addElement(viewToggle);
		addElement(actions);

		scroll = new ScrollPanel(gui);
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
		if (viewToggle != null) {
			viewToggle.setText(gui.i18nFormat(gridView ? "button.cpm.assets.listView" : "button.cpm.assets.gridView"));
		}
		List<String> assets = listAssets(category);

		if (gridView) {
			buildGridView(assets);
		} else {
			buildListView(assets);
		}
		layout.reflow();
	}

	private void buildListView(List<String> assets) {
		int y = 0;
		int w = Math.max(240, getBounds().w - 34);
		for (String path : assets) {
			String text = path.equals(selectedPath) ? "> " + path : "  " + path;
			Button b = new Button(gui, text, () -> selectedPath = path);
			b.setBounds(new Box(2, y, w, 16));
			listPanel.addElement(b);
			y += 18;
		}
		if (assets.isEmpty()) {
			Label empty = new Label(gui, gui.i18nFormat("label.cpm.assets.empty"));
			empty.setBounds(new Box(4, 4, 220, 12));
			listPanel.addElement(empty);
			y = 20;
		}
		listPanel.setBounds(new Box(0, 0, w + 4, Math.max(180, y)));
	}

	private void buildGridView(List<String> assets) {
		int panelW = getBounds().w - 16;
		int cols = Math.max(1, panelW / (GRID_CELL_W + 4));
		int x = 2, y = 2;
		int col = 0;

		for (String path : assets) {
			AssetGridCell cell = new AssetGridCell(gui, path);
			cell.setBounds(new Box(x, y, GRID_CELL_W, GRID_CELL_H));
			listPanel.addElement(cell);
			col++;
			if (col >= cols) {
				col = 0;
				x = 2;
				y += GRID_CELL_H + 4;
			} else {
				x += GRID_CELL_W + 4;
			}
		}
		if (assets.isEmpty()) {
			Label empty = new Label(gui, gui.i18nFormat("label.cpm.assets.empty"));
			empty.setBounds(new Box(4, 4, 220, 12));
			listPanel.addElement(empty);
			y = 20;
		} else if (col > 0) {
			y += GRID_CELL_H + 4;
		}
		listPanel.setBounds(new Box(0, 0, panelW, Math.max(180, y)));
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

	private class AssetGridCell extends Panel {
		private final String assetPath;
		private Image thumbnail;
		private boolean thumbLoaded;

		AssetGridCell(IGui gui, String assetPath) {
			super(gui);
			this.assetPath = assetPath;
		}

		@Override
		public void draw(MouseEvent event, float partialTicks) {
			boolean sel = assetPath.equals(selectedPath);
			int bg = sel ? gui.getColors().button_hover : gui.getColors().button_fill;
			gui.drawBox(bounds.x, bounds.y, bounds.w, bounds.h, bg);
			if (sel) {
				gui.drawRectangle(bounds.x, bounds.y, bounds.w, bounds.h, gui.getColors().button_text_hover);
			}

			int px = bounds.x + 4;
			int py = bounds.y + 4;
			int pw = bounds.w - 8;
			int ph = bounds.h - 20;

			String lower = assetPath.toLowerCase(Locale.ROOT);
			if (lower.endsWith(".png")) {
				if (!thumbLoaded) {
					thumbLoaded = true;
					byte[] data = editor.project.getEntry(assetPath);
					if (data != null) {
						try {
							thumbnail = ImageIO.read(new ByteArrayInputStream(data));
						} catch (Exception ignored) {}
					}
				}
				if (thumbnail != null) {
					TextureProvider tp = getThumbTexture();
					if (tp != null) {
						tp.bind();
						float aspect = (float) thumbnail.getWidth() / Math.max(1, thumbnail.getHeight());
						int dw, dh;
						if (aspect > 1) { dw = pw; dh = (int)(pw / aspect); }
						else { dh = ph; dw = (int)(ph * aspect); }
						gui.drawTexture(px + (pw - dw) / 2, py + (ph - dh) / 2, dw, dh, 0, 0, 1, 1);
					}
				} else {
					drawTypeIcon(px, py, pw, ph, "PNG", 0xff4a90d9);
				}
			} else if (lower.endsWith(".json")) {
				drawTypeIcon(px, py, pw, ph, "JSON", 0xffd9a44a);
			} else if (lower.endsWith(".ogg")) {
				drawTypeIcon(px, py, pw, ph, "OGG", 0xff6abd6a);
			} else if (lower.endsWith(".mid")) {
				drawTypeIcon(px, py, pw, ph, "MID", 0xffbd6ad9);
			} else {
				drawTypeIcon(px, py, pw, ph, "?", 0xff888888);
			}

			// Filename
			String name = assetPath;
			int slash = name.lastIndexOf('/');
			if (slash >= 0) name = name.substring(slash + 1);
			int tw = gui.textWidth(name);
			int maxW = bounds.w - 4;
			if (tw > maxW) {
				while (name.length() > 4 && gui.textWidth(name + "...") > maxW)
					name = name.substring(0, name.length() - 1);
				name += "...";
			}
			gui.drawText(bounds.x + (bounds.w - gui.textWidth(name)) / 2, bounds.y + bounds.h - 14, name, gui.getColors().label_text_color);
		}

		private void drawTypeIcon(int x, int y, int w, int h, String label, int color) {
			gui.drawBox(x, y, w, h, color);
			int tw = gui.textWidth(label);
			gui.drawText(x + (w - tw) / 2, y + (h - 8) / 2, label, 0xffffffff);
		}

		private TextureProvider getThumbTexture() {
			if (thumbnail == null) return null;
			return PslParticlePreviewStyle.TextureCache.get("asset:" + assetPath, thumbnail);
		}

		@Override
		public void mouseClick(MouseEvent event) {
			if (event.isHovered(bounds)) {
				selectedPath = assetPath;
				rebuildList();
			}
		}
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
