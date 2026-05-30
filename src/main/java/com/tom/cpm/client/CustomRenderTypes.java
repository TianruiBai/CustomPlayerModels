package com.tom.cpm.client;

import java.util.OptionalDouble;

import net.irisshaders.batchedentityrendering.impl.BlendingStateHolder;
import net.irisshaders.batchedentityrendering.impl.TransparencyType;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;

public class CustomRenderTypes extends RenderType {
	public static final RenderType ENTITY_COLOR = entityTranslucent(ResourceLocation.parse("textures/misc/white.png"));
	public static final RenderType LINES_NO_DEPTH = create("cpm:lines_no_depth", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 256, false, true, RenderType.CompositeState.builder().setShaderState(RENDERTYPE_LINES_SHADER).setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty())).setLayeringState(VIEW_OFFSET_Z_LAYERING).setTransparencyState(TRANSLUCENT_TRANSPARENCY).setOutputState(ITEM_ENTITY_TARGET).setWriteMaskState(COLOR_DEPTH_WRITE).setCullState(NO_CULL).setDepthTestState(NO_DEPTH_TEST).createCompositeState(false));
	public static final RenderType ENTITY_COLOR_EYES = eyes(ResourceLocation.parse("textures/misc/white.png"));

	/** PSL particle render type: translucent, depth-tested but does NOT write depth, so particles don't z-fight with the model. */
	public static RenderType pslParticle(ResourceLocation texture) {
		return create("cpm:psl_particle", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true,
			RenderType.CompositeState.builder()
				.setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
				.setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
				.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
				.setCullState(NO_CULL)
				.setLightmapState(LIGHTMAP)
				.setOverlayState(OVERLAY)
				.setDepthTestState(LEQUAL_DEPTH_TEST)
				.setWriteMaskState(COLOR_WRITE)
				.createCompositeState(false));
	}

	/** Additive-blend variant of {@link #pslParticle(ResourceLocation)}. */
	public static RenderType pslParticleAdditive(ResourceLocation texture) {
		return create("cpm:psl_particle_additive", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true,
			RenderType.CompositeState.builder()
				.setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
				.setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
				.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
				.setCullState(NO_CULL)
				.setLightmapState(LIGHTMAP)
				.setOverlayState(OVERLAY)
				.setDepthTestState(LEQUAL_DEPTH_TEST)
				.setWriteMaskState(COLOR_WRITE)
				.createCompositeState(false));
	}

	/** PSL light glow render type: additive blend, no depth write, fullbright. */
	public static RenderType pslLightAdditive(ResourceLocation texture) {
		return create("cpm:psl_light_glow", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true,
			RenderType.CompositeState.builder()
				.setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
				.setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
				.setTransparencyState(ADDITIVE_TRANSPARENCY)
				.setCullState(NO_CULL)
				.setLightmapState(LIGHTMAP)
				.setOverlayState(OVERLAY)
				.setDepthTestState(NO_DEPTH_TEST)
				.setWriteMaskState(COLOR_WRITE)
				.createCompositeState(false));
	}

	public CustomRenderTypes(String nameIn, VertexFormat formatIn, Mode drawModeIn, int bufferSizeIn,
			boolean useDelegateIn, boolean needsSortingIn, Runnable setupTaskIn, Runnable clearTaskIn) {
		super(nameIn, formatIn, drawModeIn, bufferSizeIn, useDelegateIn, needsSortingIn, setupTaskIn, clearTaskIn);
	}

	public static RenderType entityColorTranslucent() {
		return ENTITY_COLOR;
	}

	public static RenderType entityColorEyes() {
		return ENTITY_COLOR_EYES;
	}

	public static RenderType linesNoDepth() {
		return LINES_NO_DEPTH;
	}

	public static RenderType glowingEyes(ResourceLocation rl) {
		RenderType rt = RenderType.eyes(rl);
		if (ClientBase.irisLoaded)
			((BlendingStateHolder) rt).setTransparencyType(TransparencyType.DECAL);
		return rt;
	}
}
