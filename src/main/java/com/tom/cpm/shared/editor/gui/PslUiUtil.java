package com.tom.cpm.shared.editor.gui;

import java.util.ArrayList;

import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.util.ExportHelper;
import com.tom.cpm.shared.model.Cube;

final class PslUiUtil {
	private PslUiUtil() {
	}

	public static int getSelectedRuntimeId(Editor editor) {
		ModelElement selected = editor.getSelectedElement();
		if(selected == null)return -1;
		refreshRuntimeIds(editor);
		return selected.id;
	}

	public static ModelElement findElement(Editor editor, int id) {
		refreshRuntimeIds(editor);
		ModelElement[] found = new ModelElement[1];
		Editor.walkElements(editor.elements, element -> {
			if(found[0] == null && element.id == id)found[0] = element;
		});
		return found[0];
	}

	public static String describeTarget(Editor editor, int id) {
		if(id < 0)return editor.ui.i18nFormat("label.cpm.psl.target.none");
		ModelElement element = findElement(editor, id);
		if(element == null)return editor.ui.i18nFormat("label.cpm.psl.target.missing", id);
		String name = element.getElemName();
		if(name == null || name.isEmpty())name = element.type.name();
		return editor.ui.i18nFormat("label.cpm.psl.target.named", name, id);
	}

	public static boolean selectElement(Editor editor, int id) {
		ModelElement element = findElement(editor, id);
		if(element == null)return false;
		editor.selectedElement = element;
		editor.updateGui.accept(null);
		return true;
	}

	private static void refreshRuntimeIds(Editor editor) {
		ExportHelper.flattenElements(editor.elements, new int[] {10}, new ArrayList<Cube>());
	}
}
