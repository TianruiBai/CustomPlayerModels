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
import com.tom.cpm.shared.gui.ViewportCamera;
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
	private final Map<String, VBuffers.NativeRenderType> renderTypeCache = new HashMap<>();

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
		renderTypeCache.clear();
		PslParticlePreviewStyle.TextureCache.clear();
	}

	public void render(MatrixStack stack, VBuffers buffers, ViewportPanel panel) {
		if(!editor.pslTabActive || !editor.pslPreviewEnabled || editor.pslSystem == null || editor.pslSystem.isEmpty())return;
		float dt = updateDelta();
		if(editor.pslPreviewPlaying)editor.pslSystem.tickPreview(runtime, this::targetPosition, dt);

		// Compute camera orientation for particle billboarding
		ViewportCamera cam = panel.getCamera();
		Vec3f forward = new Vec3f(cam.look.x - cam.position.x, cam.look.y - cam.position.y, cam.look.z - cam.position.z);
		forward.normalize();
		Vec3f worldUp = new Vec3f(0, 1, 0);
		Vec3f camRight = cross(forward, worldUp);
		if(camRight.epsilon(0.0001f)) {
			camRight = cross(forward, new Vec3f(1, 0, 0));
		}
		camRight.normalize();
		Vec3f camUp = cross(camRight, forward);

		VertexBuffer markerBuffer = buffers.getBuffer(panel.getRenderTypes(), RenderMode.OUTLINE);
		renderParticles(stack, buffers, camRight, camUp);
		renderLights(stack, markerBuffer);
		renderPhysics(stack, markerBuffer);
	}

	private float updateDelta() {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 1 / 20f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		return dt;
	}

	private void renderParticles(MatrixStack stack, VBuffers buffers, Vec3f camRight, Vec3f camUp) {
		ParticleEmitter emitter = getSelectedParticleEmitter();
		if(emitter == null)return;

		List<ParticleInstance> instances = editor.pslSystem.getParticleInstances(emitter);
		if(instances.isEmpty())return;

		TextureProvider tex = getTexture(emitter);
		if(tex == null)return; // no valid texture — don't draw misleading fallback

		String key = emitter.isMinecraftParticle() ? "mc:" + emitter.getMinecraftParticle() : "proj:" + emitter.getTextureName();
		VBuffers.NativeRenderType nrt = renderTypeCache.get(key);
		if(nrt == null) {
			nrt = MinecraftClientAccess.get().createTexturedRenderType(tex);
			if(nrt == null)return;
			renderTypeCache.put(key, nrt);
		}

		VertexBuffer buffer = buffers.getBuffer(nrt);
		for(ParticleInstance particle : instances) {
			float size = Math.max(0.02f, particle.scale * 0.5f);
			float u0 = emitter.isAnimated() ? particle.frameU0 : 0;
			float v0 = emitter.isAnimated() ? particle.frameV0 : 0;
			float u1 = emitter.isAnimated() ? particle.frameU1 : 1;
			float v1 = emitter.isAnimated() ? particle.frameV1 : 1;
			PslParticlePreviewStyle.drawWorldBillboardSpriteUv(stack, buffer, emitter, particle.position, camRight, camUp, size, particle.alpha, particle.rotX, particle.rotY, particle.rotZ, u0, v0, u1, v1);
		}
	}

	private ParticleEmitter getSelectedParticleEmitter() {
		return editor.selectedPslElement instanceof ParticleEmitter ? (ParticleEmitter) editor.selectedPslElement : null;
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
			pos.x += light.getOffset().x;
			pos.y += light.getOffset().y;
			pos.z += light.getOffset().z;
			float r = ((light.getColor() >> 16) & 0xff) / 255f;
			float g = ((light.getColor() >> 8) & 0xff) / 255f;
			float b = (light.getColor() & 0xff) / 255f;
			float alpha = Math.max(0.25f, light.getIntensity());
			float radius = Math.max(0.08f, light.getRadius() * 0.035f);

			switch (light.getLightType()) {
				case POINT: {
					// Cross-shaped sphere representation: 3 rings in XY, XZ, YZ planes
					int segments = 16;
					for (int i = 0; i < segments; i++) {
						float a1 = (float)(i * 2 * Math.PI / segments);
						float a2 = (float)((i + 1) * 2 * Math.PI / segments);
						// XY ring
						float x1 = pos.x + (float)Math.cos(a1) * radius, y1 = pos.y + (float)Math.sin(a1) * radius;
						float x2 = pos.x + (float)Math.cos(a2) * radius, y2 = pos.y + (float)Math.sin(a2) * radius;
						drawLine(stack, buffer, x1, y1, pos.z, x2, y2, pos.z, r, g, b, alpha);
						// XZ ring
						drawLine(stack, buffer, pos.x + (float)Math.cos(a1) * radius, pos.y, pos.z + (float)Math.sin(a1) * radius, pos.x + (float)Math.cos(a2) * radius, pos.y, pos.z + (float)Math.sin(a2) * radius, r, g, b, alpha);
						// YZ ring
						drawLine(stack, buffer, pos.x, pos.y + (float)Math.cos(a1) * radius, pos.z + (float)Math.sin(a1) * radius, pos.x, pos.y + (float)Math.cos(a2) * radius, pos.z + (float)Math.sin(a2) * radius, r, g, b, alpha);
					}
					break;
				}
				case SPOT: {
					// Cone: base circle + apex lines
					float angle = (float)Math.toRadians(light.getSpotAngle() * 0.5f);
					float coneLen = radius * 2f;
					Vec3f dir = new Vec3f(0, -1, 0); // default downward direction
					Vec3f apex = new Vec3f(pos.x + dir.x * coneLen, pos.y + dir.y * coneLen, pos.z + dir.z * coneLen);
					float baseR = coneLen * (float)Math.tan(angle);
					int segs = 12;
					for (int i = 0; i < segs; i++) {
						float a1 = (float)(i * 2 * Math.PI / segs);
						float a2 = (float)((i + 1) * 2 * Math.PI / segs);
						Vec3f p1 = circlePoint(pos, dir, baseR, a1);
						Vec3f p2 = circlePoint(pos, dir, baseR, a2);
						drawLine(stack, buffer, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, r, g, b, alpha);
						drawLine(stack, buffer, apex.x, apex.y, apex.z, p1.x, p1.y, p1.z, r, g, b, alpha * 0.5f);
					}
					// Direction line
					drawLine(stack, buffer, pos.x, pos.y, pos.z, apex.x, apex.y, apex.z, r, g, b, alpha * 0.6f);
					break;
				}
				case AREA: {
					// Rectangle wireframe
					float hw = light.getAreaWidth() * 0.5f * radius * 0.5f;
					float hh = light.getAreaHeight() * 0.5f * radius * 0.5f;
					drawLine(stack, buffer, pos.x - hw, pos.y, pos.z - hh, pos.x + hw, pos.y, pos.z - hh, r, g, b, alpha);
					drawLine(stack, buffer, pos.x + hw, pos.y, pos.z - hh, pos.x + hw, pos.y, pos.z + hh, r, g, b, alpha);
					drawLine(stack, buffer, pos.x + hw, pos.y, pos.z + hh, pos.x - hw, pos.y, pos.z + hh, r, g, b, alpha);
					drawLine(stack, buffer, pos.x - hw, pos.y, pos.z + hh, pos.x - hw, pos.y, pos.z - hh, r, g, b, alpha);
					break;
				}
			}
		}
	}

	private static void drawLine(MatrixStack stack, VertexBuffer buffer, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
		buffer.pos(x1, y1, z1).color(r, g, b, a).endVertex();
		buffer.pos(x2, y2, z2).color(r, g, b, a).endVertex();
	}

	private static Vec3f circlePoint(Vec3f center, Vec3f dir, float radius, float angle) {
		Vec3f perp;
		if(Math.abs(dir.x) < 0.9f)perp = new Vec3f(1, 0, 0);
		else perp = new Vec3f(0, 1, 0);
		Vec3f right = cross(dir, perp);
		right.normalize();
		Vec3f up = cross(right, dir);
		up.normalize();
		return new Vec3f(
			center.x + right.x * radius * (float)Math.cos(angle) + up.x * radius * (float)Math.sin(angle),
			center.y + right.y * radius * (float)Math.cos(angle) + up.y * radius * (float)Math.sin(angle),
			center.z + right.z * radius * (float)Math.cos(angle) + up.z * radius * (float)Math.sin(angle)
		);
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

	private static Vec3f cross(Vec3f a, Vec3f b) {
		return new Vec3f(
			a.y * b.z - a.z * b.y,
			a.z * b.x - a.x * b.z,
			a.x * b.y - a.y * b.x
		);
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
		public boolean previewVanillaParticlesWithSharedRenderer() {
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
