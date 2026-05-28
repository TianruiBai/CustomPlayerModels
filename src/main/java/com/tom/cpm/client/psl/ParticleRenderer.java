package com.tom.cpm.client.psl;

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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import com.tom.cpl.util.Image;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.BlendMode;
import com.tom.cpm.shared.psl.particle.ParticleInstance;

/**
 * Client-side particle renderer for NeoForge 1.21.
 * Renders billboarded particle quads with real particle textures and PSL-defined behaviour.
 */
public class ParticleRenderer {

	private final Map<String, ResourceLocation> textureCache = new HashMap<>();

	public void render(List<ParticleInstance> particles, ParticleEmitter def,
	                   MultiBufferSource bufferSource, Camera camera) {
		if (particles.isEmpty() || def == null) return;

		ResourceLocation texLoc = getParticleTexture(def);
		if (texLoc == null) return;

		RenderType renderType = def.getBlendMode() == BlendMode.ADDITIVE
			? RenderType.entityTranslucentEmissive(texLoc)
			: RenderType.entityTranslucent(texLoc);

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

			float u0 = def.getSpriteU() / (float) Math.max(1, def.getSpriteTexW());
			float v0 = def.getSpriteV() / (float) Math.max(1, def.getSpriteTexH());
			float u1 = (def.getSpriteU() + def.getSpriteWidth()) / (float) Math.max(1, def.getSpriteTexW());
			float v1 = (def.getSpriteV() + def.getSpriteHeight()) / (float) Math.max(1, def.getSpriteTexH());

			PoseStack particleStack = new PoseStack();
			particleStack.translate(px, py, pz);
			particleStack.mulPose(camera.rotation());
			if (p.rotation != 0) {
				particleStack.mulPose(Axis.ZP.rotationDegrees(p.rotation));
			}
			var mat = particleStack.last().pose();

			vc.addVertex(mat, -hw, -hh, 0).setColor(rr, gg, bb, aa).setUv(u0, v1);
			vc.addVertex(mat,  hw, -hh, 0).setColor(rr, gg, bb, aa).setUv(u1, v1);
			vc.addVertex(mat,  hw,  hh, 0).setColor(rr, gg, bb, aa).setUv(u1, v0);
			vc.addVertex(mat, -hw,  hh, 0).setColor(rr, gg, bb, aa).setUv(u0, v0);
		}
	}

	private ResourceLocation getParticleTexture(ParticleEmitter def) {
		String key = def.isMinecraftParticle() ? "mc:" + def.getMinecraftParticle() : "proj:" + def.getTextureName();
		ResourceLocation cached = textureCache.get(key);
		if (cached != null) return cached;

		Image img = null;
		try {
			String particleId = def.isMinecraftParticle() ? def.getMinecraftParticle() : def.getTextureName();
			img = MinecraftClientAccess.get().getPslRuntime().loadParticleImage(particleId);
		} catch (Exception ignored) {}

		if (img == null) return null;

		ResourceLocation rl = uploadTexture("cpm_psl", key, img);
		if (rl != null) textureCache.put(key, rl);
		return rl;
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
	}
}
