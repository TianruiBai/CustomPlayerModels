package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.elements.TextField;
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
	private final Label typeLabel;

	public PslElementPropertiesPanel(IGui gui, EditorGui e) {
		super(gui);
		editor = e.getEditor();
		setBounds(new Box(0, 0, 170, 76));
		setBackgroundColor(gui.getColors().panel_background);
		new FlowLayout(this, 3, 1);

		typeLabel = new Label(gui, "");
		typeLabel.setBounds(new Box(2, 0, 164, 12));
		addElement(typeLabel);

		Label nameLabel = new Label(gui, gui.i18nFormat("label.cpm.psl.common.name"));
		nameLabel.setBounds(new Box(2, 0, 164, 12));
		addElement(nameLabel);

		nameField = new TextField(gui);
		nameField.setBounds(new Box(2, 0, 164, 18));
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
		targetLabel.setBounds(new Box(2, 0, 164, 12));
		addElement(targetLabel);

		elementIdSpinner = new Spinner(gui);
		elementIdSpinner.setBounds(new Box(2, 0, 164, 18));
		elementIdSpinner.setDp(0);
		elementIdSpinner.addChangeListener(() -> {
			PslElement element = editor.selectedPslElement;
			if(element != null) {
				element.setElementId((int) elementIdSpinner.getValue());
				editor.markDirty();
			}
		});
		addElement(elementIdSpinner);
	}

	public void refresh() {
		PslElement element = editor.selectedPslElement;
		boolean enabled = element != null;
		setVisible(enabled);
		nameField.setEnabled(enabled);
		elementIdSpinner.setEnabled(enabled);
		if(enabled) {
			typeLabel.setText(element.getType().name());
			nameField.setText(element.getName() != null ? element.getName() : "");
			elementIdSpinner.setValue(element.getElementId());
		}
	}
}
