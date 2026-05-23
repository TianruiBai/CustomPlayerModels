package com.tom.cpm.shared.animation;

import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.network.ModelEventType;

public class ServerAnimationState {
	public boolean updated, isSelf;
	public float falling;
	public boolean creativeFlying, inMenu;
	public float health, hunger, air;

	public static ServerAnimationState mergedCopy(ServerAnimationState local, ServerAnimationState server) {
		ServerAnimationState sync = new ServerAnimationState();
		sync.falling = Math.max(local.falling, server.falling);
		sync.creativeFlying = local.creativeFlying || server.creativeFlying;
		sync.inMenu = local.inMenu || server.inMenu;
		sync.health = getUpdated(local, server, ModelEventType.HEALTH).health;
		sync.hunger = getUpdated(local, server, ModelEventType.HUNGER).hunger;
		sync.air = getUpdated(local, server, ModelEventType.AIR).air;
		return sync;
	}

	private static ServerAnimationState getUpdated(ServerAnimationState local, ServerAnimationState server, ModelEventType type) {
		boolean reqEvent = MinecraftClientAccess.get().requiresSelfEventForAnimation(type);
		if (server.updated && (!server.isSelf || reqEvent)) {
			return server;
		}
		return local;
	}
}
