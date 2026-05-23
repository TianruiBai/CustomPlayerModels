package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.IModelClientHandler;
import com.tom.cpm.shared.network.IS2CPacket;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;

/** S→C: Acknowledge chunk receipt (OK or RETRY). */
public class ModelDataChunkAckS2C extends NBTS2C implements IS2CPacket {
	public ModelDataChunkAckS2C() {}
	public ModelDataChunkAckS2C(NBTTagCompound tag) { super(tag); }
	@Override public void handle(NetHandler<?, ?, ?> h, NetH from) {
		IModelClientHandler ch = h.getCpmModelClientHandler();
		if (ch != null && tag != null) ch.handleDataChunkAck(tag);
	}
}
