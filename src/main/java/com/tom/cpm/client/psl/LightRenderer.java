package com.tom.cpm.client.psl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.client.CustomRenderTypes;
import com.tom.cpm.shared.psl.IPslRuntime;
import com.tom.cpm.shared.psl.light.LightEmitter;
import com.tom.cpm.shared.psl.light.LightRuntime;

/**
 * Client-side light renderer for NeoForge 1.21.
 * Handles:
 * - Emissive render layer (fullbright quad at light element position)
 * - Dynamic light registration (delegated to IPslRuntime)
 * - Shader pack detection and graceful degradation
 */
public class LightRenderer {

	private final LightRuntime lightRt = new LightRuntime();
	private final Map<Long, Boolean> activeLights = new HashMap<>();
	private boolean shaderWarningShown;

	/**
	 * Render emissive glow quads for active light elements.
	 * Called during the model render pass.
	 */
	public void renderEmissive(List<LightEmitter> lights, PoseStack poseStack,
	                           MultiBufferSource bufferSource,
	                           java.util.function.Function<Integer, Vec3f> elemPosFn,
	                           long tickCounter, boolean triggerActive) {
		if (lights.isEmpty()) return;

		// Use existing glowing eyes render type for emissive effect
		RenderType emissiveType = CustomRenderTypes.glowingEyes(
			net.minecraft.resources.ResourceLocation.parse("textures/misc/white.png"));
		VertexConsumer vc = bufferSource.getBuffer(emissiveType);

		for (LightEmitter l : lights) {
			float intensity = lightRt.getCurrentIntensity(l, tickCounter, triggerActive);
			if (intensity <= 0.01f) continue;

			Vec3f pos = elemPosFn.apply(l.getElementId());
			if (pos == null) pos = Vec3f.ZERO;

			int r = (l.getColor() >> 16) & 0xFF;
			int g = (l.getColor() >> 8) & 0xFF;
			int b = l.getColor() & 0xFF;
			int a = (int)(intensity * 255);

			float s = 0.3f; // glow quad size
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
	 * Registers/updates/removes dynamic light sources via IPslRuntime.
	 */
	public void updateDynamicLights(List<LightEmitter> lights, int entityId,
	                                java.util.function.Function<Integer, Vec3f> elemPosFn,
	                                IPslRuntime runtime, long tickCounter, boolean triggerActive) {
		if (!runtime.isDynamicLightSupported()) return;

		// Check shader pack — degrade if active
		if (runtime.isShaderPackActive()) {
			// Unregister all lights when shaders detected
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

			boolean wasActive = activeLights.getOrDefault(l.getId(), false);
			boolean isActive = intensity > 0.01f;

			if (isActive && !wasActive) {
				runtime.registerDynamicLight(entityId, pos.x, pos.y, pos.z,
					l.getColor(), intensity * 15, l.getRadius());
				activeLights.put(l.getId(), true);
			} else if (isActive && wasActive) {
				runtime.updateDynamicLight(entityId, pos.x, pos.y, pos.z);
			} else if (!isActive && wasActive) {
				runtime.unregisterDynamicLight(entityId);
				activeLights.put(l.getId(), false);
			}
		}
	}

	/**
	 * Clean up all dynamic lights for an entity.
	 */
	public void cleanup(int entityId, IPslRuntime runtime) {
		for (long id : activeLights.keySet()) {
			runtime.unregisterDynamicLight(entityId);
		}
		activeLights.clear();
	}
}
