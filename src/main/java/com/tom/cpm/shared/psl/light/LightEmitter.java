package com.tom.cpm.shared.psl.light;

import java.io.IOException;

import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;

/**
 * Defines a light emitter attached to a model element.
 * The part is rendered on both an emissive (fullbright) render layer
 * AND as a dynamic light source that illuminates surroundings.
 */
public class LightEmitter extends PslElement {

	private int color = 0xFFFFFF;
	private float intensity = 0.7f;
	private float radius = 3.0f;
	private boolean flicker;
	private float flickerSpeed = 1.0f;
	private float flickerAmount = 0.1f;
	private boolean dynamic = true;
	private boolean castShadows = true;

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
		out.writeInt(color);
		out.writeFloat(intensity);
		out.writeFloat(radius);
		out.writeBoolean(flicker);
		out.writeFloat(flickerSpeed);
		out.writeFloat(flickerAmount);
		out.writeBoolean(dynamic);
		out.writeBoolean(castShadows);
	}

	@Override
	protected void readData(IOHelper in) throws IOException {
		color = in.readInt();
		intensity = in.readFloat();
		radius = in.readFloat();
		flicker = in.readBoolean();
		flickerSpeed = in.readFloat();
		flickerAmount = in.readFloat();
		dynamic = in.readBoolean();
		castShadows = in.readBoolean();
	}

	// --- Getters/Setters ---

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
}
