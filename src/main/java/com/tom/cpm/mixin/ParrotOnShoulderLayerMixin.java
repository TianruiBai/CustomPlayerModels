package com.tom.cpm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ParrotOnShoulderLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

import com.mojang.blaze3d.vertex.PoseStack;

import com.tom.cpl.util.ItemSlot;
import com.tom.cpm.client.CustomPlayerModelsClient;
import com.tom.cpm.client.PlayerRenderManager;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.render.ItemTransform;
import com.tom.cpm.shared.model.render.ModelRenderManager.BoundPlayer;

@Mixin(value = ParrotOnShoulderLayer.class, priority = 2000)
public abstract class ParrotOnShoulderLayerMixin extends RenderLayer<net.minecraft.world.entity.player.Player, PlayerModel<net.minecraft.world.entity.player.Player>> {

	public ParrotOnShoulderLayerMixin(RenderLayerParent<net.minecraft.world.entity.player.Player, PlayerModel<net.minecraft.world.entity.player.Player>> p_117346_) {
		super(p_117346_);
	}

	@Inject(at = @At("HEAD"),
			method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/player/Player;FFFFZ)V")
	public void onRenderPre(PoseStack matrixStackIn, MultiBufferSource bufferIn, int packedLightIn, net.minecraft.world.entity.player.Player entitylivingbaseIn, float limbSwing, float limbSwingAmount, float netHeadYaw, float headPitch, boolean leftShoulderIn, CallbackInfo cbi) {
		matrixStackIn.pushPose();
		BoundPlayer pl = CustomPlayerModelsClient.INSTANCE.manager.getPlayerFromModel(getParentModel());
		if(pl != null) {
			ModelDefinition def = pl.definition;
			if(def != null) {
				ItemTransform tr = def.getTransform(leftShoulderIn ? ItemSlot.LEFT_SHOULDER : ItemSlot.RIGHT_SHOULDER);
				if(tr != null) {
					PlayerRenderManager.multiplyStacks(tr.getMatrix(), matrixStackIn);
					if(entitylivingbaseIn.isCrouching())
						matrixStackIn.translate(0, -0.2f, 0);
				}
			}
		}
	}

	@Inject(at = @At("RETURN"),
			method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/player/Player;FFFFZ)V")
	public void onRenderPost(PoseStack matrixStackIn, MultiBufferSource bufferIn, int packedLightIn, net.minecraft.world.entity.player.Player entitylivingbaseIn, float limbSwing, float limbSwingAmount, float netHeadYaw, float headPitch, boolean leftShoulderIn, CallbackInfo cbi) {
		matrixStackIn.popPose();
	}
}
