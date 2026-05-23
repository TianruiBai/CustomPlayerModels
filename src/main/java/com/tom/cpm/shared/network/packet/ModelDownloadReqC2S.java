package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;

/** C→S: Request to download a specific model. */
public class ModelDownloadReqC2S extends NBTC2S {
	public ModelDownloadReqC2S() {}
	public ModelDownloadReqC2S(NBTTagCompound tag) { super(tag); }
	@Override public <P> void handle(NetHandler<?, P, ?> h, ServerNetH net, P pl) {}
}
