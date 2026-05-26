package com.tom.cpm.shared.editor.gui.popup;

import java.util.List;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.ConfirmPopup;
import com.tom.cpl.gui.elements.FileChooserPopup;
import com.tom.cpl.gui.elements.FileChooserPopup.FileFilter;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.MessagePopup;
import com.tom.cpl.gui.elements.PopupPanel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.elements.Tooltip;
import com.tom.cpl.math.Box;
import com.tom.cpl.util.EmbeddedLocalizations;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.TextureSlot;
import com.tom.cpm.shared.editor.actions.ActionBuilder;
import com.tom.cpm.shared.editor.gui.EditorGui;
import com.tom.cpm.shared.util.Log;

public class SkinSettingsPopup extends PopupPanel {
	private static boolean shownWarning = false;

	private ETextures tex;

	public static void showPopup(EditorGui e) {
		if(canEdit(e)) {
			e.openPopup(new SkinSettingsPopup(e.getGui(), e));
		}
	}

	public static boolean canEdit(EditorGui e) {
		Editor editor = e.getEditor();
		ETextures tex = editor.getTextureProvider();
		return tex != null && tex.isEditable();
	}

	private SkinSettingsPopup(IGui gui, EditorGui e) {
		super(gui);

		Editor editor = e.getEditor();
		tex = editor.getTextureProvider();

		Button openSkinBtn = new Button(gui, gui.i18nFormat("button.cpm.openSkin"), () -> {
			FileChooserPopup fc = new FileChooserPopup(e);
			fc.setTitle(EmbeddedLocalizations.loadSkin);
			fc.setFileDescText(EmbeddedLocalizations.filePng);
			fc.setFilter(new FileFilter("png"));
			fc.setAccept(e::loadSkin);
			fc.setButtonText(gui.i18nFormat("button.cpm.ok"));
			e.openPopup(fc);
			close();
		});
		openSkinBtn.setBounds(new Box(5, 5, 60, 20));
		addElement(openSkinBtn);

		Button saveSkin = new Button(gui, gui.i18nFormat("button.cpm.saveSkin"), () -> {
			if(gui.isShiftDown() || tex.file == null) {
				FileChooserPopup fc = new FileChooserPopup(e);
				fc.setTitle(EmbeddedLocalizations.saveSkin);
				fc.setFileDescText(EmbeddedLocalizations.filePng);
				fc.setFilter(new FileFilter("png"));
				fc.setSaveDialog(true);
				fc.setExtAdder(f -> f + ".png");
				fc.setButtonText(gui.i18nFormat("button.cpm.ok"));
				fc.setAccept(editor::saveSkin);
				e.openPopup(fc);
			} else {
				editor.saveSkin(tex.file);
			}
			close();
		});
		saveSkin.setBounds(new Box(75, 5, 60, 20));
		addElement(saveSkin);

		Button newSkin = new Button(gui, gui.i18nFormat("button.cpm.newSkin"), () -> {
			close();
			e.openPopup(new NewSkinPopup(gui, editor));
		});
		newSkin.setBounds(new Box(145, 5, 60, 20));
		addElement(newSkin);

		Button delSkin = new Button(gui, gui.i18nFormat("button.cpm.delSkin"), () -> {
			boolean edited = tex.isEdited();
			if(edited) {
				e.openPopup(new ConfirmPopup(e, gui.i18nFormat("label.cpm.delSkin"), () -> {
					editor.action("delTexture").
					updateValueOp(tex, tex.getImage(), tex.copyDefaultImg(), ETextures::setImage).
					updateValueOp(tex, tex.isEdited(), false, ETextures::setEdited).
					updateValueOp(tex, tex.file, null, (a, b) -> a.file = b).
					onAction(() -> {
						editor.restitchTextures();
						editor.updateGui();
					}).
					execute();
				}, null));
			}
		});
		delSkin.setBounds(new Box(5, 30, 60, 20));
		addElement(delSkin);

		Checkbox customGridSize = new Checkbox(gui, gui.i18nFormat("label.cpm.customGridSize"));
		customGridSize.setBounds(new Box(5, 80, 100, 20));
		customGridSize.setTooltip(new Tooltip(e, gui.i18nFormat("tooltip.cpm.customGridSize")));
		customGridSize.setSelected(tex.customGridSize);
		addElement(customGridSize);

		Label lblTW = new Label(gui, gui.i18nFormat("label.cpm.width"));
		lblTW.setBounds(new Box(5, 105, 40, 18));
		Label lblTH = new Label(gui, gui.i18nFormat("label.cpm.height"));
		lblTH.setBounds(new Box(75, 105, 40, 18));

		Spinner spinnerTW = new Spinner(gui);
		Spinner spinnerTH = new Spinner(gui);
		spinnerTW.setBounds(new Box(5, 115, 65, 20));
		spinnerTH.setBounds(new Box(75, 115, 65, 20));
		spinnerTW.setDp(0);
		spinnerTH.setDp(0);
		spinnerTW.setEnabled(tex.customGridSize);
		spinnerTH.setEnabled(tex.customGridSize);
		addElement(spinnerTW);
		addElement(spinnerTH);
		addElement(lblTW);
		addElement(lblTH);
		customGridSize.setAction(() -> {
			boolean v = !tex.customGridSize;
			ActionBuilder ab = editor.action("switch", "label.cpm.customGridSize").
					updateValueOp(tex, tex.customGridSize, v, (a, b) -> a.customGridSize = b);
			if(!v) {
				ab.updateValueOp(tex, tex.provider.size.x, tex.provider.getImage().getWidth(), (a, b) -> a.provider.size.x = b).
				updateValueOp(tex, tex.provider.size.y, tex.provider.getImage().getHeight(), (a, b) -> a.provider.size.y = b).
				onAction(tex::restitchTexture).
				onAction(editor::markElementsDirty);
				spinnerTW.setValue(tex.provider.getImage().getWidth());
				spinnerTH.setValue(tex.provider.getImage().getHeight());
			}
			ab.execute();
			customGridSize.setSelected(v);
			spinnerTW.setEnabled(v);
			spinnerTH.setEnabled(v);
		});

		Runnable r = () -> {
			if(editor.hasVanillaParts() && !shownWarning) {
				shownWarning = true;
				e.openPopup(new MessagePopup(e, gui.i18nFormat("label.cpm.warning"), gui.i18nFormat("label.cpm.skin_has_vanilla_parts")));
			} else
				editor.setTexSize((int) spinnerTW.getValue(), (int) spinnerTH.getValue());
		};
		spinnerTW.addChangeListener(r);
		spinnerTH.addChangeListener(r);
		spinnerTW.setValue(tex.provider.size.x);
		spinnerTH.setValue(tex.provider.size.y);

		// ---- Texture Slots Section ----
		int slotY = 145;
		Label slotLbl = new Label(gui, gui.i18nFormat("label.cpm.textureSlots"));
		slotLbl.setBounds(new Box(5, slotY, 200, 18));
		addElement(slotLbl);
		slotY += 22;

		// List texture slot buttons
		List<TextureSlot> slots = editor.textureSlots;
		int visibleSlots = Math.min(slots.size(), 6);
		int slotBtnY = slotY;
		for (int i = 0; i < visibleSlots; i++) {
			final int idx = i;
			TextureSlot slot = slots.get(i);
			String label = (idx == editor.activeTextureSlot ? "> " : "  ") + slot.name;
			Button slotBtn = new Button(gui, label, () -> {
				editor.switchToTextureSlot(idx);
				close();
			});
			slotBtn.setBounds(new Box(5, slotBtnY + idx * 22, 190, 20));
			addElement(slotBtn);
		}

		int btnRowY = slotBtnY + visibleSlots * 22 + 5;

		// Add Slot button
		Button addSlotBtn = new Button(gui, gui.i18nFormat("button.cpm.addTextureSlot"), () -> {
			FileChooserPopup fc = new FileChooserPopup(e);
			fc.setTitle(EmbeddedLocalizations.loadSkin);
			fc.setFileDescText(EmbeddedLocalizations.filePng);
			fc.setFilter(new FileFilter("png"));
			fc.setAccept(f -> {
				com.tom.cpl.util.Image.loadFrom(f).thenAcceptAsync(img -> {
					if (img != null) {
						TextureSlot newSlot = new TextureSlot(f.getName(), img,
							new com.tom.cpl.math.Vec2i(img.getWidth(), img.getHeight()), false);
						editor.textureSlots.add(newSlot);
						Log.info("[Editor] Added texture slot: " + newSlot.name);
						editor.updateGui();
					}
				}, gui::executeLater).thenRun(this::close);
			});
			fc.setButtonText(gui.i18nFormat("button.cpm.ok"));
			e.openPopup(fc);
		});
		addSlotBtn.setBounds(new Box(5, btnRowY, 90, 20));
		addElement(addSlotBtn);

		// Remove Slot button (disabled if only 1 slot)
		Button rmSlotBtn = new Button(gui, gui.i18nFormat("button.cpm.removeTextureSlot"), () -> {
			if (editor.textureSlots.size() <= 1) return;
			int idx = editor.activeTextureSlot;
			editor.textureSlots.remove(idx);
			if (editor.activeTextureSlot >= editor.textureSlots.size())
				editor.activeTextureSlot = editor.textureSlots.size() - 1;
			editor.switchToTextureSlot(editor.activeTextureSlot);
			Log.info("[Editor] Removed texture slot " + idx);
			editor.updateGui();
			close();
		});
		rmSlotBtn.setBounds(new Box(100, btnRowY, 100, 20));
		rmSlotBtn.setEnabled(editor.textureSlots.size() > 1);
		addElement(rmSlotBtn);

		// Next/Prev buttons
		Button prevSlotBtn = new Button(gui, "<", editor::prevTextureSlot);
		prevSlotBtn.setBounds(new Box(5, btnRowY + 25, 30, 20));
		prevSlotBtn.setEnabled(editor.textureSlots.size() > 1);
		addElement(prevSlotBtn);

		Button nextSlotBtn = new Button(gui, ">", editor::nextTextureSlot);
		nextSlotBtn.setBounds(new Box(40, btnRowY + 25, 30, 20));
		nextSlotBtn.setEnabled(editor.textureSlots.size() > 1);
		addElement(nextSlotBtn);

		Label slotNavLbl = new Label(gui, gui.i18nFormat("label.cpm.textureSlotNav"));
		slotNavLbl.setBounds(new Box(75, btnRowY + 25, 120, 20));
		addElement(slotNavLbl);

		setBounds(new Box(0, 0, 210, btnRowY + 50));
	}

	@Override
	public String getTitle() {
		return gui.i18nFormat("label.cpm.skinSettings.title", tex.getName());
	}
}
