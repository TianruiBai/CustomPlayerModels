package com.tom.cpm.client.psl;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.psl.sound.SoundEmitter;

/**
 * Client-side sound player for NeoForge 1.21.
 * Plays OGG sound effects via Minecraft's SoundManager.
 */
public class SoundPlayer {

	private final Map<String, ResourceLocation> soundCache = new HashMap<>();

	/**
	 * Play a sound effect at a world-space position.
	 */
	public void play(SoundEmitter def, Vec3f worldPos) {
		if (def == null) return;

		ResourceLocation soundId = getOrCache(def.getSoundFile());
		if (soundId == null) return;

		float pitch = def.getPitch() + (float)(Math.random() * 2 - 1) * def.getPitchVariation();
		pitch = Math.max(0.5f, Math.min(2.0f, pitch));

		SoundSource source;
		switch (def.getCategory()) {
			case AMBIENT: source = SoundSource.AMBIENT; break;
			case MASTER: source = SoundSource.MASTER; break;
			default: source = SoundSource.PLAYERS; break;
		}

		boolean looping = def.isLoop();
		float vol = def.getVolume();
		float attenuation = switch (def.getAttenuation()) {
			case NONE -> 0f;    // No distance attenuation
			case LINEAR -> 1f;  // Linear falloff
			default -> 16f;     // Inverse (logarithmic)
		};

		double x = worldPos != null ? worldPos.x : 0;
		double y = worldPos != null ? worldPos.y : 0;
		double z = worldPos != null ? worldPos.z : 0;

		SoundInstance instance = new SimpleSoundInstance(
			soundId, source, vol, pitch, net.minecraft.util.RandomSource.create(),
			looping, 0, SoundInstance.Attenuation.LINEAR,
			x, y, z, false
		);

		Minecraft.getInstance().getSoundManager().play(instance);
	}

	private ResourceLocation getOrCache(String file) {
		if (file == null || file.isEmpty()) return null;
		return soundCache.computeIfAbsent(file, f -> {
			if (f.startsWith("sounds/")) {
				return ResourceLocation.fromNamespaceAndPath("cpm", f);
			}
			return ResourceLocation.parse(f);
		});
	}

	public void clear() {
		soundCache.clear();
	}
}
