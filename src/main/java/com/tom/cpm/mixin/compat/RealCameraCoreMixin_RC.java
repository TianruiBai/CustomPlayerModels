package com.tom.cpm.mixin.compat;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import com.xtracr.realcamera.RealCameraCore;

import com.tom.cpm.client.RealCameraDetector;

@Mixin(RealCameraCore.class)
public class RealCameraCoreMixin_RC {

	@Inject(at = @At("HEAD"), method = "renderCameraEntity", remap = false)
	private static void onUpdateModelPre(final Minecraft client, final float tickDelta, MultiBufferSource buf, final Matrix4f modelView, CallbackInfo cbi) {
		RealCameraDetector.realCameraRendering = true;
	}

	@Inject(at = @At("RETURN"), method = "renderCameraEntity", remap = false)
	private static void onUpdateModelPost(final Minecraft client, final float tickDelta, MultiBufferSource buf, final Matrix4f modelView, CallbackInfo cbi) {
		RealCameraDetector.realCameraRendering = false;
	}
}
