package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.network.IS2CPacket;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;

/**
 * S→C: Result of a model update (single-packet or chunked).
 * Tag contains: { "modelId": long, "status": "OK"|"NOT_FOUND"|"NOT_OWNER"|"SIZE_EXCEEDED"|"VALIDATION_FAILED",
 *                 "msg": string (optional error detail) }
 */
public class ModelUpdateResultS2C extends NBTS2C implements IS2CPacket {
	public ModelUpdateResultS2C() {}
	public ModelUpdateResultS2C(NBTTagCompound tag) { super(tag); }

	@Override
	public void handle(NetHandler<?, ?, ?> handler, NetH from) {
		// Client-side response handled by CpmModelTransferClient via IModelClientHandler interface.
		// The handleUploadResult() method processes both upload and update completions.
		if (tag != null && handler.getCpmModelClientHandler() != null) {
			handler.getCpmModelClientHandler().handleUploadResult(tag);
		}
	}
}
