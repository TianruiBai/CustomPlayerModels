package com.tom.cpm.client.psl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.client.CustomRenderTypes;
import com.tom.cpm.shared.psl.IPslRuntime;
import com.tom.cpm.shared.psl.light.LightEmitter;
import com.tom.cpm.shared.psl.light.LightRuntime;

/**
 * Client-side light renderer for NeoForge 1.21.
 * Renders additive glow spheres/spot cones/area panels, and manages dynamic light registration.
 */
public class LightRenderer {

	private static final ResourceLocation GLOW_TEXTURE = ResourceLocation.fromNamespaceAndPath("cpm", "psl_light_glow");
	private static boolean glowTextureReady;

	private final LightRuntime lightRt = new LightRuntime();
	private final Map<Long, Boolean> activeLights = new HashMap<>();
	private boolean shaderWarningShown;

	/**
	 * Render additive glow quads for active light elements with per-type visuals.
	 */
	public void renderGlow(List<LightEmitter> lights, PoseStack poseStack,
	                       MultiBufferSource bufferSource, Camera camera,
	                       java.util.function.Function<Integer, Vec3f> elemPosFn,
	                       long tickCounter, boolean triggerActive) {
		if (lights.isEmpty()) return;
		ensureGlowTexture();

		float cx = (float) camera.getPosition().x;
		float cy = (float) camera.getPosition().y;
		float cz = (float) camera.getPosition().z;

		for (LightEmitter l : lights) {
			if (!l.isDynamic()) continue;
			float intensity = lightRt.getCurrentIntensity(l, tickCounter, triggerActive);
			if (intensity <= 0.01f) continue;

			Vec3f pos = elemPosFn.apply(l.getElementId());
			if (pos == null) pos = Vec3f.ZERO;
			// Apply offset
			Vec3f off = l.getOffset();
			float px = pos.x + off.x - cx;
			float py = pos.y + off.y - cy;
			float pz = pos.z + off.z - cz;

			int baseColor = l.getColor();
			int color = LightEmitter.applyTemperature(baseColor, l.getColorTemperature());
			float r = ((color >> 16) & 0xFF) / 255f;
			float g = ((color >> 8) & 0xFF) / 255f;
			float b = (color & 0xFF) / 255f;
			float a = Math.min(1f, intensity);

			float size;
			switch (l.getLightType()) {
				case POINT:  size = l.getRadius() * 2f; break;
				case SPOT:   size = l.getRadius() * 2f * (float)Math.tan(Math.toRadians(l.getSpotAngle() * 0.5f)) * 2f; break;
				case AREA:   size = Math.max(l.getAreaWidth(), l.getAreaHeight()) * l.getRadius(); break;
				default:     size = l.getRadius() * 2f; break;
			}

			RenderType renderType = CustomRenderTypes.pslLightAdditive(GLOW_TEXTURE);
			VertexConsumer vc = bufferSource.getBuffer(renderType);
			renderBillboardGlow(poseStack, vc, px, py, pz, size, r, g, b, a, camera);
		}
	}

	private void renderBillboardGlow(PoseStack poseStack, VertexConsumer vc,
	                                 float px, float py, float pz, float size,
	                                 float r, float g, float b, float a, Camera camera) {
		float hw = size * 0.5f;
		float hh = size * 0.5f;

		PoseStack stack = new PoseStack();
		stack.translate(px, py, pz);
		stack.mulPose(camera.rotation());
		var pose = stack.last();
		var mat = pose.pose();

		vc.addVertex(mat, -hw, -hh, 0).setColor(r, g, b, a).setUv(0, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
		vc.addVertex(mat,  hw, -hh, 0).setColor(r, g, b, a).setUv(1, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
		vc.addVertex(mat,  hw,  hh, 0).setColor(r, g, b, a).setUv(1, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
		vc.addVertex(mat, -hw,  hh, 0).setColor(r, g, b, a).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
	}

	/**
	 * Render emissive glow quads for active light elements.
	 * Called during the model render pass.
	 */
	public void renderEmissive(List<LightEmitter> lights, PoseStack poseStack,
	                           MultiBufferSource bufferSource,
	                           java.util.function.Function<Integer, Vec3f> elemPosFn,
	                           long tickCounter, boolean triggerActive) {
		if (lights.isEmpty()) return;

		RenderType emissiveType = CustomRenderTypes.glowingEyes(
			net.minecraft.resources.ResourceLocation.parse("textures/misc/white.png"));
		VertexConsumer vc = bufferSource.getBuffer(emissiveType);

		for (LightEmitter l : lights) {
			float intensity = lightRt.getCurrentIntensity(l, tickCounter, triggerActive);
			if (intensity <= 0.01f) continue;

			Vec3f pos = elemPosFn.apply(l.getElementId());
			if (pos == null) pos = Vec3f.ZERO;

			int color = LightEmitter.applyTemperature(l.getColor(), l.getColorTemperature());
			int r = (color >> 16) & 0xFF;
			int g = (color >> 8) & 0xFF;
			int b = color & 0xFF;
			int a = (int)(intensity * 192); // subdued emissive

			float s = 0.3f;
			poseStack.pushPose();
			poseStack.translate(pos.x, pos.y, pos.z);
			var mat = poseStack.last().pose();

			vc.addVertex(mat, -s, -s, 0).setColor(r, g, b, a).setUv(0, 1);
			vc.addVertex(mat,  s, -s, 0).setColor(r, g, b, a).setUv(1, 1);
			vc.addVertex(mat,  s,  s, 0).setColor(r, g, b, a).setUv(1, 0);
			vc.addVertex(mat, -s,  s, 0).setColor(r, g, b, a).setUv(0, 0);

			poseStack.popPose();
		}
	}

	/**
	 * Update dynamic lights for active light elements.
	 */
	public void updateDynamicLights(List<LightEmitter> lights, int entityId,
	                                java.util.function.Function<Integer, Vec3f> elemPosFn,
	                                IPslRuntime runtime, long tickCounter, boolean triggerActive) {
		if (!runtime.isDynamicLightSupported()) return;

		if (runtime.isShaderPackActive()) {
			for (long id : activeLights.keySet()) {
				runtime.unregisterDynamicLight(entityId);
			}
			activeLights.clear();
			if (!shaderWarningShown) {
				shaderWarningShown = true;
				com.tom.cpm.shared.util.Log.info("PSL: Dynamic lights disabled — shader pack detected");
			}
			return;
		}

		for (LightEmitter l : lights) {
			if (!l.isDynamic()) continue;

			float intensity = lightRt.getCurrentIntensity(l, tickCounter, triggerActive);
			Vec3f pos = elemPosFn.apply(l.getElementId());
			if (pos == null) continue;
			// Apply offset
			Vec3f off = l.getOffset();
			float px = pos.x + off.x;
			float py = pos.y + off.y;
			float pz = pos.z + off.z;

			boolean wasActive = activeLights.getOrDefault(l.getId(), false);
			boolean isActive = intensity > 0.01f;

			if (isActive && !wasActive) {
				runtime.registerDynamicLight(entityId, px, py, pz,
					l.getColor(), intensity * 15, l.getRadius());
				activeLights.put(l.getId(), true);
			} else if (isActive && wasActive) {
				runtime.updateDynamicLight(entityId, px, py, pz);
			} else if (!isActive && wasActive) {
				runtime.unregisterDynamicLight(entityId);
				activeLights.put(l.getId(), false);
			}
		}
	}

	public void cleanup(int entityId, IPslRuntime runtime) {
		for (long id : activeLights.keySet()) {
			runtime.unregisterDynamicLight(entityId);
		}
		activeLights.clear();
	}

	private static void ensureGlowTexture() {
		if (glowTextureReady) return;
		try {
			int size = 64;
			NativeImage img = new NativeImage(size, size, false);
			float center = size / 2f;
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					float dx = (x - center) / center;
					float dy = (y - center) / center;
					float dist = (float) Math.sqrt(dx * dx + dy * dy);
					float alpha = Math.max(0, 1 - dist);
					alpha = alpha * alpha;
					int a = (int) (alpha * 255);
					img.setPixelRGBA(x, y, (a << 24) | (255 << 16) | (255 << 8) | 255);
				}
			}
			DynamicTexture tex = new DynamicTexture(img);
			tex.setPixels(img);
			TextureUtil.prepareImage(tex.getId(), size, size);
			tex.upload();
			Minecraft.getInstance().getTextureManager().register(GLOW_TEXTURE, tex);
			glowTextureReady = true;
		} catch (Exception ignored) {
		}
	}
}
