package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.network.NetH;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.network.NetworkUtil;
import com.tom.cpm.shared.util.Log;

public class SetSkinS2C extends NBTEntityS2C {

	public SetSkinS2C() {
		super();
	}

	public SetSkinS2C(int entityId, NBTTagCompound data) {
		super(entityId, data);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <P> void handle(NetHandler<?, P, ?> handler, NetH from, P player) {
		byte[] modelData = tag.hasKey(NetworkUtil.DATA_TAG) ? tag.getByteArray(NetworkUtil.DATA_TAG) : null;
		boolean forced = tag.getBoolean(NetworkUtil.FORCED_TAG);
		if (modelData != null && modelData.length > 0) {
			try {
				java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
				byte[] hash = md.digest(modelData);
				short csum = 0;
				for (int i = 1; i < modelData.length - 2; i++) csum += (modelData[i] & 0xFF);
				short embedded = (short)(((modelData[modelData.length - 2] & 0xFF) << 8) | (modelData[modelData.length - 1] & 0xFF));
				Log.info("SetSkinS2C CLIENT hash=" + java.util.HexFormat.of().formatHex(hash) + " csum=" + csum + " embedded=" + embedded + " size=" + modelData.length);
			} catch (Exception ignored) {}
		}
		Log.info("SetSkinS2C received: entityId=" + entityId + " dataSize=" + (modelData != null ? modelData.length : 0) + " forced=" + forced);
		MinecraftClientAccess.get().getDefinitionLoader().setModel(handler.getLoaderId(player), modelData, forced);
	}

}
