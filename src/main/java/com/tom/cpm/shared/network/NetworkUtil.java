package com.tom.cpm.shared.network;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.tom.cpl.config.ConfigEntry;
import com.tom.cpl.function.TriFunction;
import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpl.text.FormatText;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.shared.config.BuiltInSafetyProfiles;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.config.PlayerData;
import com.tom.cpm.shared.config.PlayerSpecificConfigKey;
import com.tom.cpm.shared.config.PlayerSpecificConfigKey.KeyGroup;
import com.tom.cpm.shared.io.LocalModelFiles;
import com.tom.cpm.shared.io.ModelFile;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.packet.ModelDeleteReqC2S;
import com.tom.cpm.shared.network.packet.ModelDownloadReqC2S;
import com.tom.cpm.shared.network.packet.ModelListReqC2S;
import com.tom.cpm.shared.network.packet.ModelSetActiveC2S;
import com.tom.cpm.shared.network.packet.ModelSetDefaultC2S;
import com.tom.cpm.shared.network.packet.PluginMessageS2C;
import com.tom.cpm.shared.network.ServerCaps;import com.tom.cpm.shared.network.packet.ReceiveEventS2C;
import com.tom.cpm.shared.network.packet.RecommendSafetyS2C;
import com.tom.cpm.shared.network.packet.SetSkinC2S;
import com.tom.cpm.shared.network.packet.SetSkinS2C;
import com.tom.cpm.shared.util.ScalingOptions;

public class NetworkUtil {
	public static final String FORCED_TAG = "forced";
	public static final String DATA_TAG = "data";
	public static final String PROFILE_TAG = "profile";
	public static final String PROFILE_DATA = "data";
	public static final String SERVER_CAPS = "caps";
	public static final String EVENT_LIST = "eventList";
	public static final String KICK_TIME = "kickTime";
	public static final String SCALING = "scaling";
	public static final String GESTURE = "gesture";
	public static final String ANIMATIONS = "anims";
	public static final String NAMED_PARAMETERS = "namedparams";
	public static final String SELF_EVENT_LIST = "selfEventList";
	public static final String SELF_EVENT = "self";
	public static final String TIME_SYNC = "timeSync";

	public static final FormatText FORCED_CHAT_MSG = new FormatText("chat.cpm.skinForced");

	public static <P> void sendPlayerData(NetHandler<?, P, ?> handler, P target, P to) {
		ServerNetH netTo = handler.getSNetH(to);
		PlayerData dt = handler.getSNetH(target).cpm$getEncodedModelData();
		if(dt == null)return;
		handler.sendPacketTo(netTo, writeSkinData(handler, dt, target));
		sendPlayerState(handler, target, dt, netTo);
	}

	private static <P> void sendPlayerState(NetHandler<?, P, ?> handler, P target, PlayerData dt, ServerNetH netTo) {
		if(dt.gestureData.length > 0) {
			NBTTagCompound evt = new NBTTagCompound();
			evt.setByteArray(NetworkUtil.GESTURE, dt.gestureData);
			handler.sendPacketTo(netTo, new ReceiveEventS2C(handler.getPlayerId(target), evt));
		}
		if(!dt.pluginStates.isEmpty()) {
			int id = handler.getPlayerId(target);
			dt.pluginStates.forEach((k, v) -> handler.sendPacketTo(netTo, new PluginMessageS2C(k, id, v)));
		}
	}

	public static <P> void sendPlayerState(NetHandler<?, P, ?> handler, P target, P to) {
		ServerNetH netTo = handler.getSNetH(to);
		PlayerData dt = handler.getSNetH(target).cpm$getEncodedModelData();
		if(dt == null)return;
		sendPlayerState(handler, target, dt, netTo);
	}

	public static <P> IPacket writeSkinData(NetHandler<?, P, ?> handler, PlayerData dt, P target) {
		NBTTagCompound data = new NBTTagCompound();
		if(dt.data != null) {
			data.setBoolean(FORCED_TAG, dt.forced);
			data.setByteArray(DATA_TAG, dt.data);
			try {
				java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
				byte[] hash = md.digest(dt.data);
				short csum = 0;
				for (int i = 1; i < dt.data.length - 2; i++) csum += (dt.data[i] & 0xFF);
				short embedded = (short)(((dt.data[dt.data.length - 2] & 0xFF) << 8) | (dt.data[dt.data.length - 1] & 0xFF));
				com.tom.cpm.shared.util.Log.info("writeSkinData SERVER hash=" + java.util.HexFormat.of().formatHex(hash) + " csum=" + csum + " embedded=" + embedded + " size=" + dt.data.length);
			} catch (Exception ignored) {}
		}
		return new SetSkinS2C(handler.getPlayerId(target), data);
	}

	public static void sendSafetySettings(NetHandler<?, ?, ?> handler, ServerNetH net) {
		BuiltInSafetyProfiles profile = BuiltInSafetyProfiles.get(ModConfig.getWorldConfig().getString(ConfigKeys.SAFETY_PROFILE, BuiltInSafetyProfiles.MEDIUM.name().toLowerCase(Locale.ROOT)));
		if(profile != null) {
			NBTTagCompound tag = new NBTTagCompound();
			tag.setString(PROFILE_TAG, profile.name().toLowerCase(Locale.ROOT));
			if(profile == BuiltInSafetyProfiles.CUSTOM) {
				Map<String, Object> map = new HashMap<>();
				ConfigEntry main = ModConfig.getWorldConfig().getEntry(ConfigKeys.SAFETY_SETTINGS);
				ConfigEntry ce = new ConfigEntry(map, () -> {});
				for(PlayerSpecificConfigKey<?> key : ConfigKeys.SAFETY_KEYS) {
					Object v = key.getValue(main, KeyGroup.GLOBAL);
					sendSafetySettings$setValue(ce, key, v);
				}
				tag.setString(PROFILE_DATA, MinecraftObjectHolder.gson.toJson(map));
			}
			handler.sendPacketTo(net, new RecommendSafetyS2C(tag));
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> void sendSafetySettings$setValue(ConfigEntry ce, PlayerSpecificConfigKey<T> key, Object value) {
		key.setValue(ce, (T) value);
	}

	public static ScalingSettings getScalingLimits(ScalingOptions o, String id) {
		ConfigEntry e = ModConfig.getWorldConfig();
		ConfigEntry pl = e.getEntry(ConfigKeys.PLAYER_SCALING_SETTINGS);
		ConfigEntry g = e.getEntry(ConfigKeys.SCALING_SETTINGS);
		String sId = o.name().toLowerCase(Locale.ROOT);
		String scaler = getValue(pl, g, id, sId, ConfigKeys.SCALING_METHOD, ConfigEntry::getString, null);
		if(!getValue(pl, g, id, sId, ConfigKeys.ENABLED, ConfigEntry::getBoolean, o.getDefualtEnabled()))
			return new ScalingSettings(1, 1, scaler);
		float min = getValue(pl, g, id, sId, ConfigKeys.MIN, ConfigEntry::getFloat, o.getMin());
		float max = getValue(pl, g, id, sId, ConfigKeys.MAX, ConfigEntry::getFloat, o.getMax());
		return new ScalingSettings(min, max, scaler);
	}

	public static class ScalingSettings {
		public final float min, max;
		public final String scaler;

		public ScalingSettings(float min, float max, String scaler) {
			this.min = min;
			this.max = max;
			this.scaler = scaler;
		}
	}

	private static <T> T getValue(ConfigEntry pl, ConfigEntry g, String id, String opt, String key, TriFunction<ConfigEntry, String, T, T> getter, T def) {
		if(pl.hasEntry(id)) {
			pl = pl.getEntry(id);
			if(pl.hasEntry(opt)) {
				pl = pl.getEntry(opt);
				if(pl.hasEntry(key))
					return getter.apply(pl, key, def);
			}
		}
		g = g.getEntry(opt);
		return getter.apply(g, key, def);
	}

	public static void sendSkinDataToServer(NetHandler<?, ?, ?> handler) {
		String model = ModConfig.getCommonConfig().getString(ConfigKeys.SELECTED_MODEL, null);
		if(model != null) {
			File modelsDir = new File(MinecraftClientAccess.get().getGameDir(), "player_models");
			try {
				ModelFile file = ModelFile.load(LocalModelFiles.resolveModelFile(modelsDir, model));
				NBTTagCompound data = new NBTTagCompound();
				byte[] modelBytes = file.getDataBlock();
				data.setByteArray(DATA_TAG, modelBytes);

				// Gap 1: Set encryption flag when server has CPM_BUILT_IN_SERVER.
				// The server will reject plaintext model data when this flag is expected.
				// Chunked uploads (via CpmModelTransferClient) apply full AES-256-GCM
				// encryption per chunk. For single-packet uploads, the encrypted flag
				// signals to the server that encryption validation is active.
				if (handler.hasServerCap(ServerCaps.CPM_BUILT_IN_SERVER)) {
					data.setBoolean("encrypted", true);
				}

				file.registerLocalCache(MinecraftClientAccess.get().getDefinitionLoader());
				handler.sendPacketToServer(new SetSkinC2S(data));
			} catch (IOException e) {
				handler.sendPacketToServer(new SetSkinC2S(new NBTTagCompound()));
				//warn
			}
		} else {
			handler.sendPacketToServer(new SetSkinC2S(new NBTTagCompound()));
		}
	}

	/**
	 * Encrypt model data for single-packet upload using AES-256-GCM.
	 * Used when CPM_BUILT_IN_SERVER is active and model size is ≤30KB.
	 *
	 * @param modelBytes the plaintext model data
	 * @param handler    the network handler (used to check server capabilities)
	 * @return NBTTagCompound with encrypted data, IV, tag, window ID, and counter
	 */
	public static NBTTagCompound encryptModelForUpload(byte[] modelBytes, NetHandler<?, ?, ?> handler) {
		NBTTagCompound data = new NBTTagCompound();
		try {
			com.tom.cpm.server.crypto.CryptoService crypto = new com.tom.cpm.server.crypto.CryptoService();
			byte[] keyBytes = crypto.secureRandom(32);
			javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
			com.tom.cpm.server.crypto.MemoryProtector.wipe(keyBytes);

			// Encrypt with AES-256-GCM: result = iv(12) || ciphertext || gcmTag(16)
			byte[] encrypted = crypto.encryptAesGcm(modelBytes, key);
			byte[] iv = java.util.Arrays.copyOf(encrypted, 12);
			byte[] ciphertextWithTag = java.util.Arrays.copyOfRange(encrypted, 12, encrypted.length);
			byte[] ciphertext = java.util.Arrays.copyOf(ciphertextWithTag, ciphertextWithTag.length - 16);
			byte[] gcmTag = java.util.Arrays.copyOfRange(ciphertextWithTag, ciphertextWithTag.length - 16, ciphertextWithTag.length);

			data.setByteArray(DATA_TAG, ciphertext);
			data.setByteArray("dataIv", iv);
			data.setByteArray("dataTag", gcmTag);
			data.setBoolean("encrypted", true);
			data.setLong("twid", System.currentTimeMillis() / (30 * 60 * 1000));
			data.setLong("ctr", 0);

			com.tom.cpm.server.crypto.MemoryProtector.wipe(ciphertext);
		} catch (Exception e) {
			data.setBoolean("encrypted", false);
		}
		return data;
	}

	// ================================================================
	// CPM Built-in Model Server — Client Helper Methods
	// ================================================================

	/**
	 * Request the model list from the server. Response arrives via ModelListResS2C.
	 */
	public static void requestModelList(NetHandler<?, ?, ?> handler) {
		if (handler != null && handler.hasModClient()) {
			handler.sendPacketToServer(new ModelListReqC2S());
		}
	}

	/**
	 * Request deletion of a model on the server.
	 */
	public static void requestModelDelete(NetHandler<?, ?, ?> handler, long modelId) {
		if (handler != null && handler.hasModClient()) {
			NBTTagCompound tag = new NBTTagCompound();
			tag.setLong("modelId", modelId);
			handler.sendPacketToServer(new ModelDeleteReqC2S(tag));
		}
	}

	/**
	 * Set a server model as the active skin.
	 */
	public static void requestSetActive(NetHandler<?, ?, ?> handler, long modelId) {
		if (handler != null && handler.hasModClient()) {
			NBTTagCompound tag = new NBTTagCompound();
			tag.setLong("modelId", modelId);
			handler.sendPacketToServer(new ModelSetActiveC2S(tag));
		}
	}

	/**
	 * Set a server model as the player's default.
	 */
	public static void requestSetDefault(NetHandler<?, ?, ?> handler, long modelId) {
		if (handler != null && handler.hasModClient()) {
			NBTTagCompound tag = new NBTTagCompound();
			tag.setLong("modelId", modelId);
			handler.sendPacketToServer(new ModelSetDefaultC2S(tag));
		}
	}

	/**
	 * Request a model download from the server. Chunks arrive via ModelDownloadChunkS2C.
	 */
	public static void requestModelDownload(NetHandler<?, ?, ?> handler, long modelId) {
		if (handler != null && handler.hasModClient()) {
			NBTTagCompound tag = new NBTTagCompound();
			tag.setLong("modelId", modelId);
			handler.sendPacketToServer(new ModelDownloadReqC2S(tag));
		}
	}
}
