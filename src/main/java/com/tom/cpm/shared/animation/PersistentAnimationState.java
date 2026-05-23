package com.tom.cpm.shared.animation;

import java.util.Arrays;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.network.ModelEventType;
import com.tom.cpm.shared.network.NetworkUtil;

public class PersistentAnimationState {
	public final ServerAnimationState serverState = new ServerAnimationState();
	public final ServerAnimationState localState = new ServerAnimationState();
	public long jumping;
	public byte[] gestureData;
	public byte[] prevGestureData;
	public long lastGestureReceiveTime;

	public VanillaPose prevPose;
	public IPose currentPose;

	public void apply(AnimationState state) {
		state.syncState = ServerAnimationState.mergedCopy(localState, serverState);
		state.jumping = jumping;
		state.gestureData = gestureData != null ? Arrays.copyOf(gestureData, gestureData.length) : null;
		state.prevGestureData = prevGestureData != null ? Arrays.copyOf(prevGestureData, prevGestureData.length) : null;
		state.lastGestureReceiveTime = lastGestureReceiveTime;
	}

	public void receiveEvent(NBTTagCompound tag, boolean isClient) {
		serverState.isSelf = isClient;
		if(!isClient || tag.getBoolean(NetworkUtil.SELF_EVENT)) {
			serverState.updated = true;
			for(ModelEventType t : ModelEventType.VALUES) {
				if(tag.hasKey(t.getName())) {
					t.read(this, tag);
				}
			}
		}
		if(tag.hasKey(NetworkUtil.GESTURE)) {
			prevGestureData = gestureData;
			gestureData = tag.getByteArray(NetworkUtil.GESTURE);
			lastGestureReceiveTime = MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().getTime();
		}
	}
}
