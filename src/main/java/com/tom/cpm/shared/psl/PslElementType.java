package com.tom.cpm.shared.psl;

/**
 * Enum identifying the type of a PSL element.
 * Used for serialization and UI grouping.
 */
public enum PslElementType {
	PARTICLE,
	PHYSICS,
	SOUND,
	LIGHT,
	MIDI,
	;
	public static final PslElementType[] VALUES = values();
}
