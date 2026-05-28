package com.tom.cpm.shared.editor.gui;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpl.math.BoundingBox;
import com.tom.cpl.math.MatrixStack;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.math.Vec4f;
import com.tom.cpl.render.VBuffers;
import com.tom.cpl.render.VertexBuffer;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.model.render.BoxRender;
import com.tom.cpm.shared.model.render.RenderMode;
import com.tom.cpm.shared.psl.IPslRuntime;
import com.tom.cpm.shared.psl.PslElementType;
import com.tom.cpm.shared.psl.light.LightEmitter;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.particle.ParticleInstance;
import com.tom.cpm.shared.psl.physics.PhysicsBone;
import com.tom.cpm.shared.psl.physics.PhysicsState;
import com.tom.cpm.shared.psl.sound.SoundEmitter;
import com.tom.cpm.shared.skin.TextureProvider;

public class PslEditorPreview {
	private final Editor editor;
	final IPslRuntime runtime = new PreviewRuntime();
	private long lastNanos;
	private final Map<String, TextureProvider> textureCache = new HashMap<>();

	public PslEditorPreview(Editor editor) {
		this.editor = editor;
	}

	public void reset() {
		lastNanos = 0;
		if(editor.pslSystem != null)editor.pslSystem.clearRuntimeState();
		for(TextureProvider tp : textureCache.values()) {
			if(tp != null)tp.free();
		}
		textureCache.clear();
		PslParticlePreviewStyle.TextureCache.clear();
	}

	public void render(MatrixStack stack, VBuffers buffers, ViewportPanel panel) {
		if(!editor.pslPreviewEnabled || editor.pslSystem == null || editor.pslSystem.isEmpty())return;
		float dt = updateDelta();
		if(editor.pslPreviewPlaying)editor.pslSystem.tickPreview(runtime, this::targetPosition, dt);

		VertexBuffer particleBuffer = buffers.getBuffer(panel.getRenderTypes(), RenderMode.COLOR);
		VertexBuffer markerBuffer = buffers.getBuffer(panel.getRenderTypes(), RenderMode.OUTLINE);
		renderParticles(stack, particleBuffer);
		renderLights(stack, markerBuffer);
		renderPhysics(stack, markerBuffer);
	}

	private float updateDelta() {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 1 / 20f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		return dt;
	}

	private void renderParticles(MatrixStack stack, VertexBuffer buffer) {
		List<ParticleEmitter> emitters = editor.pslSystem.getElementsOfType(PslElementType.PARTICLE);
		for(ParticleEmitter emitter : emitters) {
			List<ParticleInstance> instances = editor.pslSystem.getParticleInstances(emitter);
			if(instances.isEmpty())continue;

			TextureProvider tex = getTexture(emitter);
			if(tex != null) {
				tex.bind();
				for(ParticleInstance particle : instances) {
					float size = Math.max(0.35f, particle.scale * 0.45f);
					PslParticlePreviewStyle.drawWorldTexturedSprite(stack, buffer, emitter, particle.position, size, particle.alpha, particle.rotation * 0.017453292f);
				}
			} else {
				// Minimal colored-quad fallback — real texture preferred
				for(ParticleInstance particle : instances) {
					float size = Math.max(0.35f, particle.scale * 0.45f);
					PslParticlePreviewStyle.drawWorldFallbackSprite(stack, buffer, particle.position, size, particle.color, particle.alpha, particle.rotation * 0.017453292f);
				}
			}
		}
	}

	private TextureProvider getTexture(ParticleEmitter emitter) {
		String key = emitter.isMinecraftParticle() ? "mc:" + emitter.getMinecraftParticle() : "proj:" + emitter.getTextureName();
		TextureProvider cached = textureCache.get(key);
		if(cached != null)return cached;

		Image img = null;
		if(emitter.isMinecraftParticle()) {
			try {
				img = MinecraftClientAccess.get().getPslRuntime().loadParticleImage(emitter.getMinecraftParticle());
			} catch (Exception ignored) {}
		} else {
			String texName = emitter.getTextureName();
			if(texName != null && !texName.isEmpty()) {
				byte[] data = editor.project.getEntry(texName);
				if(data != null) {
					try {
						img = ImageIO.read(new ByteArrayInputStream(data));
					} catch (Exception ignored) {}
				}
			}
		}

		if(img != null) {
			TextureProvider tp = new TextureProvider(img, new Vec2i(img.getWidth(), img.getHeight()));
			textureCache.put(key, tp);
			return tp;
		}
		// Do NOT cache null — retry next frame so late-binding atlases eventually succeed
		return null;
	}

	private void renderLights(MatrixStack stack, VertexBuffer buffer) {
		List<LightEmitter> lights = editor.pslSystem.getElementsOfType(PslElementType.LIGHT);
		for(LightEmitter light : lights) {
			Vec3f pos = targetPosition(light.getElementId());
			float size = Math.max(0.08f, light.getRadius() * 0.035f);
			float r = ((light.getColor() >> 16) & 0xff) / 255f;
			float g = ((light.getColor() >> 8) & 0xff) / 255f;
			float b = (light.getColor() & 0xff) / 255f;
			BoxRender.drawBoundingBox(stack, buffer, BoundingBox.create(pos.x - size, pos.y - size, pos.z - size, size * 2, size * 2, size * 2), r, g, b, Math.max(0.25f, light.getIntensity()));
		}
	}

	private void renderPhysics(MatrixStack stack, VertexBuffer buffer) {
		List<PhysicsBone> bones = editor.pslSystem.getElementsOfType(PslElementType.PHYSICS);
		for(PhysicsBone bone : bones) {
			PhysicsState state = editor.pslSystem.getPhysicsRuntime().getState(bone.getId());
			Vec3f pos = state != null ? state.position : targetPosition(bone.getElementId());
			float size = Math.max(0.04f, bone.getCollisionRadius() * 0.04f);
			BoxRender.drawBoundingBox(stack, buffer, BoundingBox.create(pos.x - size, pos.y - size, pos.z - size, size * 2, size * 2, size * 2), 0.4f, 0.85f, 1f, 0.9f);
		}
	}

	Vec3f targetPosition(int elementId) {
		ModelElement element = PslUiUtil.findElement(editor, elementId);
		if(element != null && element.matrixPosition != null) {
			Vec4f pos = new Vec4f(0, 0, 1, 1);
			pos.transform(element.matrixPosition);
			return new Vec3f(pos.x, pos.y, pos.z);
		}
		return Vec3f.ZERO;
	}

	private static class PreviewRuntime implements IPslRuntime {
		@Override
		public java.io.InputStream getResource(String path) {
			return null;
		}

		@Override
		public float getParticleAmountFactor() {
			return 1;
		}

		@Override
		public boolean isShaderPackActive() {
			return false;
		}

		@Override
		public void registerDynamicLight(int entityId, float x, float y, float z, int color, float level, float radius) {
		}

		@Override
		public void updateDynamicLight(int entityId, float x, float y, float z) {
		}

		@Override
		public void unregisterDynamicLight(int entityId) {
		}

		@Override
		public boolean isDynamicLightSupported() {
			return false;
		}

		@Override
		public boolean useBuiltinParticleRenderer() {
			return false;
		}

		@Override
		public boolean useSharedParticleRenderer() {
			return true;
		}

		@Override
		public void playSound(SoundEmitter emitter, Vec3f worldPosition) {
			PslPreviewUtil.previewSound(emitter);
		}

		@Override
		public boolean checkBlockCollision(float x, float y, float z) {
			return false;
		}
	}
}
