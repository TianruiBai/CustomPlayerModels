package com.tom.cpm.shared.editor.gui;

import java.lang.reflect.Method;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.psl.sound.SoundEmitter;

final class PslPreviewUtil {
	private PslPreviewUtil() {
	}

	public static void previewSound(SoundEmitter emitter) {
		try {
			Class<?> soundPlayerClass = Class.forName("com.tom.cpm.client.psl.SoundPlayer");
			Object soundPlayer = soundPlayerClass.getConstructor().newInstance();
			Method play = soundPlayerClass.getMethod("play", SoundEmitter.class, Vec3f.class);
			play.invoke(soundPlayer, emitter, Vec3f.ZERO);
		} catch (Throwable ignored) {
		}
	}
}
