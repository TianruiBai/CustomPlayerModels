package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.math.Mat3f;
import com.tom.cpl.math.Mat4f;
import com.tom.cpl.math.MatrixStack;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.render.VertexBuffer;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.skin.TextureProvider;

final class PslParticlePreviewStyle {
	private PslParticlePreviewStyle() {
	}

	static void drawGuiTexturePreview(IGui gui, int x, int y, int w, int h, Image image, ParticleEmitter emitter) {
		if(image == null || image.getWidth() <= 0 || image.getHeight() <= 0)return;
		TextureProvider provider = TextureCache.get(textureKey(emitter), image);
		if(provider == null)return;
		provider.bind();

		float aspect = image.getWidth() / (float)Math.max(1, image.getHeight());
		float boxAspect = w / (float)Math.max(1, h);
		int drawW;
		int drawH;
		if(aspect > boxAspect) {
			drawW = w;
			drawH = Math.max(1, (int)(w / aspect));
		} else {
			drawH = h;
			drawW = Math.max(1, (int)(h * aspect));
		}
		gui.drawTexture(x + (w - drawW) / 2, y + (h - drawH) / 2, drawW, drawH, 0, 0, 1, 1);
	}

	static void drawWorldTexturedSprite(MatrixStack stack, VertexBuffer buffer, ParticleEmitter emitter, Vec3f pos, float size, float alpha, float rotation) {
		float u0 = 0;
		float v0 = 0;
		float u1 = 1;
		float v1 = 1;
		if(!emitter.isMinecraftParticle()) {
			int texW = Math.max(1, emitter.getSpriteTexW());
			int texH = Math.max(1, emitter.getSpriteTexH());
			u0 = emitter.getSpriteU() / (float)texW;
			v0 = emitter.getSpriteV() / (float)texH;
			u1 = (emitter.getSpriteU() + emitter.getSpriteWidth()) / (float)texW;
			v1 = (emitter.getSpriteV() + emitter.getSpriteHeight()) / (float)texH;
		}

		float s = Math.max(0.2f, size);
		float a = Math.max(0.18f, Math.min(1f, alpha));
		// Two crossed quads so the sprite is visible from any camera angle
		drawTexturedQuad(stack, buffer, pos.x, pos.y, pos.z, s, a, rotation, u0, v0, u1, v1, false);
		drawTexturedQuad(stack, buffer, pos.x, pos.y, pos.z, s * 0.72f, Math.min(1f, a * 1.15f), -rotation * 0.6f, u0, v0, u1, v1, true);
	}

	private static void drawTexturedQuad(MatrixStack stack, VertexBuffer buffer, float x, float y, float z, float size, float alpha, float rotation, float u0, float v0, float u1, float v1, boolean crossPlane) {
		Mat4f matrix = stack.getLast().getMatrix();
		Mat3f normal = stack.getLast().getNormal();
		float cos = (float)Math.cos(rotation);
		float sin = (float)Math.sin(rotation);
		float x1 = -size * cos - -size * sin;
		float y1 = -size * sin + -size * cos;
		float x2 = size * cos - -size * sin;
		float y2 = size * sin + -size * cos;
		float x3 = size * cos - size * sin;
		float y3 = size * sin + size * cos;
		float x4 = -size * cos - size * sin;
		float y4 = -size * sin + size * cos;
		if(crossPlane) {
			vertex(buffer, matrix, normal, x, y + y1, z + x1, alpha, u0, v1);
			vertex(buffer, matrix, normal, x, y + y2, z + x2, alpha, u1, v1);
			vertex(buffer, matrix, normal, x, y + y3, z + x3, alpha, u1, v0);
			vertex(buffer, matrix, normal, x, y + y4, z + x4, alpha, u0, v0);
		} else {
			vertex(buffer, matrix, normal, x + x1, y + y1, z, alpha, u0, v1);
			vertex(buffer, matrix, normal, x + x2, y + y2, z, alpha, u1, v1);
			vertex(buffer, matrix, normal, x + x3, y + y3, z, alpha, u1, v0);
			vertex(buffer, matrix, normal, x + x4, y + y4, z, alpha, u0, v0);
		}
	}

	static void drawWorldFallbackSprite(MatrixStack stack, VertexBuffer buffer, Vec3f pos, float size, int color, float alpha, float rotation) {
		float r = ((color >> 16) & 0xff) / 255f;
		float g = ((color >> 8) & 0xff) / 255f;
		float b = (color & 0xff) / 255f;
		float a = Math.max(0.18f, Math.min(1f, alpha));
		float s = Math.max(0.2f, size);
		drawColoredQuad(stack, buffer, pos.x, pos.y, pos.z, s, r, g, b, a, rotation, false);
		drawColoredQuad(stack, buffer, pos.x, pos.y, pos.z, s * 0.72f, r * 1.15f, g * 1.15f, b * 1.15f, Math.min(1f, a * 1.15f), -rotation * 0.6f, true);
	}

	private static void drawColoredQuad(MatrixStack stack, VertexBuffer buffer, float x, float y, float z, float size, float r, float g, float b, float alpha, float rotation, boolean crossPlane) {
		Mat4f matrix = stack.getLast().getMatrix();
		Mat3f normal = stack.getLast().getNormal();
		float cos = (float)Math.cos(rotation);
		float sin = (float)Math.sin(rotation);
		float x1 = -size * cos - -size * sin;
		float y1 = -size * sin + -size * cos;
		float x2 = size * cos - -size * sin;
		float y2 = size * sin + -size * cos;
		float x3 = size * cos - size * sin;
		float y3 = size * sin + size * cos;
		float x4 = -size * cos - size * sin;
		float y4 = -size * sin + size * cos;
		if(crossPlane) {
			vertexColor(buffer, matrix, normal, x, y + y1, z + x1, r, g, b, alpha);
			vertexColor(buffer, matrix, normal, x, y + y2, z + x2, r, g, b, alpha);
			vertexColor(buffer, matrix, normal, x, y + y3, z + x3, r, g, b, alpha);
			vertexColor(buffer, matrix, normal, x, y + y4, z + x4, r, g, b, alpha);
		} else {
			vertexColor(buffer, matrix, normal, x + x1, y + y1, z, r, g, b, alpha);
			vertexColor(buffer, matrix, normal, x + x2, y + y2, z, r, g, b, alpha);
			vertexColor(buffer, matrix, normal, x + x3, y + y3, z, r, g, b, alpha);
			vertexColor(buffer, matrix, normal, x + x4, y + y4, z, r, g, b, alpha);
		}
	}

	private static void vertex(VertexBuffer buffer, Mat4f matrix, Mat3f normal, float x, float y, float z, float alpha, float u, float v) {
		buffer.pos(matrix, x, y, z).color(1, 1, 1, alpha).tex(u, v).normal(normal, 0, 1, 0).endVertex();
	}

	private static void vertexColor(VertexBuffer buffer, Mat4f matrix, Mat3f normal, float x, float y, float z, float r, float g, float b, float alpha) {
		buffer.pos(matrix, x, y, z).color(r, g, b, alpha).tex(0, 0).normal(normal, 0, 1, 0).endVertex();
	}

	private static String textureKey(ParticleEmitter emitter) {
		return emitter.isMinecraftParticle() ? "mc:" + emitter.getMinecraftParticle() : "project:" + emitter.getTextureName();
	}

	static final class TextureCache {
		private static TextureProvider cached;
		private static String cachedKey;

		static TextureProvider get(String key, Image image) {
			if(cached != null && key.equals(cachedKey))return cached;
			if(cached != null)cached.free();
			cached = new TextureProvider(image, new Vec2i(image.getWidth(), image.getHeight()));
			cachedKey = key;
			return cached;
		}

		static void clear() {
			if(cached != null)cached.free();
			cached = null;
			cachedKey = null;
		}
	}
}