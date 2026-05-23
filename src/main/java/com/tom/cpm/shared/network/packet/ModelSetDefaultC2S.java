package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;

/** C→S: Set a model as the player's default for auto-load on join. */
public class ModelSetDefaultC2S extends NBTC2S {
	public ModelSetDefaultC2S() {}
	public ModelSetDefaultC2S(NBTTagCompound tag) { super(tag); }
	@Override public <P> void handle(NetHandler<?, P, ?> h, ServerNetH net, P pl) {}
}
