package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.IModelServerHandler;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;

/**
 * C→S: Update an existing model on the server (single-packet, ≤30KB).
 * For larger models, use ModelUploadInitC2S with existingModelId set.
 *
 * Tag contains: { "modelId": long, "data": <encrypted byte[]>,
 *                 "dataIv": <byte[12]>, "dataTag": <byte[16]>,
 *                 "sha256": <byte[32]> }
 */
public class ModelUpdateC2S extends NBTC2S {
	public ModelUpdateC2S() {}
	public ModelUpdateC2S(NBTTagCompound tag) { super(tag); }

	@Override
	public <P> void handle(NetHandler<?, P, ?> handler, ServerNetH net, P player) {
		IModelServerHandler mh = handler.getCpmModelPacketHandler();
		if (mh != null && tag != null) {
			mh.handleModelUpdate(handler, net, player, tag);
		}
	}
}
