package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.IModelServerHandler;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;

/** C→S: All chunks sent; request final verification and storage. */
public class ModelUploadCompleteC2S extends NBTC2S {
	public ModelUploadCompleteC2S() {}
	public ModelUploadCompleteC2S(NBTTagCompound tag) { super(tag); }
	@Override public <P> void handle(NetHandler<?, P, ?> h, ServerNetH net, P pl) {
		IModelServerHandler mh = h.getCpmModelPacketHandler();
		if (mh != null && tag != null) mh.handleUploadComplete(h, net, pl, tag);
	}
}
