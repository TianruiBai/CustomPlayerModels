package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.ScrollPanel;
import com.tom.cpl.gui.util.HorizontalLayout;
import com.tom.cpl.gui.util.TabbedPanelManager;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;

public class PslInspectorPanel extends Panel {
	private final Editor editor;
	private final Label selectedLabel;
	private final Label targetLabel;
	private final PslElementPropertiesPanel commonProps;
	private final PslSettingsPanel settingsPanel;
	private final PslTriggerEditor triggerEditor;
	private final PslInteractivePanel interactivePanel;

	public PslInspectorPanel(IGui gui, EditorGui e, int width, int height) {
		super(gui);
		this.editor = e.getEditor();
		setBounds(new Box(0, 0, width, height));
		setBackgroundColor(gui.getColors().panel_background);

		Panel header = new Panel(gui);
		header.setBounds(new Box(0, 0, width, 22));
		header.setBackgroundColor(gui.getColors().menu_bar_background);
		selectedLabel = new Label(gui, "");
		selectedLabel.setBounds(new Box(5, 5, Math.max(120, width / 2 - 10), 12));
		header.addElement(selectedLabel);
		targetLabel = new Label(gui, "");
		targetLabel.setBounds(new Box(Math.max(130, width / 2), 5, Math.max(120, width / 2 - 10), 12));
		header.addElement(targetLabel);
		addElement(header);

		Panel tabButtons = new Panel(gui);
		tabButtons.setBounds(new Box(0, 22, width, 20));
		tabButtons.setBackgroundColor(gui.getColors().menu_bar_background);
		HorizontalLayout buttons = new HorizontalLayout(tabButtons);
		TabbedPanelManager tabs = new TabbedPanelManager(gui);

		Panel commonTab = new Panel(gui);
		commonTab.setBounds(new Box(0, 0, width, height - 42));
		ScrollPanel commonScroll = new ScrollPanel(gui);
		commonScroll.setBounds(new Box(0, 0, width, height - 42));
		commonScroll.setScrollBarSide(true);
		commonProps = new PslElementPropertiesPanel(gui, e, width);
		commonScroll.setDisplay(commonProps);
		commonTab.addElement(commonScroll);

		Panel settingsTab = new Panel(gui);
		settingsTab.setBounds(new Box(0, 0, width, height - 42));
		ScrollPanel settingsScroll = new ScrollPanel(gui);
		settingsScroll.setBounds(new Box(0, 0, width, height - 42));
		settingsScroll.setScrollBarSide(true);
		settingsPanel = new PslSettingsPanel(gui, e, width);
		settingsScroll.setDisplay(settingsPanel);
		settingsTab.addElement(settingsScroll);

		Panel triggerTab = new Panel(gui);
		triggerTab.setBounds(new Box(0, 0, width, height - 42));
		ScrollPanel triggerScroll = new ScrollPanel(gui);
		triggerScroll.setBounds(new Box(0, 0, width, height - 42));
		triggerScroll.setScrollBarSide(true);
		triggerEditor = new PslTriggerEditor(gui, e, width);
		triggerScroll.setDisplay(triggerEditor);
		triggerTab.addElement(triggerScroll);

		Panel toolsTab = new Panel(gui);
		toolsTab.setBounds(new Box(0, 0, width, height - 42));
		ScrollPanel toolsScroll = new ScrollPanel(gui);
		toolsScroll.setBounds(new Box(0, 0, width, height - 42));
		toolsScroll.setScrollBarSide(true);
		interactivePanel = new PslInteractivePanel(gui, e, width);
		toolsScroll.setDisplay(interactivePanel);
		toolsTab.addElement(toolsScroll);

		buttons.add(tabs.createTab(gui.i18nFormat("label.cpm.psl.common"), commonTab));
		buttons.add(tabs.createTab(gui.i18nFormat("label.cpm.psl.settings"), settingsTab));
		buttons.add(tabs.createTab(gui.i18nFormat("label.cpm.psl.trigger"), triggerTab));
		buttons.add(tabs.createTab(gui.i18nFormat("label.cpm.psl.tools"), toolsTab));
		tabs.setBounds(new Box(0, 42, width, height - 42));
		addElement(tabs);
		addElement(tabButtons);

		editor.updateGui.add(this::refresh);
		refresh();
	}

	private void refresh() {
		PslElement selected = editor.selectedPslElement;
		boolean hasSelection = selected != null;
		if(hasSelection) {
			String name = selected.getName() != null && !selected.getName().isEmpty() ? selected.getName() : selected.getType().name();
			selectedLabel.setText(gui.i18nFormat("label.cpm.psl.inspector.selected", selected.getType().name(), name) + describePreviewTarget(selected));
			targetLabel.setText(gui.i18nFormat("label.cpm.psl.target", PslUiUtil.describeTarget(editor, selected.getElementId())));
		} else {
			selectedLabel.setText(gui.i18nFormat("label.cpm.psl.noSelection"));
			targetLabel.setText("");
		}

		commonProps.refresh();
		settingsPanel.refresh();
		triggerEditor.refresh();
		interactivePanel.refresh();
	}

	private String describePreviewTarget(PslElement selected) {
		if(!(selected instanceof ParticleEmitter))return "";
		ParticleEmitter emitter = (ParticleEmitter) selected;
		String particleId = emitter.isMinecraftParticle() ? emitter.getMinecraftParticle() : emitter.getTextureName();
		if(particleId == null || particleId.isEmpty())particleId = gui.i18nFormat("label.cpm.psl.target.none");
		return " [" + particleId + "]";
	}
}
