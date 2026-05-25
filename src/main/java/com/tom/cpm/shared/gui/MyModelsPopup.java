package com.tom.cpm.shared.gui;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.MouseEvent;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.ConfirmPopup;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.MessagePopup;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.PopupPanel;
import com.tom.cpl.gui.elements.ScrollPanel;
import com.tom.cpl.gui.util.HorizontalLayout;
import com.tom.cpl.gui.util.TabbedPanelManager;
import com.tom.cpl.math.Box;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpl.nbt.NBTTagList;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.MinecraftClientAccess.ServerStatus;
import com.tom.cpm.shared.editor.gui.EditorGui;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.network.packet.ModelDeleteReqC2S;
import com.tom.cpm.shared.io.LocalModelFiles;
import com.tom.cpm.shared.skin.TextureProvider;
import com.tom.cpm.shared.util.Log;
import com.tom.cpm.shared.network.packet.ModelListReqC2S;
import com.tom.cpm.shared.network.packet.ModelSetActiveC2S;
import com.tom.cpm.shared.network.packet.ModelSetDefaultC2S;
import com.tom.cpm.server.client.CpmModelTransferClient;
import com.tom.cpm.server.crypto.CryptoService;
import com.tom.cpm.shared.paste.PastePopup;

/**
 * "My Models" popup — browse models from three sources:
 * 1. Local .cpmmodel files (player_models/ directory)
 * 2. Server database (via ModelListReq/Res packets)
 * 3. Paste site (via PasteClient.listFiles)
 *
 * Actions: Set Active, Set Default, Delete, Download (server→local), Open in Editor.
 */
public class MyModelsPopup extends PopupPanel {

	private static final int ENTRY_HEIGHT = 36;
	private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);

	private final Frame frame;
	private final TabbedPanelManager tabs;
	private final Panel tabButtonPanel;
	private final HorizontalLayout tabButtons;
	private ScrollPanel localScp, serverScp, pasteScp;
	private Panel localPanel, serverPanel, pastePanel;
	private Button refreshLocalBtn, refreshServerBtn;
	private List<ModelEntry> localEntries = new ArrayList<>();
	private List<ModelEntry> serverEntries = new ArrayList<>();
	private List<ModelEntry> pasteEntries = new ArrayList<>();
	private List<TextureProvider> serverModelIcons = new ArrayList<>();

	public MyModelsPopup(Frame frame) {
		super(frame.getGui());
		this.frame = frame;
		setBounds(new Box(0, 0, 430, 300));

		refreshLocalBtn = new Button(gui, gui.i18nFormat("button.cpm.reload_models"), this::refreshLocalModels);
		refreshLocalBtn.setBounds(new Box(5, 0, 110, 20));
		addElement(refreshLocalBtn);

		refreshServerBtn = new Button(gui, gui.i18nFormat("button.cpm.reload_models"), this::refreshServerModels);
		refreshServerBtn.setBounds(new Box(120, 0, 110, 20));
		addElement(refreshServerBtn);

		tabButtonPanel = new Panel(gui);
		tabButtonPanel.setBounds(new Box(5, 22, bounds.w - 10, 20));
		tabButtonPanel.setBackgroundColor(gui.getColors().menu_bar_background);
		addElement(tabButtonPanel);
		tabButtons = new HorizontalLayout(tabButtonPanel);

		tabs = new TabbedPanelManager(gui);
		tabs.setBounds(new Box(0, 42, bounds.w, bounds.h - 42));
		addElement(tabs);

		// ---- Local Models Tab ----
		localPanel = new Panel(gui);
		localPanel.setBackgroundColor(gui.getColors().button_border);
		localPanel.setBounds(new Box(0, 0, 410, 1));
		localScp = new ScrollPanel(gui);
		localScp.setBounds(new Box(5, 5, 410, bounds.h - 40));
		localScp.setDisplay(localPanel);
		addTab("local", localScp);

		// ---- Server Models Tab ----
		serverPanel = new Panel(gui);
		serverPanel.setBackgroundColor(gui.getColors().button_border);
		serverPanel.setBounds(new Box(0, 0, 410, 1));
		serverScp = new ScrollPanel(gui);
		serverScp.setBounds(new Box(5, 5, 410, bounds.h - 40));
		serverScp.setDisplay(serverPanel);
		addTab("server", serverScp);

		// ---- Paste Site Tab ----
		pastePanel = new Panel(gui);
		pastePanel.setBackgroundColor(gui.getColors().button_border);
		pastePanel.setBounds(new Box(0, 0, 410, 1));
		pasteScp = new ScrollPanel(gui);
		pasteScp.setBounds(new Box(5, 5, 410, bounds.h - 40));
		pasteScp.setDisplay(pastePanel);
		addTab("paste", pasteScp);

		refreshLocalModels();
		refreshServerModels();
		refreshPasteModels();
	}

	private void addTab(String key, ScrollPanel scp) {
		Panel tabPanel = new Panel(gui);
		tabPanel.setBounds(new Box(0, 0, 430, bounds.h - 42));
		tabPanel.addElement(scp);
		tabButtons.add(tabs.createTab(gui.i18nFormat("label.cpm.myModels.tab." + key), tabPanel));
	}

	private void refreshLocalModels() {
		localEntries.clear();
		localPanel.getElements().clear();
		loadLocalModels();
	}

	private void refreshServerModels() {
		serverEntries.clear();
		for (TextureProvider tex : serverModelIcons) {
			if (tex != null) tex.free();
		}
		serverModelIcons.clear();
		serverPanel.getElements().clear();
		loadServerModels();
	}

	private void refreshPasteModels() {
		pasteEntries.clear();
		pastePanel.getElements().clear();
		loadPasteModels();
	}

	private String formatSize(long bytes) {
		double kb = bytes / 1024.0;
		if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
		double mb = kb / 1024.0;
		return String.format(Locale.ROOT, "%.2f MB", mb);
	}

	private String formatTime(long ts) {
		return ts > 0 ? DATE_FMT.format(new Date(ts)) : "-";
	}

	// ================================================================
	// Local models
	// ================================================================

	private void loadLocalModels() {
		File modelsDir = new File(MinecraftClientAccess.get().getGameDir(), "player_models");
		List<File> files = LocalModelFiles.listModelsRecursive(modelsDir);
		if (files.isEmpty()) {
			Label lbl = new Label(gui, gui.i18nFormat("label.cpm.myModels.noLocal"));
			lbl.setBounds(new Box(5, 10, 0, 0));
			localPanel.addElement(lbl);
			localPanel.setBounds(new Box(0, 0, 410, 28));
			return;
		}

		int y = 0;
		for (File f : files) {
			String relativePath;
			try {
				relativePath = LocalModelFiles.toRelativeModelPath(modelsDir, f);
			} catch (Exception e) {
				Log.warn("Skipping local model outside player_models: " + f.getPath());
				continue;
			}
			ModelEntry entry = new ModelEntry(relativePath, f.length(), 0, f.lastModified(), "local", null);
			localEntries.add(entry);

			Label nameLbl = new Label(gui, relativePath);
			nameLbl.setBounds(new Box(5, y, 250, 10));
			localPanel.addElement(nameLbl);

			Label metaLbl = new Label(gui, formatSize(f.length()) + " | " + formatTime(f.lastModified()));
			metaLbl.setBounds(new Box(5, y + 12, 250, 10));
			localPanel.addElement(metaLbl);

			Button setBtn = new Button(gui, gui.i18nFormat("button.cpm.setActive"), () -> setLocalActive(modelsDir, f));
			setBtn.setBounds(new Box(255, y, 70, 20));
			localPanel.addElement(setBtn);

			Button editBtn = new Button(gui, gui.i18nFormat("button.cpm.openInEditor"), () -> {
				setLocalActive(modelsDir, f);
				MinecraftClientAccess.get().openGui(EditorGui::new);
			});
			editBtn.setBounds(new Box(330, y, 40, 20));
			localPanel.addElement(editBtn);

			Button delBtn = new Button(gui, gui.i18nFormat("button.cpm.delete"), () -> deleteLocal(f, entry));
			delBtn.setBounds(new Box(375, y, 35, 20));
			localPanel.addElement(delBtn);

			y += ENTRY_HEIGHT;
		}
		localPanel.setBounds(new Box(0, 0, 410, Math.max(28, y)));
	}

	private void setLocalActive(File modelsDir, File f) {
		String selected;
		try {
			selected = LocalModelFiles.toRelativeModelPath(modelsDir, f);
		} catch (IOException e) {
			frame.openPopup(new MessagePopup(frame, gui.i18nFormat("label.cpm.error"), e.getMessage()));
			return;
		}
		ModConfig.getCommonConfig().setString(ConfigKeys.SELECTED_MODEL, selected);
		MinecraftClientAccess.get().getNetHandler().sendSkinData();
		frame.openPopup(new MessagePopup(frame, gui.i18nFormat("label.cpm.export_success"),
			gui.i18nFormat("label.cpm.modelSetActive", selected)));
	}

	private void deleteLocal(File f, ModelEntry entry) {
		frame.openPopup(new ConfirmPopup(frame,
			gui.i18nFormat("label.cpm.myModels.confirmDelete", f.getName()), () -> {
				if (f.delete()) {
					localEntries.remove(entry);
					refreshLocalModels();
				}
			}, null));
	}

	// ================================================================
	// Server models
	// ================================================================

	private void loadServerModels() {
		ServerStatus status = MinecraftClientAccess.get().getServerSideStatus();
		if (status != ServerStatus.INSTALLED) {
			Label lbl = new Label(gui, gui.i18nFormat("label.cpm.myModels.noServer"));
			lbl.setBounds(new Box(5, 10, 0, 0));
			serverPanel.addElement(lbl);
			serverPanel.setBounds(new Box(0, 0, 410, 28));
			return;
		}

		// The model list will arrive asynchronously via ModelListResS2C.
		// For now, show a loading indicator. The CpmModelTransferClient will
		// populate the server tab when handleModelList() fires.
		Label lbl = new Label(gui, gui.i18nFormat("label.cpm.loading"));
		lbl.setBounds(new Box(5, 10, 0, 0));
		serverPanel.addElement(lbl);

		CpmModelTransferClient client = CpmModelTransferClient.getInstance(new CryptoService());
		client.removeModelListListener(this::populateServerModels);
		client.addModelListListener(this::populateServerModels);

		// Send the list request
		NetHandler<?, ?, ?> nh = MinecraftClientAccess.get().getNetHandler();
		if (nh != null) {
			nh.sendPacketToServer(new ModelListReqC2S());
		}
	}

	/**
	 * Called from CpmModelTransferClient.handleModelList() to populate the
	 * server models tab with the actual data. Runs on game thread for GUI safety.
	 */
	public void populateServerModels(NBTTagCompound data) {
		MinecraftClientAccess.get().executeOnGameThread(() -> {
			serverPanel.getElements().clear();
			serverEntries.clear();
			for (TextureProvider tex : serverModelIcons) {
				if (tex != null) tex.free();
			}
			serverModelIcons.clear();

			NBTTagList list = data.getTagList("models", 10); // 10 = NBTTagCompound type
			if (list == null || list.tagCount() == 0) {
				Label lbl = new Label(gui, gui.i18nFormat("label.cpm.myModels.noServer"));
				lbl.setBounds(new Box(5, 10, 0, 0));
				serverPanel.addElement(lbl);
				serverPanel.setBounds(new Box(0, 0, 410, 28));
				return;
			}

			int y = 0;
			for (int i = 0; i < list.tagCount(); i++) {
				NBTTagCompound entry = (NBTTagCompound) list.get(i);
				long id = entry.getLong("id");
				String name = entry.getString("name");
				int size = entry.getInteger("size");
				boolean isDefault = entry.getBoolean("default");
				long created = entry.getLong("created");
				byte[] iconData = entry.hasKey("icon") ? entry.getByteArray("icon") : null;

				ModelEntry model = new ModelEntry(name, size, id, created, "server", null);
				serverEntries.add(model);

				boolean hasIcon = iconData != null && iconData.length > 0;
				int iconW = hasIcon ? ENTRY_HEIGHT : 0;
				if (hasIcon) {
					try {
						Image img = Image.loadFrom(new ByteArrayInputStream(iconData));
						TextureProvider tex = new TextureProvider(img, new Vec2i(img.getWidth(), img.getHeight()));
						serverModelIcons.add(tex);
						ModelIconPanel iconPnl = new ModelIconPanel(gui, tex, ENTRY_HEIGHT - 4, ENTRY_HEIGHT - 4);
						iconPnl.setBounds(new Box(3, y + 2, ENTRY_HEIGHT - 4, ENTRY_HEIGHT - 4));
						serverPanel.addElement(iconPnl);
					} catch (Exception e) {
						Log.warn("Failed to load icon for model " + id + ": " + e.getMessage());
						hasIcon = false;
						iconW = 0;
					}
				}

				// Name label (with default indicator)
				String labelText = name + (isDefault ? gui.i18nFormat("label.cpm.myModels.defaultFlag") : "");
				Label nameLbl = new Label(gui, labelText);
				nameLbl.setBounds(new Box(5 + iconW, y, 200, 10));
				serverPanel.addElement(nameLbl);

				Label metaLbl = new Label(gui, formatSize(size) + " | " + formatTime(created));
				metaLbl.setBounds(new Box(5 + iconW, y + 12, 220, 10));
				serverPanel.addElement(metaLbl);

				int btnX = 230;
				Button activeBtn = new Button(gui, gui.i18nFormat("button.cpm.setActive"), () -> setServerActive(id));
				activeBtn.setBounds(new Box(btnX, y, 55, 20));
				serverPanel.addElement(activeBtn);
				btnX += 60;

				Button defBtn = new Button(gui, gui.i18nFormat("button.cpm.setDefault"), () -> setServerDefault(id));
				defBtn.setBounds(new Box(btnX, y, 55, 20));
				serverPanel.addElement(defBtn);
				btnX += 60;

				Button delBtn = new Button(gui, gui.i18nFormat("button.cpm.delete"), () -> deleteServer(id, model));
				delBtn.setBounds(new Box(btnX, y, 55, 20));
				serverPanel.addElement(delBtn);

				y += ENTRY_HEIGHT;
			}
			serverPanel.setBounds(new Box(0, 0, 410, Math.max(28, y)));
		});
	}

	private void setServerActive(long modelId) {
		NBTTagCompound tag = new NBTTagCompound();
		tag.setLong("mid", modelId);
		MinecraftClientAccess.get().getNetHandler().sendPacketToServer(new ModelSetActiveC2S(tag));
		frame.openPopup(new MessagePopup(frame, gui.i18nFormat("button.cpm.setActive"),
			gui.i18nFormat("label.cpm.modelSetActive", "#" + modelId)));
	}

	private void setServerDefault(long modelId) {
		NBTTagCompound tag = new NBTTagCompound();
		tag.setLong("mid", modelId);
		MinecraftClientAccess.get().getNetHandler().sendPacketToServer(new ModelSetDefaultC2S(tag));
		frame.openPopup(new MessagePopup(frame, gui.i18nFormat("button.cpm.setDefault"),
			gui.i18nFormat("label.cpm.modelSetDefault")));
	}

	private void deleteServer(long modelId, ModelEntry entry) {
		frame.openPopup(new ConfirmPopup(frame,
			gui.i18nFormat("label.cpm.myModels.confirmDelete", "#" + modelId), () -> {
				NBTTagCompound tag = new NBTTagCompound();
				tag.setLong("mid", modelId);
				MinecraftClientAccess.get().getNetHandler().sendPacketToServer(new ModelDeleteReqC2S(tag));
				serverEntries.remove(entry);
			}, null));
	}

	// ================================================================
	// Paste site models
	// ================================================================

	private void loadPasteModels() {
		// Paste site models are managed through the existing PastePopup.
		// For now, show a button to open the paste site browser.
		Label infoLbl = new Label(gui, gui.i18nFormat("label.cpm.myModels.pasteInfo"));
		infoLbl.setBounds(new Box(5, 10, 200, 10));
		pastePanel.addElement(infoLbl);

		Button openPasteBtn = new Button(gui, gui.i18nFormat("button.cpm.paste.openBrowser"), () -> {
			close();
			new PastePopup(frame).open();
		});
		openPasteBtn.setBounds(new Box(5, 30, 200, 20));
		pastePanel.addElement(openPasteBtn);

		pastePanel.setBounds(new Box(0, 0, 410, 60));
	}

	// ================================================================
	// Entry POJO
	// ================================================================

	private static class ModelEntry {
		final String name;
		final long size;
		final long id;
		final long timestamp;
		final String source; // "local", "server", "paste"
		final String pasteId;

		ModelEntry(String name, long size, long id, long timestamp, String source, String pasteId) {
			this.name = name;
			this.size = size;
			this.id = id;
			this.timestamp = timestamp;
			this.source = source;
			this.pasteId = pasteId;
		}
	}

	@Override
	public String getTitle() {
		return gui.i18nFormat("label.cpm.myModels.title");
	}

	public void open() {
		frame.openPopup(this);
	}

	@Override
	public void onClosed() {
		super.onClosed();
		CpmModelTransferClient.getInstance(new CryptoService()).removeModelListListener(this::populateServerModels);
		for (TextureProvider tex : serverModelIcons) {
			if (tex != null) tex.free();
		}
		serverModelIcons.clear();
	}

	// ================================================================
	// Icon panel element — renders a model icon in the server list
	// ================================================================

	private static class ModelIconPanel extends Panel {
		private final TextureProvider icon;
		private final int iconW, iconH;

		ModelIconPanel(IGui gui, TextureProvider icon, int w, int h) {
			super(gui);
			this.icon = icon;
			this.iconW = w;
			this.iconH = h;
		}

		@Override
		public void draw(MouseEvent event, float partialTicks) {
			if (icon != null) {
				icon.bind();
				gui.drawTexture(bounds.x, bounds.y, iconW, iconH, 0, 0, 1, 1);
			}
		}
	}
}
