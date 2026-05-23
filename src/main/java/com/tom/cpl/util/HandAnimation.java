package com.tom.cpl.util;

public enum HandAnimation {
	NONE,
	EAT,
	DRINK,
	BLOCK,
	BOW,
	TRIDENT("SPEAR"),
	CROSSBOW,
	SPYGLASS,
	TOOT_HORN,
	BRUSH,
	BUNDLE,
	SPEAR,
	;
	private final String alt;

	private HandAnimation(String alt) {
		this.alt = alt;
	}

	private HandAnimation() {
		this(null);
	}

	public static final HandAnimation[] VALUES = values();

	public static <T extends Enum<T>> HandAnimation of(T value) {
		String name = value.name();
		for (int i = 0; i < VALUES.length; i++) {
			HandAnimation e = VALUES[i];
			if(e.alt != null && e.alt.equalsIgnoreCase(name))return e;
			if(e.name().equalsIgnoreCase(name))return e;
		}
		return NONE;
	}

	public static <T extends Enum<T>> HandAnimation map(T value) {
		String name = value.name();
		for (int i = 0; i < VALUES.length; i++) {
			HandAnimation e = VALUES[i];
			if(e.name().equalsIgnoreCase(name))return e;
		}
		return NONE;
	}
}
