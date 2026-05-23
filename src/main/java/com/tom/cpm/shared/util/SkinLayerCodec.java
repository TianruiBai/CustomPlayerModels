package com.tom.cpm.shared.util;

import java.util.Set;
import java.util.function.Predicate;

public class SkinLayerCodec<T extends Enum<T>> {
	private final T[] values;

	public SkinLayerCodec(T[] values) {
		this.values = values;
	}

	public void setValue(int val, Set<T> to) {
		for (int i = 0; i < values.length; i++) {
			setEncPart(to, val, i, values[i]);
		}
	}

	public int getValue(Predicate<T> from) {
		int v = 0;
		for (int i = 0; i < values.length; i++) {
			if (from.test(values[i]))v |= (1 << i);
		}
		return v;
	}

	private static <T> void setEncPart(Set<T> s, int value, int off, T part) {
		if((value & (1 << off)) != 0)s.add(part);
		else s.remove(part);
	}
}
