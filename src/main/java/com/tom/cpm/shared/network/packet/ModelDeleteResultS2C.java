package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.IS2CPacket;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;

/** S→C: Result of a model deletion request. */
public class ModelDeleteResultS2C extends NBTS2C implements IS2CPacket {
	public ModelDeleteResultS2C() {}
	public ModelDeleteResultS2C(NBTTagCompound tag) { super(tag); }
	@Override public void handle(NetHandler<?, ?, ?> h, NetH from) {}
}
