package com.tom.cpm.shared.gui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.MessagePopup;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.PopupPanel;
import com.tom.cpl.gui.elements.ScrollPanel;
import com.tom.cpl.gui.util.TabbedPanelManager;
import com.tom.cpl.math.Box;
import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpl.nbt.NBTTagList;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.MinecraftClientAccess.ServerStatus;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.network.packet.ModelDeleteReqC2S;
import com.tom.cpm.shared.network.packet.ModelDownloadReqC2S;
import com.tom.cpm.shared.network.packet.ModelListReqC2S;
import com.tom.cpm.shared.network.packet.ModelSetActiveC2S;
import com.tom.cpm.shared.network.packet.ModelSetDefaultC2S;
import com.tom.cpm.shared.paste.PasteClient;
import com.tom.cpm.shared.paste.PastePopup;
import com.tom.cpm.shared.util.Log;

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

	private final Frame frame;
	private final TabbedPanelManager tabs;
	private ScrollPanel localScp, serverScp, pasteScp;
	private Panel localPanel, serverPanel, pastePanel;
	private List<ModelEntry> localEntries = new ArrayList<>();
	private List<ModelEntry> serverEntries = new ArrayList<>();
	private List<ModelEntry> pasteEntries = new ArrayList<>();

	public MyModelsPopup(Frame frame) {
		super(frame.getGui());
		this.frame = frame;
		setBounds(new Box(0, 0, 360, 280));

		tabs = new TabbedPanelManager(gui);
		tabs.setBounds(new Box(0, 20, bounds.w, bounds.h - 20));
		addElement(tabs);

		// ---- Local Models Tab ----
		localPanel = new Panel(gui);
		localPanel.setBackgroundColor(gui.getColors().button_border);
		localScp = new ScrollPanel(gui);
		localScp.setBounds(new Box(5, 5, 350, bounds.h - 30));
		localScp.setDisplay(localPanel);
		addTab("local", localScp);

		// ---- Server Models Tab ----
		serverPanel = new Panel(gui);
		serverPanel.setBackgroundColor(gui.getColors().button_border);
		serverScp = new ScrollPanel(gui);
		serverScp.setBounds(new Box(5, 5, 350, bounds.h - 30));
		serverScp.setDisplay(serverPanel);
		addTab("server", serverScp);

		// ---- Paste Site Tab ----
		pastePanel = new Panel(gui);
		pastePanel.setBackgroundColor(gui.getColors().button_border);
		pasteScp = new ScrollPanel(gui);
		pasteScp.setBounds(new Box(5, 5, 350, bounds.h - 30));
		pasteScp.setDisplay(pastePanel);
		addTab("paste", pasteScp);

		loadLocalModels();
		loadServerModels();
		loadPasteModels();
	}

	private void addTab(String key, ScrollPanel scp) {
		Panel tabPanel = new Panel(gui);
		tabPanel.setBounds(new Box(0, 0, 360, bounds.h - 20));
		tabPanel.addElement(scp);
		tabs.createTab(gui.i18nFormat("label.cpm.myModels.tab." + key), tabPanel);
	}

	// ================================================================
	// Local models
	// ================================================================

	private void loadLocalModels() {
		File modelsDir = new File(MinecraftClientAccess.get().getGameDir(), "player_models");
		File[] files = modelsDir.exists() ? modelsDir.listFiles((f, n) -> n.endsWith(".cpmmodel")) : null;
		if (files == null || files.length == 0) {
			Label lbl = new Label(gui, gui.i18nFormat("label.cpm.myModels.noLocal"));
			lbl.setBounds(new Box(5, 10, 0, 0));
			localPanel.addElement(lbl);
			return;
		}

		int y = 0;
		for (File f : files) {
			ModelEntry entry = new ModelEntry(f.getName(), f.length(), 0, f.lastModified(), "local", null);
			localEntries.add(entry);

			Label nameLbl = new Label(gui, f.getName());
			nameLbl.setBounds(new Box(5, y, 200, 10));
			localPanel.addElement(nameLbl);

			Button setBtn = new Button(gui, gui.i18nFormat("button.cpm.setActive"), () -> setLocalActive(f));
			setBtn.setBounds(new Box(210, y, 60, 16));
			localPanel.addElement(setBtn);

			Button delBtn = new Button(gui, gui.i18nFormat("button.cpm.delete"), () -> deleteLocal(f, entry));
			delBtn.setBounds(new Box(275, y, 50, 16));
			localPanel.addElement(delBtn);

			y += ENTRY_HEIGHT;
		}
		localPanel.setBounds(new Box(0, 0, 350, y));
	}

	private void setLocalActive(File f) {
		ModConfig.getCommonConfig().setString(ConfigKeys.SELECTED_MODEL, f.getName());
		MinecraftClientAccess.get().getNetHandler().sendSkinData();
		frame.openPopup(new MessagePopup(frame, gui.i18nFormat("label.cpm.export_success"),
			gui.i18nFormat("label.cpm.modelSetActive", f.getName())));
	}

	private void deleteLocal(File f, ModelEntry entry) {
		if (f.delete()) {
			localEntries.remove(entry);
			localPanel.getElements().clear();
			loadLocalModels(); // refresh
		}
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
			return;
		}

		// The model list will arrive asynchronously via ModelListResS2C.
		// For now, show a loading indicator. The CpmModelTransferClient will
		// populate the server tab when handleModelList() fires.
		Label lbl = new Label(gui, gui.i18nFormat("label.cpm.loading"));
		lbl.setBounds(new Box(5, 10, 0, 0));
		serverPanel.addElement(lbl);

		// Send the list request
		NetHandler<?, ?, ?> nh = MinecraftClientAccess.get().getNetHandler();
		if (nh != null) {
			nh.sendPacketToServer(new ModelListReqC2S());
		}
	}

	/**
	 * Called from CpmModelTransferClient.handleModelList() to populate the
	 * server models tab with the actual data.
	 */
	public void populateServerModels(NBTTagCompound data) {
		serverPanel.getElements().clear();
		serverEntries.clear();

		NBTTagList list = data.getTagList("models", 10); // 10 = NBTTagCompound type
		if (list == null || list.tagCount() == 0) {
			Label lbl = new Label(gui, gui.i18nFormat("label.cpm.myModels.noServer"));
			lbl.setBounds(new Box(5, 10, 0, 0));
			serverPanel.addElement(lbl);
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

			ModelEntry model = new ModelEntry(name, size, id, created, "server", null);
			serverEntries.add(model);

			// Name label (with default indicator)
			String labelText = name + (isDefault ? gui.i18nFormat("label.cpm.myModels.defaultFlag") : "");
			Label nameLbl = new Label(gui, labelText);
			nameLbl.setBounds(new Box(5, y, 180, 10));
			serverPanel.addElement(nameLbl);

			int btnX = 190;
			Button activeBtn = new Button(gui, gui.i18nFormat("button.cpm.setActive"), () -> setServerActive(id));
			activeBtn.setBounds(new Box(btnX, y, 45, 16));
			serverPanel.addElement(activeBtn);
			btnX += 50;

			Button defBtn = new Button(gui, gui.i18nFormat("button.cpm.setDefault"), () -> setServerDefault(id));
			defBtn.setBounds(new Box(btnX, y, 45, 16));
			serverPanel.addElement(defBtn);
			btnX += 50;

			Button delBtn = new Button(gui, gui.i18nFormat("button.cpm.delete"), () -> deleteServer(id, model));
			delBtn.setBounds(new Box(btnX, y, 50, 16));
			serverPanel.addElement(delBtn);

			y += ENTRY_HEIGHT;
		}
		serverPanel.setBounds(new Box(0, 0, 350, y));
	}

	private void setServerActive(long modelId) {
		NBTTagCompound tag = new NBTTagCompound();
		tag.setLong("modelId", modelId);
		MinecraftClientAccess.get().getNetHandler().sendPacketToServer(new ModelSetActiveC2S(tag));
	}

	private void setServerDefault(long modelId) {
		NBTTagCompound tag = new NBTTagCompound();
		tag.setLong("modelId", modelId);
		MinecraftClientAccess.get().getNetHandler().sendPacketToServer(new ModelSetDefaultC2S(tag));
	}

	private void deleteServer(long modelId, ModelEntry entry) {
		NBTTagCompound tag = new NBTTagCompound();
		tag.setLong("modelId", modelId);
		MinecraftClientAccess.get().getNetHandler().sendPacketToServer(new ModelDeleteReqC2S(tag));
		serverEntries.remove(entry);
		// Refresh will happen when ModelDeleteResultS2C arrives
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

		pastePanel.setBounds(new Box(0, 0, 350, 60));
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
}
