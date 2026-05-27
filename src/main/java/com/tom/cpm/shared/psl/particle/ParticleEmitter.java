package com.tom.cpm.shared.psl.particle;

import java.io.IOException;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;

/**
 * Defines a particle emitter attached to a model element.
 * Spawns 2D sprite particles with configurable appearance, motion, and lifecycle.
 */
public class ParticleEmitter extends PslElement {

	public enum EmitterType {
		POINT,
		BOX,
		SPHERE,
		;
		public static final EmitterType[] VALUES = values();
	}

	public enum BillboardMode {
		FIXED,
		VERTICAL,
		HORIZONTAL,
		CENTER,
		;
		public static final BillboardMode[] VALUES = values();
	}

	public enum BlendMode {
		ALPHA,
		ADDITIVE,
		MULTIPLY,
		;
		public static final BlendMode[] VALUES = values();
	}

	private String textureName;
	private float spriteWidth = 1;
	private float spriteHeight = 1;
	private int spriteU;
	private int spriteV;
	private int spriteTexW = 16;
	private int spriteTexH = 16;
	private EmitterType emitterType = EmitterType.POINT;
	private Vec3f emitterSize = new Vec3f(0, 0, 0);
	private float rate = 10;
	private int maxParticles = 50;
	private float lifeMin = 0.5f;
	private float lifeMax = 1.5f;
	private Vec3f velocity = new Vec3f(0, 0.1f, 0);
	private float velocityVariation = 0.5f;
	private float gravity;
	private float scaleStart = 1;
	private float scaleEnd;
	private int colorStart = 0xFFFFFFFF;
	private int colorEnd = 0x00FFFFFF;
	private float alphaStart = 1;
	private float alphaEnd;
	private float rotationStart;
	private float rotationEnd = 360;
	private boolean collision;
	private BillboardMode billboard = BillboardMode.CENTER;
	private BlendMode blendMode = BlendMode.ALPHA;
	private boolean respectGraphicsSetting = true;

	public ParticleEmitter() {
	}

	public ParticleEmitter(long id, int elementId) {
		super(id, elementId);
	}

	@Override
	public PslElementType getType() {
		return PslElementType.PARTICLE;
	}

	@Override
	protected void writeData(IOHelper out) throws IOException {
		out.writeUTF(textureName != null ? textureName : "");
		out.writeFloat(spriteWidth);
		out.writeFloat(spriteHeight);
		out.writeVarInt(spriteU);
		out.writeVarInt(spriteV);
		out.writeVarInt(spriteTexW);
		out.writeVarInt(spriteTexH);
		out.writeVarInt(emitterType.ordinal());
		out.writeFloat(emitterSize.x);
		out.writeFloat(emitterSize.y);
		out.writeFloat(emitterSize.z);
		out.writeFloat(rate);
		out.writeVarInt(maxParticles);
		out.writeFloat(lifeMin);
		out.writeFloat(lifeMax);
		out.writeFloat(velocity.x);
		out.writeFloat(velocity.y);
		out.writeFloat(velocity.z);
		out.writeFloat(velocityVariation);
		out.writeFloat(gravity);
		out.writeFloat(scaleStart);
		out.writeFloat(scaleEnd);
		out.writeInt(colorStart);
		out.writeInt(colorEnd);
		out.writeFloat(alphaStart);
		out.writeFloat(alphaEnd);
		out.writeFloat(rotationStart);
		out.writeFloat(rotationEnd);
		out.writeBoolean(collision);
		out.writeVarInt(billboard.ordinal());
		out.writeVarInt(blendMode.ordinal());
		out.writeBoolean(respectGraphicsSetting);
	}

	@Override
	protected void readData(IOHelper in) throws IOException {
		textureName = in.readUTF();
		if (textureName.isEmpty()) textureName = null;
		spriteWidth = in.readFloat();
		spriteHeight = in.readFloat();
		spriteU = in.readVarInt();
		spriteV = in.readVarInt();
		spriteTexW = in.readVarInt();
		spriteTexH = in.readVarInt();
		emitterType = EmitterType.VALUES[in.readVarInt()];
		emitterSize = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
		rate = in.readFloat();
		maxParticles = in.readVarInt();
		lifeMin = in.readFloat();
		lifeMax = in.readFloat();
		velocity = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
		velocityVariation = in.readFloat();
		gravity = in.readFloat();
		scaleStart = in.readFloat();
		scaleEnd = in.readFloat();
		colorStart = in.readInt();
		colorEnd = in.readInt();
		alphaStart = in.readFloat();
		alphaEnd = in.readFloat();
		rotationStart = in.readFloat();
		rotationEnd = in.readFloat();
		collision = in.readBoolean();
		billboard = BillboardMode.VALUES[in.readVarInt()];
		blendMode = BlendMode.VALUES[in.readVarInt()];
		respectGraphicsSetting = in.readBoolean();
	}

	// --- Getters/Setters ---

	public String getTextureName() { return textureName; }
	public void setTextureName(String textureName) { this.textureName = textureName; }
	public float getSpriteWidth() { return spriteWidth; }
	public void setSpriteWidth(float spriteWidth) { this.spriteWidth = spriteWidth; }
	public float getSpriteHeight() { return spriteHeight; }
	public void setSpriteHeight(float spriteHeight) { this.spriteHeight = spriteHeight; }
	public int getSpriteU() { return spriteU; }
	public void setSpriteU(int spriteU) { this.spriteU = spriteU; }
	public int getSpriteV() { return spriteV; }
	public void setSpriteV(int spriteV) { this.spriteV = spriteV; }
	public int getSpriteTexW() { return spriteTexW; }
	public void setSpriteTexW(int spriteTexW) { this.spriteTexW = spriteTexW; }
	public int getSpriteTexH() { return spriteTexH; }
	public void setSpriteTexH(int spriteTexH) { this.spriteTexH = spriteTexH; }
	public EmitterType getEmitterType() { return emitterType; }
	public void setEmitterType(EmitterType emitterType) { this.emitterType = emitterType; }
	public Vec3f getEmitterSize() { return emitterSize; }
	public void setEmitterSize(Vec3f emitterSize) { this.emitterSize = emitterSize; }
	public float getRate() { return rate; }
	public void setRate(float rate) { this.rate = rate; }
	public int getMaxParticles() { return maxParticles; }
	public void setMaxParticles(int maxParticles) { this.maxParticles = maxParticles; }
	public float getLifeMin() { return lifeMin; }
	public void setLifeMin(float lifeMin) { this.lifeMin = lifeMin; }
	public float getLifeMax() { return lifeMax; }
	public void setLifeMax(float lifeMax) { this.lifeMax = lifeMax; }
	public Vec3f getVelocity() { return velocity; }
	public void setVelocity(Vec3f velocity) { this.velocity = velocity; }
	public float getVelocityVariation() { return velocityVariation; }
	public void setVelocityVariation(float velocityVariation) { this.velocityVariation = velocityVariation; }
	public float getGravity() { return gravity; }
	public void setGravity(float gravity) { this.gravity = gravity; }
	public float getScaleStart() { return scaleStart; }
	public void setScaleStart(float scaleStart) { this.scaleStart = scaleStart; }
	public float getScaleEnd() { return scaleEnd; }
	public void setScaleEnd(float scaleEnd) { this.scaleEnd = scaleEnd; }
	public int getColorStart() { return colorStart; }
	public void setColorStart(int colorStart) { this.colorStart = colorStart; }
	public int getColorEnd() { return colorEnd; }
	public void setColorEnd(int colorEnd) { this.colorEnd = colorEnd; }
	public float getAlphaStart() { return alphaStart; }
	public void setAlphaStart(float alphaStart) { this.alphaStart = alphaStart; }
	public float getAlphaEnd() { return alphaEnd; }
	public void setAlphaEnd(float alphaEnd) { this.alphaEnd = alphaEnd; }
	public float getRotationStart() { return rotationStart; }
	public void setRotationStart(float rotationStart) { this.rotationStart = rotationStart; }
	public float getRotationEnd() { return rotationEnd; }
	public void setRotationEnd(float rotationEnd) { this.rotationEnd = rotationEnd; }
	public boolean isCollision() { return collision; }
	public void setCollision(boolean collision) { this.collision = collision; }
	public BillboardMode getBillboard() { return billboard; }
	public void setBillboard(BillboardMode billboard) { this.billboard = billboard; }
	public BlendMode getBlendMode() { return blendMode; }
	public void setBlendMode(BlendMode blendMode) { this.blendMode = blendMode; }
	public boolean isRespectGraphicsSetting() { return respectGraphicsSetting; }
	public void setRespectGraphicsSetting(boolean respectGraphicsSetting) { this.respectGraphicsSetting = respectGraphicsSetting; }
}
