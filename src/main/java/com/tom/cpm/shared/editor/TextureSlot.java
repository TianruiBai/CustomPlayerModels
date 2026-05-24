package com.tom.cpm.shared.editor;

import java.util.HashMap;
import java.util.Map;

import com.tom.cpl.math.Vec2i;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.editor.project.JsonMap;

/**
 * Represents a single texture slot in the multi-texture system.
 * Each slot holds a named texture image that can be switched at runtime
 * via {@link AnimationType#TEXTURE} animations or manually in the editor.
 *
 * <p>Slot 0 is always the default/primary texture for backward compatibility.
 */
public class TextureSlot {
	public String name;
	public Image image;
	public Vec2i gridSize;
	public boolean customGridSize;

	public TextureSlot() {
		this.name = "Default";
		this.gridSize = new Vec2i(64, 64);
		this.customGridSize = false;
	}

	public TextureSlot(String name, Image image, Vec2i gridSize, boolean customGridSize) {
		this.name = name;
		this.image = image;
		this.gridSize = gridSize != null ? gridSize : new Vec2i(image.getWidth(), image.getHeight());
		this.customGridSize = customGridSize;
	}

	public TextureSlot(TextureSlot cpy) {
		this.name = cpy.name;
		this.image = new Image(cpy.image);
		this.gridSize = new Vec2i(cpy.gridSize);
		this.customGridSize = cpy.customGridSize;
	}

	/** Serialize to project JSON format. */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("name", name);
		map.put("gridSizeX", gridSize.x);
		map.put("gridSizeY", gridSize.y);
		map.put("customGridSize", customGridSize);
		return map;
	}

	/** Deserialize from project JSON format. */
	public static TextureSlot fromMap(JsonMap map) {
		TextureSlot slot = new TextureSlot();
		slot.name = map.getString("name", "Unnamed");
		int sx = map.getInt("gridSizeX", 64);
		int sy = map.getInt("gridSizeY", 64);
		slot.gridSize = new Vec2i(sx, sy);
		slot.customGridSize = map.getBoolean("customGridSize", false);
		return slot;
	}
}
