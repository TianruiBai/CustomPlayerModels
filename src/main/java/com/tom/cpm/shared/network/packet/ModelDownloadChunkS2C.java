package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.IS2CPacket;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;

/** S→C: Single chunk of a downloaded model. */
public class ModelDownloadChunkS2C extends NBTS2C implements IS2CPacket {
	public ModelDownloadChunkS2C() {}
	public ModelDownloadChunkS2C(NBTTagCompound tag) { super(tag); }
	@Override public void handle(NetHandler<?, ?, ?> h, NetH from) {}
}
