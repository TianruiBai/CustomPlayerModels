package com.tom.cpm.shared.editor.util;

import java.util.UUID;

import com.tom.cpl.util.Image;
import com.tom.cpm.shared.gui.ViewportCamera;

public class ModelDescription {
	public String name = "";
	public String desc = "";
	public Image icon;
	public ViewportCamera camera = new ViewportCamera();
	public CopyProtection copyProtection = CopyProtection.NORMAL;
	public UUID uuid;

	/** Server model ID — set when this model is uploaded to/downloaded from the built-in server.
	 *  Null means the model has no server association. Non-null means it exists on the server
	 *  and can be updated via "Update on Server" in the export dialog. */
	public Long serverModelId;

	/** Paste/Gist link — set when loading a model that was previously shared via paste site.
	 *  Used by ExportPopup to enable the "Export Definition" (update existing) button. */
	public String pasteLink;

	public static enum CopyProtection {
		NORMAL,
		UUID_LOCK,
		CLONEABLE
		;
		public static final CopyProtection[] VALUES = values();

		public static CopyProtection lookup(String name) {
			for (CopyProtection t : VALUES) {
				if(t.name().equalsIgnoreCase(name))return t;
			}
			return null;
		}
	}
}
