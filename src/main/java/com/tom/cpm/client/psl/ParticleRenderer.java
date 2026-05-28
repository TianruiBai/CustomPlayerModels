package com.tom.cpm.client.psl;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleDescription;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

import com.tom.cpl.util.Image;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.BlendMode;
import com.tom.cpm.shared.psl.particle.ParticleInstance;
import com.tom.cpm.shared.util.Log;

/**
 * Client-side particle renderer for NeoForge 1.21.
 * Renders billboarded particle quads with real particle textures and PSL-defined behaviour.
 */
public class ParticleRenderer {

	private final Map<String, ParticleTexture> textureCache = new HashMap<>();
	private final Map<String, Boolean> textureLogCache = new HashMap<>();

	public void render(List<ParticleInstance> particles, ParticleEmitter def,
	                   MultiBufferSource bufferSource, Camera camera) {
		if (particles.isEmpty() || def == null) return;

		ParticleTexture texture = getParticleTexture(def);
		if (texture == null) return;

		RenderType renderType = def.getBlendMode() == BlendMode.ADDITIVE
			? RenderType.entityTranslucentEmissive(texture.location)
			: RenderType.entityTranslucent(texture.location);

		VertexConsumer vc = bufferSource.getBuffer(renderType);
		float cx = (float) camera.getPosition().x;
		float cy = (float) camera.getPosition().y;
		float cz = (float) camera.getPosition().z;

		for (ParticleInstance p : particles) {
			float px = p.position.x - cx;
			float py = p.position.y - cy;
			float pz = p.position.z - cz;

			float hw = p.scale * 0.5f;
			float hh = p.scale * 0.5f;

			int rr = (p.color >> 16) & 0xFF;
			int gg = (p.color >> 8) & 0xFF;
			int bb = p.color & 0xFF;
			int aa = (int)(p.alpha * 255f);

			float u0 = texture.u0;
			float v0 = texture.v0;
			float u1 = texture.u1;
			float v1 = texture.v1;

			PoseStack particleStack = new PoseStack();
			particleStack.translate(px, py, pz);
			particleStack.mulPose(camera.rotation());
			if (p.rotation != 0) {
				particleStack.mulPose(Axis.ZP.rotationDegrees(p.rotation));
			}
			var pose = particleStack.last();
			var mat = pose.pose();

			vc.addVertex(mat, -hw, -hh, 0).setColor(rr, gg, bb, aa).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
			vc.addVertex(mat,  hw, -hh, 0).setColor(rr, gg, bb, aa).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
			vc.addVertex(mat,  hw,  hh, 0).setColor(rr, gg, bb, aa).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
			vc.addVertex(mat, -hw,  hh, 0).setColor(rr, gg, bb, aa).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
		}
	}

	private ParticleTexture getParticleTexture(ParticleEmitter def) {
		String key = def.isMinecraftParticle() ? "mc:" + def.getMinecraftParticle() : "proj:" + def.getTextureName();
		ParticleTexture cached = textureCache.get(key);
		if (cached != null) return cached;

		if (def.isMinecraftParticle()) {
			ParticleTexture atlasTexture = getMinecraftParticleTexture(def.getMinecraftParticle());
			if (atlasTexture != null) {
				textureCache.put(key, atlasTexture);
				logTextureOnce(key, "atlas " + def.getMinecraftParticle());
				return atlasTexture;
			}
		}

		Image img = null;
		try {
			String particleId = def.isMinecraftParticle() ? def.getMinecraftParticle() : def.getTextureName();
			img = MinecraftClientAccess.get().getPslRuntime().loadParticleImage(particleId);
		} catch (Exception ignored) {}

		if (img == null) {
			logTextureOnce(key, "missing");
			return null;
		}

		ResourceLocation rl = uploadTexture("cpm_psl", key, img);
		if (rl == null) {
			logTextureOnce(key, "upload failed");
			return null;
		}

		float u0 = 0;
		float v0 = 0;
		float u1 = 1;
		float v1 = 1;
		if (!def.isMinecraftParticle()) {
			int texW = Math.max(1, def.getSpriteTexW());
			int texH = Math.max(1, def.getSpriteTexH());
			u0 = def.getSpriteU() / (float) texW;
			v0 = def.getSpriteV() / (float) texH;
			u1 = (def.getSpriteU() + def.getSpriteWidth()) / (float) texW;
			v1 = (def.getSpriteV() + def.getSpriteHeight()) / (float) texH;
		}

		ParticleTexture texture = new ParticleTexture(rl, u0, v0, u1, v1);
		textureCache.put(key, texture);
		logTextureOnce(key, "dynamic " + rl);
		return texture;
	}

	private void logTextureOnce(String key, String result) {
		if(textureLogCache.putIfAbsent(key + ":" + result, Boolean.TRUE) == null) {
			Log.info("PSL particle texture: " + key + " -> " + result);
		}
	}

	private ParticleTexture getMinecraftParticleTexture(String particleId) {
		if (particleId == null || particleId.isEmpty()) return null;
		try {
			ResourceLocation particleLocation = ResourceLocation.parse(particleId);
			TextureAtlas atlas = getParticleAtlas();
			if (atlas == null) return null;

			ParticleTexture direct = getAtlasParticleTexture(atlas, particleLocation);
			if (direct != null) return direct;

			ResourceLocation definitionPath = ResourceLocation.fromNamespaceAndPath(particleLocation.getNamespace(), "particles/" + particleLocation.getPath() + ".json");
			var resource = Minecraft.getInstance().getResourceManager().getResource(definitionPath);
			if (resource.isEmpty()) return null;
			try (Reader reader = resource.get().openAsReader()) {
				ParticleDescription description = ParticleDescription.fromJson(GsonHelper.parse(reader));
				for (ResourceLocation spriteId : description.getTextures()) {
					ParticleTexture texture = getAtlasParticleTexture(atlas, spriteId);
					if (texture != null) return texture;
				}
			}
		} catch (IOException ignored) {
		} catch (Exception ignored) {
		}
		return null;
	}

	private static TextureAtlas getParticleAtlas() {
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
		return texture instanceof TextureAtlas atlas ? atlas : null;
	}

	private static ParticleTexture getAtlasParticleTexture(TextureAtlas atlas, ResourceLocation spriteId) {
		try {
			TextureAtlasSprite sprite = atlas.getSprite(spriteId);
			if (sprite == null || sprite.contents() == null) return null;
			if (MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name())) return null;
			return new ParticleTexture(TextureAtlas.LOCATION_PARTICLES, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
		} catch (IllegalStateException ignored) {
			return null;
		}
	}

	private static ResourceLocation uploadTexture(String prefix, String key, Image img) {
		try {
			NativeImage ni = new NativeImage(img.getWidth(), img.getHeight(), false);
			for (int y = 0; y < img.getHeight(); y++) {
				for (int x = 0; x < img.getWidth(); x++) {
					int argb = img.getRGB(x, y);
					int a = (argb >> 24) & 0xFF;
					int r = (argb >> 16) & 0xFF;
					int g = (argb >> 8) & 0xFF;
					int b = argb & 0xFF;
					ni.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
				}
			}
			DynamicTexture dynTex = new DynamicTexture(ni);
			dynTex.setPixels(ni);
			TextureUtil.prepareImage(dynTex.getId(), ni.getWidth(), ni.getHeight());
			dynTex.upload();
			ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("cpm", prefix + "/" + Math.abs(key.hashCode()));
			Minecraft.getInstance().getTextureManager().register(loc, dynTex);
			return loc;
		} catch (Exception ignored) {
			return null;
		}
	}

	public void clear() {
		textureCache.clear();
		textureLogCache.clear();
	}

	private static class ParticleTexture {
		private final ResourceLocation location;
		private final float u0;
		private final float v0;
		private final float u1;
		private final float v1;

		private ParticleTexture(ResourceLocation location, float u0, float v0, float u1, float v1) {
			this.location = location;
			this.u0 = u0;
			this.v0 = v0;
			this.u1 = u1;
			this.v1 = v1;
		}
	}
}
