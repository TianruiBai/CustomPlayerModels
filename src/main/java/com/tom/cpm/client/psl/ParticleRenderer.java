package com.tom.cpm.client.psl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.client.CustomRenderTypes;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.particle.ParticleInstance;

/**
 * Client-side particle renderer for NeoForge 1.21.
 * Renders billboarded particle quads using Minecraft's vertex consumer.
 */
public class ParticleRenderer {

	private final Map<String, ResourceLocation> textureCache = new HashMap<>();

	public void render(List<ParticleInstance> particles, ParticleEmitter def,
	                   PoseStack poseStack, MultiBufferSource bufferSource,
	                   Camera camera, float partialTicks) {
		if (particles.isEmpty() || def == null) return;

		RenderType renderType;
		switch (def.getBlendMode()) {
			case ADDITIVE:
				renderType = CustomRenderTypes.glowingEyes(getTex(def));
				break;
			default:
				renderType = CustomRenderTypes.entityColorTranslucent();
				break;
		}

		VertexConsumer vc = bufferSource.getBuffer(renderType);
		float cx = (float) camera.getPosition().x;
		float cy = (float) camera.getPosition().y;
		float cz = (float) camera.getPosition().z;

		for (ParticleInstance p : particles) {
			float px = p.position.x - cx;
			float py = p.position.y - cy;
			float pz = p.position.z - cz;

			float hw = p.scale * def.getSpriteWidth() * 0.5f;
			float hh = p.scale * def.getSpriteHeight() * 0.5f;

			int ar = (p.color >> 24) & 0xFF;
			int rr = (p.color >> 16) & 0xFF;
			int gg = (p.color >> 8) & 0xFF;
			int bb = p.color & 0xFF;
			int aa = (int)(p.alpha * ar);

			float u0 = def.getSpriteU() / (float) Math.max(1, def.getSpriteTexW());
			float v0 = def.getSpriteV() / (float) Math.max(1, def.getSpriteTexH());
			float u1 = (def.getSpriteU() + def.getSpriteTexW()) / (float) Math.max(1, def.getSpriteTexW());
			float v1 = (def.getSpriteV() + def.getSpriteTexH()) / (float) Math.max(1, def.getSpriteTexH());

			poseStack.pushPose();
			poseStack.translate(px, py, pz);
			var mat = poseStack.last().pose();

			vc.addVertex(mat, -hw, -hh, 0).setColor(rr, gg, bb, aa).setUv(u0, v1);
			vc.addVertex(mat,  hw, -hh, 0).setColor(rr, gg, bb, aa).setUv(u1, v1);
			vc.addVertex(mat,  hw,  hh, 0).setColor(rr, gg, bb, aa).setUv(u1, v0);
			vc.addVertex(mat, -hw,  hh, 0).setColor(rr, gg, bb, aa).setUv(u0, v0);

			poseStack.popPose();
		}
	}

	private ResourceLocation getTex(ParticleEmitter def) {
		String name = def.getTextureName();
		if (name == null || name.isEmpty()) name = "textures/misc/white.png";
		return textureCache.computeIfAbsent(name, ResourceLocation::parse);
	}

	public void clear() {
		textureCache.clear();
	}
}
