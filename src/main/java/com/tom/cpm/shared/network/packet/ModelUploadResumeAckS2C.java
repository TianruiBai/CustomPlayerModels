package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.IModelClientHandler;
import com.tom.cpm.shared.network.IS2CPacket;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;

/** S→C: Respond to upload resume request with last received chunk index. */
public class ModelUploadResumeAckS2C extends NBTS2C implements IS2CPacket {
	public ModelUploadResumeAckS2C() {}
	public ModelUploadResumeAckS2C(NBTTagCompound tag) { super(tag); }
	@Override public void handle(NetHandler<?, ?, ?> h, NetH from) {
		IModelClientHandler ch = h.getCpmModelClientHandler();
		if (ch != null && tag != null) ch.handleUploadResumeAck(tag);
	}
}
