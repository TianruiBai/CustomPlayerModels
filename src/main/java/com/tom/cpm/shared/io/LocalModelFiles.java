package com.tom.cpm.shared.io;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.definition.Link;

public final class LocalModelFiles {
	public static final String LOCAL_OVERFLOW_LOADER = "cpmdb_overflow";

	private LocalModelFiles() {
	}

	public static boolean isModelFileName(String name) {
		return name != null && name.toLowerCase(Locale.ROOT).endsWith(".cpmmodel");
	}

	public static boolean isModelFile(File file) {
		return file != null && file.isFile() && isModelFileName(file.getName());
	}

	public static File resolveModelFile(File modelsDir, String selectedModel) throws IOException {
		if (selectedModel == null || selectedModel.isEmpty()) throw new IOException("No selected model");
		File root = modelsDir.getCanonicalFile();
		File resolved = new File(modelsDir, selectedModel.replace('\\', '/')).getCanonicalFile();
		if (!resolved.toPath().startsWith(root.toPath())) throw new IOException("Model path escapes player_models: " + selectedModel);
		if (!isModelFileName(resolved.getName())) throw new IOException("Unsupported model file: " + selectedModel);
		return resolved;
	}

	public static String toRelativeModelPath(File modelsDir, File modelFile) throws IOException {
		File root = modelsDir.getCanonicalFile();
		File resolved = modelFile.getCanonicalFile();
		if (!resolved.toPath().startsWith(root.toPath())) throw new IOException("Model path escapes player_models: " + modelFile);
		return root.toPath().relativize(resolved.toPath()).toString().replace(File.separatorChar, '/');
	}

	public static List<File> listModelsRecursive(File modelsDir) {
		List<File> models = new ArrayList<>();
		collectModels(modelsDir, models);
		models.sort((a, b) -> a.getPath().compareToIgnoreCase(b.getPath()));
		return models;
	}

	public static byte[] loadLocalOverflowResource(String path) throws IOException {
		File modelsDir = new File(MinecraftClientAccess.get().getGameDir(), "player_models");
		Link target = new Link(LOCAL_OVERFLOW_LOADER, path);
		for (File file : listModelsRecursive(modelsDir)) {
			try {
				ModelFile modelFile = ModelFile.load(file);
				if (target.equals(modelFile.getOverflowLink()) && modelFile.getOverflowLocal() != null) {
					return modelFile.getOverflowLocal().clone();
				}
			} catch (IOException ignored) {
			}
		}
		throw new IOException("Local model overflow not found: " + target);
	}

	private static void collectModels(File dir, List<File> models) {
		File[] files = dir != null && dir.exists() ? dir.listFiles() : null;
		if (files == null) return;
		for (File file : files) {
			if (file.isDirectory()) {
				if (!"autosaves".equals(file.getName())) collectModels(file, models);
			} else if (isModelFile(file)) {
				models.add(file);
			}
		}
	}
}