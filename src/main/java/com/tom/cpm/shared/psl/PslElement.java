package com.tom.cpm.shared.psl;

import java.io.IOException;

import com.tom.cpm.shared.io.IOHelper;

/**
 * Base class for all PSL (Particle · Physics · Sound · Light) elements.
 * Each element has a unique ID, an optional name, a target model element,
 * and a trigger that determines when it is active.
 */
public abstract class PslElement {
	protected long id;
	protected String name;
	protected int elementId;
	protected PslTrigger trigger;
	protected boolean enabled = true;

	protected PslElement() {
		this.trigger = new PslTrigger(PslTrigger.TriggerType.ALWAYS);
	}

	protected PslElement(long id, int elementId) {
		this.id = id;
		this.elementId = elementId;
		this.trigger = new PslTrigger(PslTrigger.TriggerType.ALWAYS);
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getElementId() {
		return elementId;
	}

	public void setElementId(int elementId) {
		this.elementId = elementId;
	}

	public PslTrigger getTrigger() {
		return trigger;
	}

	public void setTrigger(PslTrigger trigger) {
		this.trigger = trigger;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isActive(PslTriggerState state) {
		return trigger.isActive(state);
	}

	/**
	 * Get the type identifier for serialization.
	 */
	public abstract PslElementType getType();

	/**
	 * Write this element's data to the output (excluding type tag and common fields).
	 */
	protected abstract void writeData(IOHelper out) throws IOException;

	/**
	 * Read this element's data from the input (excluding type tag and common fields).
	 */
	protected abstract void readData(IOHelper in) throws IOException;

	/**
	 * Write the complete element including type tag and common header.
	 */
	public final void write(IOHelper out) throws IOException {
		out.writeVarInt(getType().ordinal());
		out.writeLong(id);
		out.writeUTF(name != null ? name : "");
		out.writeVarInt(elementId);
		trigger.write(out);
		out.writeBoolean(enabled);
		writeData(out);
	}

	/**
	 * Read a PslElement from the input. The type tag has already been read.
	 */
	public static PslElement read(IOHelper in, PslElementType type) throws IOException {
		long id = in.readLong();
		String name = in.readUTF();
		int elementId = in.readVarInt();
		PslTrigger trigger = PslTrigger.read(in);

		PslElement elem;
		switch (type) {
		case PARTICLE:
			elem = new com.tom.cpm.shared.psl.particle.ParticleEmitter();
			break;
		case PHYSICS:
			elem = new com.tom.cpm.shared.psl.physics.PhysicsBone();
			break;
		case SOUND:
			elem = new com.tom.cpm.shared.psl.sound.SoundEmitter();
			break;
		case LIGHT:
			elem = new com.tom.cpm.shared.psl.light.LightEmitter();
			break;
		case MIDI:
			elem = new com.tom.cpm.shared.psl.sound.MidiEmitter();
			break;
		default:
			throw new IOException("Unknown PSL element type: " + type);
		}

		elem.id = id;
		elem.name = name.isEmpty() ? null : name;
		elem.elementId = elementId;
		elem.trigger = trigger;
		if(in.available() > 0) {
			elem.enabled = in.readBoolean();
		}
		elem.readData(in);
		return elem;
	}

	@Override
	public String toString() {
		return getType() + " [" + id + "]" + (name != null ? " " + name : "");
	}
}
