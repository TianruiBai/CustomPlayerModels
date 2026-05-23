package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;

/** C→S: Single encrypted chunk of model data. */
public class ModelDataChunkC2S extends NBTC2S {
	public ModelDataChunkC2S() {}
	public ModelDataChunkC2S(NBTTagCompound tag) { super(tag); }
	@Override public <P> void handle(NetHandler<?, P, ?> h, ServerNetH net, P pl) {}
}
