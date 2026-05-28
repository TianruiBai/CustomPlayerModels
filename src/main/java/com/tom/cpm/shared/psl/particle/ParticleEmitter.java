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

	public enum ParticleSource {
		CUSTOM_SPRITE,
		MINECRAFT_BUILTIN,
		;
		public static final ParticleSource[] VALUES = values();
	}

	public enum PathMode {
		ATTACHED,
		WORLD,
		ANIMATION_PATH,
		;
		public static final PathMode[] VALUES = values();
	}

	public enum RotationMode {
		/** No rotation – particle always faces the camera. */
		NONE,
		/** Interpolate rotation from rotationStart to rotationEnd over lifetime. */
		LINEAR,
		/** Continuous spin at rotationSpeed degrees/second. */
		SPIN,
		;
		public static final RotationMode[] VALUES = values();
	}

	private ParticleSource particleSource = ParticleSource.CUSTOM_SPRITE;
	private String textureName;
	private String minecraftParticle = "minecraft:flame";
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
	private float rotationStartX, rotationStartY, rotationStartZ;
	private float rotationEndX, rotationEndY = 360, rotationEndZ;
	private RotationMode rotationMode = RotationMode.NONE;
	private float rotationSpeedX, rotationSpeedY, rotationSpeedZ;
	private boolean randomRotationStart;
	private boolean collision;
	private BillboardMode billboard = BillboardMode.CENTER;
	private BlendMode blendMode = BlendMode.ALPHA;
	private boolean respectGraphicsSetting = true;
	private PathMode pathMode = PathMode.ATTACHED;
	private String pathAnimation;
	private boolean inheritTargetMotion = true;
	private Vec3f offset = new Vec3f(0, 0, 0);
	private float windStrength;
	private Vec3f windDirection = new Vec3f(-0.3f, 0.05f, 0.2f);
	private float playbackSpeed = 1;

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
		out.writeFloat(rotationStartX);
		out.writeFloat(rotationStartY);
		out.writeFloat(rotationStartZ);
		out.writeFloat(rotationEndX);
		out.writeFloat(rotationEndY);
		out.writeFloat(rotationEndZ);
		out.writeBoolean(collision);
		out.writeVarInt(billboard.ordinal());
		out.writeVarInt(blendMode.ordinal());
		out.writeBoolean(respectGraphicsSetting);
		out.writeVarInt(particleSource.ordinal());
		out.writeUTF(minecraftParticle != null ? minecraftParticle : "");
		out.writeVarInt(pathMode.ordinal());
		out.writeUTF(pathAnimation != null ? pathAnimation : "");
		out.writeBoolean(inheritTargetMotion);
		out.writeFloat(offset.x);
		out.writeFloat(offset.y);
		out.writeFloat(offset.z);
		out.writeFloat(windStrength);
		out.writeFloat(windDirection.x);
		out.writeFloat(windDirection.y);
		out.writeFloat(windDirection.z);
		out.writeVarInt(rotationMode.ordinal());
		out.writeFloat(rotationSpeedX);
		out.writeFloat(rotationSpeedY);
		out.writeFloat(rotationSpeedZ);
		out.writeBoolean(randomRotationStart);
		out.writeFloat(playbackSpeed);
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
		rotationStartX = in.readFloat();
		rotationStartY = in.readFloat();
		rotationStartZ = in.readFloat();
		rotationEndX = in.readFloat();
		rotationEndY = in.readFloat();
		rotationEndZ = in.readFloat();
		collision = in.readBoolean();
		billboard = BillboardMode.VALUES[in.readVarInt()];
		blendMode = BlendMode.VALUES[in.readVarInt()];
		respectGraphicsSetting = in.readBoolean();
		try {
			particleSource = readEnum(ParticleSource.VALUES, in.readVarInt(), ParticleSource.CUSTOM_SPRITE);
			minecraftParticle = in.readUTF();
			if (minecraftParticle.isEmpty()) minecraftParticle = "minecraft:flame";
			pathMode = readEnum(PathMode.VALUES, in.readVarInt(), PathMode.ATTACHED);
			pathAnimation = in.readUTF();
			if (pathAnimation.isEmpty()) pathAnimation = null;
			inheritTargetMotion = in.readBoolean();
			offset = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
			windStrength = in.readFloat();
			windDirection = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
			rotationMode = readEnum(RotationMode.VALUES, in.readVarInt(), RotationMode.NONE);
			rotationSpeedX = in.readFloat();
			rotationSpeedY = in.readFloat();
			rotationSpeedZ = in.readFloat();
			randomRotationStart = in.readBoolean();
			playbackSpeed = in.readFloat();
		} catch (IOException ignored) {
			particleSource = ParticleSource.CUSTOM_SPRITE;
			minecraftParticle = "minecraft:flame";
			pathMode = PathMode.ATTACHED;
			pathAnimation = null;
			inheritTargetMotion = true;
			offset = new Vec3f(0, 0, 0);
			windStrength = 0;
			windDirection = new Vec3f(-0.3f, 0.05f, 0.2f);
			rotationMode = RotationMode.NONE;
			rotationSpeedX = 0;
			rotationSpeedY = 0;
			rotationSpeedZ = 0;
			randomRotationStart = false;
			playbackSpeed = 1;
		}
	}

	private static <T> T readEnum(T[] values, int ordinal, T fallback) {
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
	}

	// --- Getters/Setters ---

	public ParticleSource getParticleSource() { return particleSource; }
	public void setParticleSource(ParticleSource particleSource) { this.particleSource = particleSource != null ? particleSource : ParticleSource.CUSTOM_SPRITE; }
	public String getTextureName() { return textureName; }
	public void setTextureName(String textureName) { this.textureName = textureName; }
	public String getMinecraftParticle() { return minecraftParticle; }
	public void setMinecraftParticle(String minecraftParticle) { this.minecraftParticle = minecraftParticle; }
	public boolean isMinecraftParticle() { return particleSource == ParticleSource.MINECRAFT_BUILTIN; }
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
	public float getRotationStartX() { return rotationStartX; }
	public void setRotationStartX(float v) { this.rotationStartX = v; }
	public float getRotationStartY() { return rotationStartY; }
	public void setRotationStartY(float v) { this.rotationStartY = v; }
	public float getRotationStartZ() { return rotationStartZ; }
	public void setRotationStartZ(float v) { this.rotationStartZ = v; }
	public float getRotationEndX() { return rotationEndX; }
	public void setRotationEndX(float v) { this.rotationEndX = v; }
	public float getRotationEndY() { return rotationEndY; }
	public void setRotationEndY(float v) { this.rotationEndY = v; }
	public float getRotationEndZ() { return rotationEndZ; }
	public void setRotationEndZ(float v) { this.rotationEndZ = v; }
	public boolean isCollision() { return collision; }
	public void setCollision(boolean collision) { this.collision = collision; }
	public BillboardMode getBillboard() { return billboard; }
	public void setBillboard(BillboardMode billboard) { this.billboard = billboard; }
	public BlendMode getBlendMode() { return blendMode; }
	public void setBlendMode(BlendMode blendMode) { this.blendMode = blendMode; }
	public boolean isRespectGraphicsSetting() { return respectGraphicsSetting; }
	public void setRespectGraphicsSetting(boolean respectGraphicsSetting) { this.respectGraphicsSetting = respectGraphicsSetting; }
	public PathMode getPathMode() { return pathMode; }
	public void setPathMode(PathMode pathMode) { this.pathMode = pathMode != null ? pathMode : PathMode.ATTACHED; }
	public String getPathAnimation() { return pathAnimation; }
	public void setPathAnimation(String pathAnimation) { this.pathAnimation = pathAnimation; }
	public boolean isInheritTargetMotion() { return inheritTargetMotion; }
	public void setInheritTargetMotion(boolean inheritTargetMotion) { this.inheritTargetMotion = inheritTargetMotion; }
	public Vec3f getOffset() { return offset; }
	public void setOffset(Vec3f offset) { this.offset = offset; }
	public RotationMode getRotationMode() { return rotationMode; }
	public void setRotationMode(RotationMode rotationMode) { this.rotationMode = rotationMode != null ? rotationMode : RotationMode.NONE; }
	public float getRotationSpeedX() { return rotationSpeedX; }
	public void setRotationSpeedX(float v) { this.rotationSpeedX = v; }
	public float getRotationSpeedY() { return rotationSpeedY; }
	public void setRotationSpeedY(float v) { this.rotationSpeedY = v; }
	public float getRotationSpeedZ() { return rotationSpeedZ; }
	public void setRotationSpeedZ(float v) { this.rotationSpeedZ = v; }
	public boolean isRandomRotationStart() { return randomRotationStart; }
	public void setRandomRotationStart(boolean randomRotationStart) { this.randomRotationStart = randomRotationStart; }
	public Vec3f getWindDirection() { return windDirection; }
	public void setWindDirection(Vec3f v) { this.windDirection = v; }
	public float getWindStrength() { return windStrength; }
	public void setWindStrength(float v) { this.windStrength = v; }
	public float getPlaybackSpeed() { return playbackSpeed; }
	public void setPlaybackSpeed(float v) { this.playbackSpeed = v; }
}
