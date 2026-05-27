package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.elements.TextField;
import com.tom.cpl.gui.elements.Tooltip;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.PslElement;

/**
 * Common PSL fields shared by every element type.
 */
public class PslElementPropertiesPanel extends Panel {
	private final Editor editor;
	private final TextField nameField;
	private final Spinner elementIdSpinner;
	private final Label typeLabel, targetDisplayLabel;
	private final Button useSelectedButton, showTargetButton;
	private final int formWidth;

	public PslElementPropertiesPanel(IGui gui, EditorGui e) {
		this(gui, e, 360);
	}

	public PslElementPropertiesPanel(IGui gui, EditorGui e, int width) {
		super(gui);
		editor = e.getEditor();
		formWidth = Math.min(620, Math.max(360, width - 14));
		setBounds(new Box(0, 0, formWidth, 112));
		setBackgroundColor(gui.getColors().panel_background);
		new FlowLayout(this, 3, 1);

		typeLabel = new Label(gui, "");
		typeLabel.setBounds(new Box(2, 0, formWidth - 6, 12));
		addElement(typeLabel);

		Label nameLabel = new Label(gui, gui.i18nFormat("label.cpm.psl.common.name"));
		nameLabel.setBounds(new Box(2, 0, formWidth - 6, 12));
		addElement(nameLabel);

		nameField = new TextField(gui);
		nameField.setBounds(new Box(2, 0, formWidth - 6, 18));
		nameField.setEventListener(() -> {
			PslElement element = editor.selectedPslElement;
			if(element != null) {
				element.setName(nameField.getText());
				editor.markDirty();
				editor.updateGui.accept(null);
			}
		});
		addElement(nameField);

		Label targetLabel = new Label(gui, gui.i18nFormat("label.cpm.psl.common.elementId"));
		targetLabel.setBounds(new Box(2, 0, formWidth - 6, 12));
		addElement(targetLabel);

		targetDisplayLabel = new Label(gui, "");
		targetDisplayLabel.setBounds(new Box(2, 0, formWidth - 6, 12));
		addElement(targetDisplayLabel);

		Panel targetRow = new Panel(gui);
		targetRow.setBounds(new Box(0, 0, formWidth, 18));

		elementIdSpinner = new Spinner(gui);
		elementIdSpinner.setBounds(new Box(2, 0, 96, 18));
		elementIdSpinner.setDp(0);
		elementIdSpinner.addChangeListener(() -> {
			PslElement element = editor.selectedPslElement;
			if(element != null) {
				element.setElementId((int) elementIdSpinner.getValue());
				editor.markDirty();
				editor.updateGui.accept(null);
			}
		});
		targetRow.addElement(elementIdSpinner);

		useSelectedButton = new Button(gui, gui.i18nFormat("button.cpm.psl.useSelected"), this::useSelectedTarget);
		useSelectedButton.setBounds(new Box(104, 0, 116, 18));
		useSelectedButton.setTooltip(new Tooltip(e, gui.i18nFormat("tooltip.cpm.psl.useSelected")));
		targetRow.addElement(useSelectedButton);

		showTargetButton = new Button(gui, gui.i18nFormat("button.cpm.psl.showTarget"), this::showTarget);
		showTargetButton.setBounds(new Box(224, 0, 116, 18));
		showTargetButton.setTooltip(new Tooltip(e, gui.i18nFormat("tooltip.cpm.psl.showTarget")));
		targetRow.addElement(showTargetButton);
		addElement(targetRow);
	}

	public void refresh() {
		PslElement element = editor.selectedPslElement;
		boolean enabled = element != null;
		setVisible(enabled);
		nameField.setEnabled(enabled);
		elementIdSpinner.setEnabled(enabled);
		useSelectedButton.setEnabled(enabled && editor.getSelectedElement() != null);
		showTargetButton.setEnabled(enabled && element.getElementId() >= 0);
		if(enabled) {
			typeLabel.setText(element.getType().name());
			nameField.setText(element.getName() != null ? element.getName() : "");
			elementIdSpinner.setValue(element.getElementId());
			targetDisplayLabel.setText(gui.i18nFormat("label.cpm.psl.target", PslUiUtil.describeTarget(editor, element.getElementId())));
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
}
