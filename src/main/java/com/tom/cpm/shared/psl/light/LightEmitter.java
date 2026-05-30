package com.tom.cpm.shared.psl.light;

import java.io.IOException;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;

/**
 * Defines a light emitter attached to a model element.
 * The part is rendered on both an emissive (fullbright) render layer
 * AND as a dynamic light source that illuminates surroundings.
 */
public class LightEmitter extends PslElement {

	public enum LightType {
		POINT,
		SPOT,
		AREA,
		;
		public static final LightType[] VALUES = values();
	}

	private LightType lightType = LightType.POINT;
	private int color = 0xFFFFFF;
	private float intensity = 0.7f;
	private float radius = 3.0f;
	private boolean flicker;
	private float flickerSpeed = 1.0f;
	private float flickerAmount = 0.1f;
	private boolean dynamic = true;
	private boolean castShadows = true;
	private Vec3f offset = new Vec3f(0, 0, 0);
	private Vec3f rotation = new Vec3f(0, 0, 0);
	private float spotAngle = 45f;
	private float spotSoftness = 0.2f;
	private float areaWidth = 1f;
	private float areaHeight = 1f;
	private float colorTemperature = 0.5f;

	public LightEmitter() {
	}

	public LightEmitter(long id, int elementId) {
		super(id, elementId);
	}

	@Override
	public PslElementType getType() {
		return PslElementType.LIGHT;
	}

	@Override
	protected void writeData(IOHelper out) throws IOException {
		out.writeVarInt(lightType.ordinal());
		out.writeInt(color);
		out.writeFloat(intensity);
		out.writeFloat(radius);
		out.writeBoolean(flicker);
		out.writeFloat(flickerSpeed);
		out.writeFloat(flickerAmount);
		out.writeBoolean(dynamic);
		out.writeBoolean(castShadows);
		out.writeFloat(offset.x);
		out.writeFloat(offset.y);
		out.writeFloat(offset.z);
		out.writeFloat(rotation.x);
		out.writeFloat(rotation.y);
		out.writeFloat(rotation.z);
		out.writeFloat(spotAngle);
		out.writeFloat(spotSoftness);
		out.writeFloat(areaWidth);
		out.writeFloat(areaHeight);
		out.writeFloat(colorTemperature);
	}

	@Override
	protected void readData(IOHelper in) throws IOException {
		lightType = readEnum(LightType.VALUES, in.readVarInt(), LightType.POINT);
		color = in.readInt();
		intensity = in.readFloat();
		radius = in.readFloat();
		flicker = in.readBoolean();
		flickerSpeed = in.readFloat();
		flickerAmount = in.readFloat();
		dynamic = in.readBoolean();
		castShadows = in.readBoolean();
		try {
			offset = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
			rotation = new Vec3f(in.readFloat(), in.readFloat(), in.readFloat());
			spotAngle = in.readFloat();
			spotSoftness = in.readFloat();
			areaWidth = in.readFloat();
			areaHeight = in.readFloat();
			colorTemperature = in.readFloat();
		} catch (IOException ignored) {
		}
	}

	private static <T> T readEnum(T[] values, int ordinal, T fallback) {
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
	}

	// --- Getters/Setters ---

	public LightType getLightType() { return lightType; }
	public void setLightType(LightType v) { this.lightType = v != null ? v : LightType.POINT; }
	public int getColor() { return color; }
	public void setColor(int color) { this.color = color & 0xFFFFFF; }
	public float getIntensity() { return intensity; }
	public void setIntensity(float intensity) { this.intensity = Math.max(0, Math.min(1, intensity)); }
	public float getRadius() { return radius; }
	public void setRadius(float radius) { this.radius = Math.max(1, Math.min(15, radius)); }
	public boolean isFlicker() { return flicker; }
	public void setFlicker(boolean flicker) { this.flicker = flicker; }
	public float getFlickerSpeed() { return flickerSpeed; }
	public void setFlickerSpeed(float flickerSpeed) { this.flickerSpeed = flickerSpeed; }
	public float getFlickerAmount() { return flickerAmount; }
	public void setFlickerAmount(float flickerAmount) { this.flickerAmount = flickerAmount; }
	public boolean isDynamic() { return dynamic; }
	public void setDynamic(boolean dynamic) { this.dynamic = dynamic; }
	public boolean isCastShadows() { return castShadows; }
	public void setCastShadows(boolean castShadows) { this.castShadows = castShadows; }
	public Vec3f getOffset() { return offset; }
	public void setOffset(Vec3f v) { this.offset = v; }
	public Vec3f getRotation() { return rotation; }
	public void setRotation(Vec3f v) { this.rotation = v; }
	public float getSpotAngle() { return spotAngle; }
	public void setSpotAngle(float v) { this.spotAngle = Math.max(1, Math.min(179, v)); }
	public float getSpotSoftness() { return spotSoftness; }
	public void setSpotSoftness(float v) { this.spotSoftness = Math.max(0, Math.min(1, v)); }
	public float getAreaWidth() { return areaWidth; }
	public void setAreaWidth(float v) { this.areaWidth = Math.max(0.1f, v); }
	public float getAreaHeight() { return areaHeight; }
	public void setAreaHeight(float v) { this.areaHeight = Math.max(0.1f, v); }
	public float getColorTemperature() { return colorTemperature; }
	public void setColorTemperature(float v) { this.colorTemperature = Math.max(0, Math.min(1, v)); }

	/** Apply color temperature to the base RGB color. 0=cool blue, 0.5=neutral, 1=warm orange. */
	public static int applyTemperature(int rgb, float temp) {
		if (temp == 0.5f) return rgb;
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		if (temp < 0.5f) {
			float t = (0.5f - temp) * 2f;
			r = (int)(r * (1 - t * 0.15f));
			g = (int)(g * (1 - t * 0.05f));
			b = (int)(b + (255 - b) * t * 0.3f);
		} else {
			float t = (temp - 0.5f) * 2f;
			r = (int)(r + (255 - r) * t * 0.3f);
			g = (int)(g * (1 - t * 0.2f));
			b = (int)(b * (1 - t * 0.5f));
		}
		return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
	}
}
