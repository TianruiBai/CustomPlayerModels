package com.tom.cpm.api;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.tom.cpm.shared.util.Log;

public class CPMApiManager implements CPMPluginRegistry {
	private Set<Class<?>> registered = new HashSet<>();
	private List<ICPMPlugin> plugins = new ArrayList<>();
	protected ClientApi client;
	protected CommonApi common;

	@Override
	public void register(ICPMPlugin plugin) {
		plugins.add(plugin);
		registered.add(plugin.getClass());
	}

	@SuppressWarnings("unchecked")
	public void registerSupplier(String sender, Supplier<?> supplier) {
		try {
			ICPMPlugin plugin = ((Supplier<ICPMPlugin>) supplier.get()).get();
			register(plugin);
		} catch (Throwable e) {
			Log.error("Mod " + sender + " provides a broken implementation of CPM api", e);
		}
	}

	public void registerPluginClass(String className) {
		try {
			Class<?> asmClass = Class.forName(className);
			if (registered.contains(asmClass))return;
			Class<? extends ICPMPlugin> asmInstanceClass = asmClass.asSubclass(ICPMPlugin.class);
			Constructor<? extends ICPMPlugin> constructor = asmInstanceClass.getDeclaredConstructor();
			ICPMPlugin instance = constructor.newInstance();
			register(instance);
		} catch (ReflectiveOperationException | LinkageError e) {
			Log.error("Failed to load: " + className, e);
		}
	}

	public String getPluginStatus() {
		StringBuilder bb = new StringBuilder();
		bb.append("Loaded plugins: (");
		bb.append(plugins.size());
		bb.append(")\n");
		plugins.forEach(p -> {
			bb.append('\t');
			bb.append(p.getOwnerModId());
			bb.append('\n');
		});
		return bb.toString();
	}

	public ClientApi clientApi() {
		return client;
	}

	public CommonApi commonApi() {
		return common;
	}

	protected void initClient() {
		plugins.forEach(client::callInit);
	}

	protected void initCommon() {
		plugins.forEach(common::callInit);
	}

	public ClientApi.ApiBuilder buildClient() {
		return new ClientApi.ApiBuilder(this);
	}

	public CommonApi.ApiBuilder buildCommon() {
		return new CommonApi.ApiBuilder(this);
	}
}
