package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.IModelServerHandler;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;

/** C→S: Cancel an in-progress upload. */
public class ModelUploadCancelC2S extends NBTC2S {
	public ModelUploadCancelC2S() {}
	public ModelUploadCancelC2S(NBTTagCompound tag) { super(tag); }
	@Override public <P> void handle(NetHandler<?, P, ?> h, ServerNetH net, P pl) {
		IModelServerHandler mh = h.getCpmModelPacketHandler();
		if (mh != null && tag != null) mh.handleUploadCancel(h, net, pl, tag);
	}
}
