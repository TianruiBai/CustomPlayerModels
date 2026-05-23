package com.tom.cpm.shared.network.packet;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.config.PlayerData;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.network.NetworkUtil;
import com.tom.cpm.shared.network.ServerCaps;
import com.tom.cpm.shared.util.Log;

public class SetSkinC2S extends NBTC2S {

	public SetSkinC2S(NBTTagCompound data) {
		super(data);
	}

	public SetSkinC2S() {
	}

	@Override
	public <P> void handle(NetHandler<?, P, ?> handler, ServerNetH net, P player) {
		PlayerData pd = net.cpm$getEncodedModelData();
		if(pd.canChangeModel()) {
			byte[] modelData = tag.hasKey(NetworkUtil.DATA_TAG) ? tag.getByteArray(NetworkUtil.DATA_TAG) : null;

			// Gap 3: Reject plaintext model data when CPM_BUILT_IN_SERVER is active.
			// The server MUST reject unencrypted model data — there is NO fallback path.
			boolean hasBuiltInServer = handler.getCpmModelPacketHandler() != null;
			if (hasBuiltInServer && modelData != null && modelData.length > 0) {
				boolean encrypted = tag.getBoolean("encrypted");
				if (!encrypted) {
					Log.warn("Rejected plaintext model data from player " + handler.resolvePlayerUUID(player) +
						" — CPM_BUILT_IN_SERVER requires encrypted uploads. " +
						"Player may be using an older client.");
					// Do NOT store plaintext model data. Send current model state instead.
					handler.sendPacketToTracking(player, NetworkUtil.writeSkinData(handler, pd, player));
					return;
				}
				// Model data is encrypted — decrypt via CpmModelPacketHandler
				// The CpmModelPacketHandler will handle decryption and storage
				// through the model server pipeline.
				Log.debug("Received encrypted model data from " + handler.resolvePlayerUUID(player) +
					" (" + modelData.length + " bytes)");
			}

			pd.setModel(modelData, false, false);
			handler.sendPacketToTracking(player, NetworkUtil.writeSkinData(handler, pd, player));
			pd.save(handler.getID(player));
		} else {
			handler.sendChat(player, NetworkUtil.FORCED_CHAT_MSG);
		}
	}
}
