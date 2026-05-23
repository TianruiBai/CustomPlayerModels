package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;

/** C→S: Delete a model owned by the requesting player. */
public class ModelDeleteReqC2S extends NBTC2S {
	public ModelDeleteReqC2S() {}
	public ModelDeleteReqC2S(NBTTagCompound tag) { super(tag); }
	@Override public <P> void handle(NetHandler<?, P, ?> h, ServerNetH net, P pl) {}
}
