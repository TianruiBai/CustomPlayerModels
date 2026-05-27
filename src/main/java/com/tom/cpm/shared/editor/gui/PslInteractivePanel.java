package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Tooltip;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslTrigger.TriggerType;
import com.tom.cpm.shared.psl.physics.PhysicsBone;

public class PslInteractivePanel extends Panel {
	private final Editor editor;
	private final EditorGui frm;
	private final Label targetLabel;
	private final Label parentLabel;
	private final Button useSelectedButton;
	private final Button showTargetButton;
	private final Button useParentButton;
	private final Button showParentButton;
	private final Button resetTriggerButton;
	private final Button resetPreviewButton;
	private final Checkbox gizmoCheckbox;
	private final Checkbox outlineCheckbox;
	private final Checkbox previewCheckbox;
	private final Checkbox previewPlayCheckbox;
	private final int formWidth;

	public PslInteractivePanel(IGui gui, EditorGui e) {
		this(gui, e, 360);
	}

	public PslInteractivePanel(IGui gui, EditorGui e, int width) {
		super(gui);
		this.editor = e.getEditor();
		this.frm = e;
		formWidth = Math.min(620, Math.max(360, width - 14));
		setBounds(new Box(0, 0, formWidth, 196));
		setBackgroundColor(gui.getColors().panel_background);
		new FlowLayout(this, 4, 2);

		targetLabel = new Label(gui, "");
		targetLabel.setBounds(new Box(4, 0, formWidth - 8, 12));
		addElement(targetLabel);

		Panel targetRow = new Panel(gui);
		targetRow.setBounds(new Box(0, 0, formWidth, 18));
		useSelectedButton = new Button(gui, gui.i18nFormat("button.cpm.psl.useSelected"), this::useSelectedTarget);
		useSelectedButton.setBounds(new Box(4, 0, 150, 18));
		useSelectedButton.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.useSelected")));
		targetRow.addElement(useSelectedButton);
		showTargetButton = new Button(gui, gui.i18nFormat("button.cpm.psl.showTarget"), this::showTarget);
		showTargetButton.setBounds(new Box(160, 0, 150, 18));
		showTargetButton.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.showTarget")));
		targetRow.addElement(showTargetButton);
		addElement(targetRow);

		parentLabel = new Label(gui, "");
		parentLabel.setBounds(new Box(4, 0, formWidth - 8, 12));
		addElement(parentLabel);

		Panel parentRow = new Panel(gui);
		parentRow.setBounds(new Box(0, 0, formWidth, 18));
		useParentButton = new Button(gui, gui.i18nFormat("button.cpm.psl.useParent"), this::useSelectedParent);
		useParentButton.setBounds(new Box(4, 0, 150, 18));
		useParentButton.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.useParent")));
		parentRow.addElement(useParentButton);
		showParentButton = new Button(gui, gui.i18nFormat("button.cpm.psl.showParent"), this::showParent);
		showParentButton.setBounds(new Box(160, 0, 150, 18));
		showParentButton.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.showParent")));
		parentRow.addElement(showParentButton);
		addElement(parentRow);

		Panel viewRow = new Panel(gui);
		viewRow.setBounds(new Box(0, 0, formWidth, 18));
		gizmoCheckbox = new Checkbox(gui, gui.i18nFormat("label.cpm.display.displayGizmo"));
		gizmoCheckbox.setBounds(new Box(4, 1, 150, 16));
		gizmoCheckbox.setAction(() -> {
			editor.displayGizmo.toggle();
			editor.updateGui.accept(null);
		});
		viewRow.addElement(gizmoCheckbox);
		outlineCheckbox = new Checkbox(gui, gui.i18nFormat("label.cpm.display.showOutlines"));
		outlineCheckbox.setBounds(new Box(160, 1, 150, 16));
		outlineCheckbox.setAction(() -> {
			editor.showOutlines.toggle();
			editor.updateGui.accept(null);
		});
		viewRow.addElement(outlineCheckbox);
		addElement(viewRow);

		Panel previewRow = new Panel(gui);
		previewRow.setBounds(new Box(0, 0, formWidth, 18));
		previewCheckbox = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.preview.enabled"));
		previewCheckbox.setBounds(new Box(4, 1, 150, 16));
		previewCheckbox.setAction(() -> {
			editor.pslPreviewEnabled = !editor.pslPreviewEnabled;
			previewCheckbox.setSelected(editor.pslPreviewEnabled);
			if(!editor.pslPreviewEnabled)editor.pslPreview.reset();
			editor.updateGui.accept(null);
		});
		previewRow.addElement(previewCheckbox);
		previewPlayCheckbox = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.preview.playing"));
		previewPlayCheckbox.setBounds(new Box(160, 1, 150, 16));
		previewPlayCheckbox.setAction(() -> {
			editor.pslPreviewPlaying = !editor.pslPreviewPlaying;
			previewPlayCheckbox.setSelected(editor.pslPreviewPlaying);
			editor.updateGui.accept(null);
		});
		previewRow.addElement(previewPlayCheckbox);
		addElement(previewRow);

		resetPreviewButton = new Button(gui, gui.i18nFormat("button.cpm.psl.preview.reset"), () -> {
			editor.pslPreview.reset();
			editor.updateGui.accept(null);
		});
		resetPreviewButton.setBounds(new Box(4, 0, 150, 18));
		addElement(resetPreviewButton);

		Button playSelectedButton = new Button(gui, gui.i18nFormat("button.cpm.psl.preview.playSelected"), () -> {
			if(editor.pslSystem != null && editor.selectedPslElement != null) {
				editor.pslSystem.tickSinglePreview(editor.selectedPslElement, editor.pslPreview.runtime, editor.pslPreview::targetPosition, 1/20f);
				editor.updateGui.accept(null);
			}
		});
		playSelectedButton.setBounds(new Box(160, 0, 150, 18));
		addElement(playSelectedButton);

		resetTriggerButton = new Button(gui, gui.i18nFormat("button.cpm.psl.resetTrigger"), this::resetTrigger);
		resetTriggerButton.setBounds(new Box(4, 0, 306, 18));
		resetTriggerButton.setTooltip(new Tooltip(frm, gui.i18nFormat("tooltip.cpm.psl.resetTrigger")));
		addElement(resetTriggerButton);
	}

	public void refresh() {
		PslElement element = editor.selectedPslElement;
		boolean hasSelection = element != null;
		setVisible(hasSelection);
		if(!hasSelection)return;

		targetLabel.setText(gui.i18nFormat("label.cpm.psl.target", PslUiUtil.describeTarget(editor, element.getElementId())));
		useSelectedButton.setEnabled(editor.getSelectedElement() != null);
		showTargetButton.setEnabled(element.getElementId() >= 0);
		resetTriggerButton.setEnabled(hasSelection);
		gizmoCheckbox.setSelected(editor.displayGizmo.get());
		outlineCheckbox.setSelected(editor.showOutlines.get());
		previewCheckbox.setSelected(editor.pslPreviewEnabled);
		previewPlayCheckbox.setSelected(editor.pslPreviewPlaying);
		previewPlayCheckbox.setEnabled(editor.pslPreviewEnabled);
		resetPreviewButton.setEnabled(editor.pslPreviewEnabled);

		boolean physics = element instanceof PhysicsBone;
		parentLabel.setVisible(physics);
		useParentButton.setVisible(physics);
		showParentButton.setVisible(physics);
		if(physics) {
			PhysicsBone bone = (PhysicsBone) element;
			parentLabel.setText(gui.i18nFormat("label.cpm.psl.parent", PslUiUtil.describeTarget(editor, bone.getParentElementId())));
			useParentButton.setEnabled(editor.getSelectedElement() != null);
			showParentButton.setEnabled(bone.getParentElementId() >= 0);
		}
	}

	private void useSelectedTarget() {
		PslElement element = editor.selectedPslElement;
		if(element == null)return;
		int id = PslUiUtil.getSelectedRuntimeId(editor);
		if(id < 0)return;
		element.setElementId(id);
		editor.markDirty();
		editor.updateGui.accept(null);
	}

	private void showTarget() {
		PslElement element = editor.selectedPslElement;
		if(element != null)PslUiUtil.selectElement(editor, element.getElementId());
	}

	private void useSelectedParent() {
		if(!(editor.selectedPslElement instanceof PhysicsBone))return;
		int id = PslUiUtil.getSelectedRuntimeId(editor);
		if(id < 0)return;
		((PhysicsBone) editor.selectedPslElement).setParentElementId(id);
		editor.markDirty();
		editor.updateGui.accept(null);
	}

	private void showParent() {
		if(editor.selectedPslElement instanceof PhysicsBone) {
			PslUiUtil.selectElement(editor, ((PhysicsBone) editor.selectedPslElement).getParentElementId());
		}
	}

	private void resetTrigger() {
		PslElement element = editor.selectedPslElement;
		if(element == null)return;
		element.getTrigger().setType(TriggerType.ALWAYS);
		editor.markDirty();
		editor.updateGui.accept(null);
	}
}
