package com.tom.cpm.shared.editor.project.loaders;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.math.Vec2i;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Generators;
import com.tom.cpm.shared.editor.TextureSlot;
import com.tom.cpm.shared.editor.anim.AnimatedTex;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.editor.project.IProject;
import com.tom.cpm.shared.editor.project.JsonList;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.ProjectPartLoader;
import com.tom.cpm.shared.editor.project.ProjectWriter;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.util.Log;

public class TexturesLoaderV1 implements ProjectPartLoader {

	@Override
	public String getId() {
		return "textures";
	}

	@Override
	public int getVersion() {
		return 1;
	}

	@Override
	public void load(Editor editor, IProject project) throws IOException {
		JsonMap data = project.getJson("config.json");
		JsonMap texDt = data.getMap("textures");
		for(TextureSheetType tex : TextureSheetType.VALUES) {
			String name = tex.name().toLowerCase(Locale.ROOT);
			if(!tex.editable) {
				if(data.getBoolean(name + "Tex", false) || project.getEntry(name + ".png") != null) {
					ETextures eTex = editor.textures.get(tex);
					if(eTex == null)eTex = new ETextures(editor, tex);
					editor.textures.put(tex, eTex);
					eTex.provider.size = tex.getDefSize();
					Image def = new Image(eTex.provider.size.x, eTex.provider.size.y);
					try(InputStream is = ModelDefinitionLoader.class.getResourceAsStream("/assets/cpm/textures/template/" + tex.name().toLowerCase(Locale.ROOT) + ".png")) {
						def = Image.loadFrom(is);
					} catch (IOException e) {
					}
					eTex.setDefaultImg(def);
					eTex.setImage(new Image(def));
					eTex.setChangedLocally(false);
					eTex.markDirty();
				}
			} else {
				Image img = project.getIfExists(name + ".png", Image::loadFrom);
				if(img != null) {
					if(img.getWidth() > ETextures.MAX_TEX_SIZE || img.getHeight() > ETextures.MAX_TEX_SIZE) {
						Log.error("Illegal image size for texture: " + name);
						continue;
					}
					ETextures eTex = editor.textures.get(tex);
					if(eTex == null)eTex = new ETextures(editor, tex);
					editor.textures.put(tex, eTex);
					eTex.setImage(img);
					eTex.setChangedLocally(false);
					eTex.markDirty();
					JsonMap skinTexSize = data.getMap(name + "Size");
					eTex.provider.size = new Vec2i(skinTexSize, tex.getDefSize());
					boolean customGridSize = img.getWidth() != eTex.provider.size.x || img.getHeight() != eTex.provider.size.y;
					if(texDt != null && texDt.containsKey(name)) {
						JsonMap dt = texDt.getMap(name);
						loadAnimatedTexture(editor, dt, eTex, tex);
						eTex.customGridSize = dt.getBoolean("customGridSize", customGridSize);
					} else {
						eTex.customGridSize = customGridSize;
					}
				}
			}
		}
		Set<RootGroups> groups = new HashSet<>();
		for(ModelElement e : editor.elements) {
			for (RootModelType rmt : RootModelType.VALUES) {
				if(e.typeData == rmt)
					groups.add(RootGroups.getGroup(rmt));
			}
		}
		groups.forEach(g -> Generators.loadTextures(editor, g, editor.textures::put));

		// Load texture slots (multi-texture system, Phase 6+)
		JsonList slotList = data.getList("textureSlots");
		if (slotList != null && slotList.size() > 0) {
			editor.textureSlots.clear();
			for (int i = 0; i < slotList.size(); i++) {
				JsonMap slotMap = slotList.getMap(i);
				if (slotMap == null) continue;
				TextureSlot slot = TextureSlot.fromMap(slotMap);
				// Load the slot's PNG image (slot 0 is skin.png, rest are skin_i.png)
				String pngPath = i == 0 ? "skin.png" : "skin_" + i + ".png";
				Image slotImg = project.getIfExists(pngPath, Image::loadFrom);
				if (slotImg != null) {
					slot.image = slotImg;
				}
				editor.textureSlots.add(slot);
			}
			// Restore active slot index
			editor.activeTextureSlot = data.getInt("activeTextureSlot", 0);
			if (editor.activeTextureSlot >= editor.textureSlots.size())
				editor.activeTextureSlot = 0;
			Log.info("[TexturesLoader] Loaded " + editor.textureSlots.size() + " texture slots");
		}
	}

	@Override
	public void save(Editor editor, ProjectWriter project) throws IOException {
		JsonMap data = project.getJson("config.json");
		JsonMap texDt = data.putMap("textures");
		for(TextureSheetType tex : TextureSheetType.VALUES) {
			String name = tex.name().toLowerCase(Locale.ROOT);
			ETextures eTex = editor.textures.get(tex);
			if(eTex != null) {
				if(eTex.isEditable()) {
					Map<String, Object> size = new HashMap<>();
					data.put(name + "Size", size);
					size.put("x", eTex.provider.size.x);
					size.put("y", eTex.provider.size.y);
					if(eTex.provider.texture != null && eTex.isEdited()) {
						project.putFile(name + ".png", eTex.getImage(), Image::storeTo);
					}
					JsonMap dt = texDt.putMap(name);
					dt.put("customGridSize", eTex.customGridSize);
					saveAnimatedTexture(dt, eTex);
				} else {
					data.put(name + "Tex", true);
				}
			} else {
				project.delete(name + ".png");
			}
		}

		// Save texture slots (multi-texture system, Phase 6+)
		if (editor.textureSlots.size() > 1) {
			JsonList slotList = data.putList("textureSlots");
			for (int i = 0; i < editor.textureSlots.size(); i++) {
				TextureSlot slot = editor.textureSlots.get(i);
				JsonMap slotMap = slotList.addMap();
				slotMap.put("name", slot.name);
				slotMap.put("gridSizeX", slot.gridSize.x);
				slotMap.put("gridSizeY", slot.gridSize.y);
				slotMap.put("customGridSize", slot.customGridSize);
				// Save slot PNG (slot 0 is already saved as skin.png)
				if (i > 0 && slot.image != null) {
					final int idx = i;
					project.putFile("skin_" + idx + ".png", slot.image, Image::storeTo);
				}
			}
			data.put("activeTextureSlot", editor.activeTextureSlot);
		}
	}

	protected void loadAnimatedTexture(Editor editor, JsonMap dt, ETextures tex, TextureSheetType sheet) {
		JsonList list = dt.getList("anim");
		list.forEachMap(elem -> tex.animatedTexs.add(new AnimatedTex(editor, sheet, elem)));
	}

	protected void saveAnimatedTexture(JsonMap dt, ETextures tex) {
		JsonList list = dt.putList("anim");
		tex.animatedTexs.forEach(t -> t.save(list.addMap()));
	}
}
