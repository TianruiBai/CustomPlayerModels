package com.tom.cpm.shared.parts;

import java.io.IOException;

import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.skin.TextureProvider;

/**
 * Stores a texture for a specific slot index in the multi-texture system.
 * Slot 0 corresponds to the default SKIN texture; slots 1+ are alternates.
 *
 * <p>At runtime, {@link ModelDefinition#setActiveTextureSlot(int)} selects
 * which slot's texture is used for rendering. This is driven by
 * {@link com.tom.cpm.shared.animation.AnimationType#TEXTURE} animations.
 */
public class ModelPartTextureSlot implements IModelPart, IResolvedModelPart {
	private int slotIndex;
	private TextureProvider image;

	public ModelPartTextureSlot(IOHelper in, ModelDefinition def) throws IOException {
		slotIndex = in.readVarInt();
		image = new TextureProvider(in, def);
	}

	public ModelPartTextureSlot(int slotIndex, TextureProvider image) {
		this.slotIndex = slotIndex;
		this.image = image;
	}

	@Override
	public IResolvedModelPart resolve() throws IOException {
		return this;
	}

	@Override
	public void preApply(ModelDefinition def) {
		def.addTextureSlot(slotIndex, image);
	}

	@Override
	public void write(IOHelper dout) throws IOException {
		dout.writeVarInt(slotIndex);
		image.write(dout);
	}

	@Override
	public ModelPartType getType() {
		return ModelPartType.TEXTURE_SLOT;
	}

	public int getSlotIndex() {
		return slotIndex;
	}

	public TextureProvider getImage() {
		return image;
	}
}
