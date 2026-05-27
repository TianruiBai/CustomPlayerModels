package com.tom.cpm.shared.editor.gui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.tom.cpm.shared.editor.Editor;

final class PslParticleCatalog {
	private static final List<String> FALLBACK_BUILTIN = Arrays.asList(
			"minecraft:flame",
			"minecraft:smoke",
			"minecraft:campfire_cosy_smoke",
			"minecraft:cloud",
			"minecraft:dust_plume",
			"minecraft:enchanted_hit",
			"minecraft:end_rod",
			"minecraft:firework",
			"minecraft:glow",
			"minecraft:happy_villager",
			"minecraft:heart",
			"minecraft:poof",
			"minecraft:soul_fire_flame",
			"minecraft:splash",
			"minecraft:totem_of_undying"
			);

	private PslParticleCatalog() {
	}

	public static List<String> minecraftParticles() {
		try {
			Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
			Object registry = registries.getField("PARTICLE_TYPE").get(null);
			Method keySet = registry.getClass().getMethod("keySet");
			Object keys = keySet.invoke(registry);
			List<String> out = new ArrayList<>();
			if(keys instanceof Set<?>) {
				for(Object key : (Set<?>) keys)out.add(String.valueOf(key));
			}
			Collections.sort(out);
			if(!out.isEmpty())return out;
		} catch (Throwable ignored) {
		}
		return FALLBACK_BUILTIN;
	}

	public static List<String> customParticles(Editor editor) {
		List<String> out = new ArrayList<>();
		List<String> entries = editor.project.listEntires("particles");
		if(entries == null)return out;
		for(String entry : entries) {
			if(entry.toLowerCase(Locale.ROOT).endsWith(".png"))out.add("particles/" + entry);
		}
		Collections.sort(out);
		return out;
	}
}
