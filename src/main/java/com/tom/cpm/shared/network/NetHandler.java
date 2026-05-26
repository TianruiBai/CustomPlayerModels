package com.tom.cpm.shared.network;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import com.tom.cpl.nbt.NBTTag;
import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpl.nbt.NBTTagList;
import com.tom.cpl.nbt.NBTTagString;
import com.tom.cpl.text.IText;
import com.tom.cpl.text.LiteralText;
import com.tom.cpl.util.ThrowingConsumer;
import com.tom.cpl.util.TriConsumer;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.shared.animation.AnimationRegistry;
import com.tom.cpm.shared.animation.ServerAnimationState;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.config.ConfigChangeRequest;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.config.PlayerData;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.model.ScaleData;
import com.tom.cpm.shared.network.NetH.ServerNetH;
import com.tom.cpm.shared.network.packet.GestureC2S;
import com.tom.cpm.shared.network.packet.GetSkinS2C;
import com.tom.cpm.shared.network.packet.HelloC2S;
import com.tom.cpm.shared.network.packet.HelloS2C;
import com.tom.cpm.shared.network.packet.PluginMessageC2S;
import com.tom.cpm.shared.network.packet.PluginMessageS2C;
import com.tom.cpm.shared.network.packet.ReceiveEventS2C;
import com.tom.cpm.shared.network.packet.RecommendSafetyS2C;
import com.tom.cpm.shared.network.packet.RequestPlayerC2S;
import com.tom.cpm.shared.network.packet.ScaleInfoS2C;
import com.tom.cpm.shared.network.packet.ServerAnimationS2C;
import com.tom.cpm.shared.network.packet.SetScaleC2S;
import com.tom.cpm.shared.network.packet.SetSkinC2S;
import com.tom.cpm.shared.network.packet.SetSkinS2C;
import com.tom.cpm.shared.network.packet.SubEventC2S;
import com.tom.cpm.shared.network.packet.ModelListReqC2S;
import com.tom.cpm.shared.network.packet.ModelListResS2C;
import com.tom.cpm.shared.network.packet.ModelUploadInitC2S;
import com.tom.cpm.shared.network.packet.ModelUploadInitAckS2C;
import com.tom.cpm.shared.network.packet.ModelDataChunkC2S;
import com.tom.cpm.shared.network.packet.ModelDataChunkAckS2C;
import com.tom.cpm.shared.network.packet.ModelUploadCompleteC2S;
import com.tom.cpm.shared.network.packet.ModelUploadResultS2C;
import com.tom.cpm.shared.network.packet.ModelUploadCancelC2S;
import com.tom.cpm.shared.network.packet.ModelUploadResumeC2S;
import com.tom.cpm.shared.network.packet.ModelUploadResumeAckS2C;
import com.tom.cpm.shared.network.packet.ModelDownloadReqC2S;
import com.tom.cpm.shared.network.packet.ModelDownloadChunkS2C;
import com.tom.cpm.shared.network.packet.ModelSetActiveC2S;
import com.tom.cpm.shared.network.packet.ModelSetDefaultC2S;
import com.tom.cpm.shared.network.packet.ModelDeleteReqC2S;
import com.tom.cpm.shared.network.packet.ModelDeleteResultS2C;
import com.tom.cpm.shared.parts.anim.menu.CommandAction.LegacyCommandActionWriter;
import com.tom.cpm.shared.parts.anim.menu.CommandAction.ServerCommandAction;
import com.tom.cpm.shared.util.Log;
import com.tom.cpm.shared.util.ScalingOptions;

public class NetHandler<RL, P, NET> {
	public static final String GET_SKIN = "get_skin";
	public static final String SET_SKIN = "set_skin";
	public static final String HELLO = "hello";
	public static final String SET_SCALE = "set_scl";
	public static final String RECOMMEND_SAFETY = "rec_sfy";
	public static final String SUBSCRIBE_EVENT = "sub_evt";
	public static final String RECEIVE_EVENT = "rec_evt";
	public static final String GESTURE = "gesture";
	public static final String SERVER_ANIMATION = "srv_anim";
	public static final String PLUGIN = "plugin";
	public static final String REQUEST_PLAYER = "req_pl";
	// CPM Built-in Model Server packet IDs
	public static final String MODEL_LIST = "mdl_lst";
	public static final String MODEL_UPLOAD_INIT = "mdl_upi";
	public static final String MODEL_UPLOAD_CHUNK = "mdl_upc";
	public static final String MODEL_UPLOAD_COMPLETE = "mdl_ucp";
	public static final String MODEL_UPLOAD_CANCEL = "mdl_ucn";
	public static final String MODEL_UPLOAD_RESUME = "mdl_urs";
	public static final String MODEL_DOWNLOAD = "mdl_dwn";
	public static final String MODEL_SET_ACTIVE = "mdl_act";
	public static final String MODEL_SET_DEFAULT = "mdl_def";
	public static final String MODEL_DELETE = "mdl_del";
	public static final String MODEL_TIME_SYNC = "mdl_tms";

	protected Function<P, UUID> getPlayerUUID;

	/** Public accessor for the player UUID function. Used by model packet handlers. */
	@SuppressWarnings("unchecked")
	public <T> UUID resolvePlayerUUID(T player) {
		return getPlayerUUID.apply((P) player);
	}

	// Stage 2 Security: admin check (OP level ≥ 2) and IP capture
	protected Function<P, Boolean> isPlayerAdmin;
	protected Function<P, String> getPlayerIP;

	public void setIsPlayerAdmin(Function<P, Boolean> isPlayerAdmin) {
		this.isPlayerAdmin = isPlayerAdmin;
	}

	public void setGetPlayerIP(Function<P, String> getPlayerIP) {
		this.getPlayerIP = getPlayerIP;
	}

	@SuppressWarnings("unchecked")
	public <T> boolean isAdmin(T player) {
		if (isPlayerAdmin == null) return false;
		return isPlayerAdmin.apply((P) player);
	}

	@SuppressWarnings("unchecked")
	public <T> String getPlayerIp(T player) {
		if (getPlayerIP == null) return "unknown";
		return getPlayerIP.apply((P) player);
	}

	private TriConsumer<NET, RL, byte[]> sendPacket;
	private TriConsumer<P, RL, byte[]> sendToAllTracking;
	protected IntFunction<P> getPlayerById;
	protected BiConsumer<P, IText> sendChat;
	protected BiConsumer<P, Consumer<P>> findTracking;
	protected Function<NET, Executor> executor;
	protected Function<P, Object> playerToLoader;
	protected Supplier<P> getClient;
	protected Function<P, NET> getNet;
	protected Function<NET, P> getPlayer;
	protected BiConsumer<P, IText> kickPlayer;
	protected ToIntFunction<P> getPlayerId;
	protected Consumer<IText> displayText;
	protected Supplier<Collection<? extends P>> getOnlinePlayers;
	protected Map<ScalingOptions, Map<String, Scaler<P>>> scaleSetters = new EnumMap<>(ScalingOptions.class);
	protected BiConsumer<? super P, ServerAnimationState> animStateUpdate;
	protected Predicate<NET> allowPackets = net -> true;

	private List<ConfigChangeRequest<?, ?>> recommendedSettingChanges = new ArrayList<>();
	private EnumSet<ServerCaps> serverCaps = EnumSet.noneOf(ServerCaps.class);
	private boolean scalingWarning;

	// CPM Built-in Model Server integration
	private IModelServerHandler cpmModelPacketHandler;
	private IModelClientHandler cpmModelClientHandler;

	public void setCpmModelPacketHandler(IModelServerHandler handler) {
		this.cpmModelPacketHandler = handler;
	}
	public IModelServerHandler getCpmModelPacketHandler() {
		return cpmModelPacketHandler;
	}
	public void setCpmModelClientHandler(IModelClientHandler handler) {
		this.cpmModelClientHandler = handler;
	}
	public IModelClientHandler getCpmModelClientHandler() {
		return cpmModelClientHandler;
	}

	// Gap 4: Model download synchronization — used by CpmDbResourceLoader
	private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<byte[]>> downloadFutures =
		new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Request a model download from the server and block until complete.
	 * Used by CpmDbResourceLoader to synchronously load a model from the server DB.
	 *
	 * @param modelId the server model ID to download
	 * @return the decrypted model bytes
	 * @throws IOException if download fails or times out
	 */
	public byte[] requestModelDownloadSync(long modelId) throws java.io.IOException {
		java.util.concurrent.CompletableFuture<byte[]> future = new java.util.concurrent.CompletableFuture<>();
		downloadFutures.put(modelId, future);

		// Send the download request
		NBTTagCompound tag = new NBTTagCompound();
		tag.setLong("modelId", modelId);
		sendPacketToServer(new ModelDownloadReqC2S(tag));

		try {
			// Block until the download completes (with 30-second timeout)
			return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			downloadFutures.remove(modelId);
			throw new java.io.IOException("Model download timed out for modelId=" + modelId);
		} catch (Exception e) {
			downloadFutures.remove(modelId);
			throw new java.io.IOException("Model download failed for modelId=" + modelId, e);
		}
	}

	/**
	 * Complete a pending download future with the reassembled model data.
	 * Called by CpmModelTransferClient when all download chunks have been received.
	 */
	public void completeDownload(long modelId, byte[] modelData) {
		java.util.concurrent.CompletableFuture<byte[]> future = downloadFutures.remove(modelId);
		if (future != null) {
			future.complete(modelData);
		}
	}

	/**
	 * Fail a pending download future with an error.
	 */
	public void failDownload(long modelId, Throwable error) {
		java.util.concurrent.CompletableFuture<byte[]> future = downloadFutures.remove(modelId);
		if (future != null) {
			future.completeExceptionally(error);
		}
	}

	protected Map<RL, Supplier<IPacket>> packetS2C = new HashMap<>(), packetC2S = new HashMap<>();
	protected Map<Class<? extends IPacket>, RL> packetLookup = new HashMap<>();
	private BiFunction<String, String, RL> keyFactory;

	public NetHandler(BiFunction<String, String, RL> keyFactory) {
		this.keyFactory = keyFactory;

		register(packetC2S, HELLO, HelloC2S.class, HelloC2S::new);
		register(packetS2C, HELLO, HelloS2C.class, HelloS2C::new);

		register(packetC2S, SET_SKIN, SetSkinC2S.class, SetSkinC2S::new);
		register(packetS2C, SET_SKIN, SetSkinS2C.class, SetSkinS2C::new);

		register(packetS2C, GET_SKIN, GetSkinS2C.class, GetSkinS2C::new);

		register(packetC2S, SET_SCALE, SetScaleC2S.class, SetScaleC2S::new);
		register(packetS2C, SET_SCALE, ScaleInfoS2C.class, ScaleInfoS2C::new);

		register(packetS2C, RECOMMEND_SAFETY, RecommendSafetyS2C.class, RecommendSafetyS2C::new);

		register(packetC2S, SUBSCRIBE_EVENT, SubEventC2S.class, SubEventC2S::new);

		register(packetS2C, RECEIVE_EVENT, ReceiveEventS2C.class, ReceiveEventS2C::new);

		register(packetC2S, GESTURE, GestureC2S.class, GestureC2S::new);

		register(packetS2C, SERVER_ANIMATION, ServerAnimationS2C.class, ServerAnimationS2C::new);

		register(packetC2S, PLUGIN, PluginMessageC2S.class, PluginMessageC2S::new);
		register(packetS2C, PLUGIN, PluginMessageS2C.class, PluginMessageS2C::new);

		register(packetC2S, REQUEST_PLAYER, RequestPlayerC2S.class, RequestPlayerC2S::new);

		// CPM Built-in Model Server packets
		register(packetC2S, MODEL_LIST, ModelListReqC2S.class, ModelListReqC2S::new);
		register(packetS2C, MODEL_LIST, ModelListResS2C.class, ModelListResS2C::new);
		register(packetC2S, MODEL_UPLOAD_INIT, ModelUploadInitC2S.class, ModelUploadInitC2S::new);
		register(packetS2C, MODEL_UPLOAD_INIT, ModelUploadInitAckS2C.class, ModelUploadInitAckS2C::new);
		register(packetC2S, MODEL_UPLOAD_CHUNK, ModelDataChunkC2S.class, ModelDataChunkC2S::new);
		register(packetS2C, MODEL_UPLOAD_CHUNK, ModelDataChunkAckS2C.class, ModelDataChunkAckS2C::new);
		register(packetC2S, MODEL_UPLOAD_COMPLETE, ModelUploadCompleteC2S.class, ModelUploadCompleteC2S::new);
		register(packetS2C, MODEL_UPLOAD_COMPLETE, ModelUploadResultS2C.class, ModelUploadResultS2C::new);
		register(packetC2S, MODEL_UPLOAD_CANCEL, ModelUploadCancelC2S.class, ModelUploadCancelC2S::new);
		register(packetC2S, MODEL_UPLOAD_RESUME, ModelUploadResumeC2S.class, ModelUploadResumeC2S::new);
		register(packetS2C, MODEL_UPLOAD_RESUME, ModelUploadResumeAckS2C.class, ModelUploadResumeAckS2C::new);
		register(packetC2S, MODEL_DOWNLOAD, ModelDownloadReqC2S.class, ModelDownloadReqC2S::new);
		register(packetS2C, MODEL_DOWNLOAD, ModelDownloadChunkS2C.class, ModelDownloadChunkS2C::new);
		register(packetC2S, MODEL_SET_ACTIVE, ModelSetActiveC2S.class, ModelSetActiveC2S::new);
		register(packetC2S, MODEL_SET_DEFAULT, ModelSetDefaultC2S.class, ModelSetDefaultC2S::new);
		register(packetC2S, MODEL_DELETE, ModelDeleteReqC2S.class, ModelDeleteReqC2S::new);
		register(packetS2C, MODEL_DELETE, ModelDeleteResultS2C.class, ModelDeleteResultS2C::new);
	}

	@SuppressWarnings("unchecked")
	protected <PCKT extends IPacket> void register(Map<RL, Supplier<IPacket>> map, String name, Class<PCKT> clazz, Supplier<PCKT> factory) {
		RL key = keyFactory.apply(MinecraftObjectHolder.NETWORK_ID, name);
		map.put(key, (Supplier<IPacket>) factory);
		packetLookup.put(clazz, key);
	}

	@SuppressWarnings("unchecked")
	public void onJoin(P player) {
		NBTTagCompound data = new NBTTagCompound();
		int kickTimer = ModConfig.getWorldConfig().getInt(ConfigKeys.KICK_PLAYERS_WITHOUT_MOD, 0);
		data.setInteger(NetworkUtil.KICK_TIME, kickTimer);
		data.setTag(NetworkUtil.SERVER_CAPS, writeCaps());

		ServerNetH net = getSNetH(player);
		PlayerData pd = newData();
		net.cpm$setEncodedModelData(pd);
		pd.load(getID(player));

		NBTTagCompound scaling = new NBTTagCompound();
		data.setTag(NetworkUtil.SCALING, scaling);
		for(ScalingOptions o : scaleSetters.keySet()) {
			float v = pd.scale.getOrDefault(o, 1F);
			if(v != 1) {
				scaling.setFloat(o.getNetKey(), v);
			}
		}

		if (allowPackets.test((NET) net))
			sendPacketTo0((NET) net, new HelloS2C(data));
	}

	private NBTTag writeCaps() {
		NBTTagCompound data = new NBTTagCompound();
		scaleSetters.keySet().stream().map(ScalingOptions::getCaps).filter(e -> e != null).distinct().forEach(c -> setCap(data, c));
		setCap(data, ServerCaps.MODEL_EVENT_SUBS);
		setCap(data, ServerCaps.GESTURES);
		setCap(data, ServerCaps.PLUGIN_MESSAGES);
		if(ModConfig.getWorldConfig().getBoolean(ConfigKeys.ENABLE_INVIS_GLOW, true))setCap(data, ServerCaps.INVIS_GLOW);
		setCap(data, ServerCaps.NAMED_PARAMETERS);
		// Announce built-in model server caps if the server is initialized
		if (cpmModelPacketHandler != null) {
			setCap(data, ServerCaps.CPM_BUILT_IN_SERVER);
			setCap(data, ServerCaps.CPM_CHUNKED_TRANSFER);
			setCap(data, ServerCaps.CPM_TIME_BOUND_KEYS);
		}
		return data;
	}

	private void setCap(NBTTagCompound tag, ServerCaps caps) {
		tag.setBoolean(caps.name().toLowerCase(Locale.ROOT), true);
	}

	protected PlayerData newData() {
		return new PlayerData();
	}

	public void receiveServer(RL key, InputStream data, ServerNetH net) {
		processPacket(packetC2S, key, data, net);
	}

	public void receiveClient(RL key, InputStream data, NetH net) {
		processPacket(packetS2C, key, data, net);
	}

	private void processPacket(Map<RL, Supplier<IPacket>> map, RL key, InputStream data, NetH net) {
		try {
			Supplier<IPacket> factory = map.get(key);
			if(factory != null) {
				IPacket pckt = factory.get();
				IOHelper h = new IOHelper(data);
				pckt.read(h);
				pckt.handleRaw(this, net);
			}
		} catch (Throwable e) {
			Log.error("Exception while processing cpm packet: " + key, e);
		}
	}

	public void handleServerCaps(NBTTagCompound tag) {
		serverCaps.clear();
		for(ServerCaps c : ServerCaps.VALUES) {
			if(tag.getBoolean(c.name().toLowerCase(Locale.ROOT))) {
				serverCaps.add(c);
			}
		}
	}

	public void sendSkinData() {
		if(hasModClient())
			NetworkUtil.sendSkinDataToServer(this);
	}

	public void setSkin(P pl, String skin, boolean force, boolean save) {
		ServerNetH h = getSNetH(pl);
		PlayerData pd = h.cpm$getEncodedModelData();
		pd.setModel(skin, force, save);
		if(skin == null) {
			sendPacketTo(h, new GetSkinS2C());
		}
		sendPacketToTracking(pl, NetworkUtil.writeSkinData(this, pd, pl));
		pd.save(getID(pl));
	}

	public void setSkin(P pl, byte[] skin, boolean force) {
		setSkin(pl, skin, force, false);
	}

	public void setSkin(P pl, byte[] skin, boolean force, boolean save) {
		PlayerData pd = getSNetH(pl).cpm$getEncodedModelData();
		pd.setModel(skin, force, save);
		sendPacketToTracking(pl, NetworkUtil.writeSkinData(this, pd, pl));
		pd.save(getID(pl));
	}

	public void setScale(ScaleData scl) {
		if (hasModClient()) {
			if(scl == null)scl = ScaleData.NULL;
			NBTTagCompound nbt = new NBTTagCompound();
			for(Entry<ScalingOptions, Float> e : scl.getScaling().entrySet()) {
				if(serverCaps.contains(e.getKey().getCaps())) {
					nbt.setFloat(e.getKey().getNetKey(), e.getValue());
				}
			}
			sendPacketToServer(new SetScaleC2S(nbt));
		}
	}

	public void onRespawn(P pl) {
		PlayerData pd = getSNetH(pl).cpm$getEncodedModelData();
		pd.rescale(this, pl);
	}

	public void tick() {
		int kickTimer = ModConfig.getWorldConfig().getInt(ConfigKeys.KICK_PLAYERS_WITHOUT_MOD, 0);
		for(P p : new ArrayList<>(getOnlinePlayers.get())) {
			ServerNetH net = getSNetH(p);
			PlayerData dt = net.cpm$getEncodedModelData();
			if(dt != null) {
				if(!net.cpm$hasMod()) {
					dt.ticksSinceLogin++;
					if(kickTimer > 0 && dt.ticksSinceLogin > kickTimer) {
						kickPlayer.accept(p, new LiteralText(ModConfig.getWorldConfig().getString(ConfigKeys.KICK_MESSAGE, ConfigKeys.DEFAULT_KICK_MESSAGE)));
					}
				}
				NBTTagCompound evt = new NBTTagCompound();
				NBTTagCompound evtS = new NBTTagCompound();
				updatePlayer(p, dt.state);
				for (ModelEventType type : ModelEventType.SYNC_TYPES) {
					if(dt.eventSubs.contains(type)) {
						type.write(dt.state, evt);
					}
					if(dt.selfSubs.contains(type)) {
						type.write(dt.state, evtS);
					}
				}
				if(evt.tagCount() > 0) {
					sendPacketToTracking(p, new ReceiveEventS2C(getPlayerId.applyAsInt(p), evt));
				}
				if(evtS.tagCount() > 0) {
					evtS.setBoolean(NetworkUtil.SELF_EVENT, true);
					sendPacketTo(net, new ReceiveEventS2C(getPlayerId.applyAsInt(p), evtS));
				}
			}
		}
	}

	public void updatePlayer(P player, ServerAnimationState state) {
		animStateUpdate.accept(player, state);
	}

	public boolean hasModClient() {
		NET n = getClientNet();
		return n instanceof NetH && ((NetH) n).cpm$hasMod();
	}

	public void onLogOut() {
		recommendedSettingChanges.clear();
		serverCaps.clear();
		scalingWarning = false;
	}

	public void sendEventSubs(ModelDefinition def) {
		if(serverCaps.contains(ServerCaps.MODEL_EVENT_SUBS)) {
			NBTTagCompound tag = new NBTTagCompound();
			List<ModelEventType> events = def.getAnimations().getAnimations().stream().flatMap(e -> e.onPoses.stream()).
					filter(e -> e instanceof VanillaPose).map(ModelEventType::getType).filter(e -> e != null).
					distinct().collect(Collectors.toList());
			{
				NBTTagList list = new NBTTagList();
				tag.setTag(NetworkUtil.EVENT_LIST, list);
				events.stream().map(ModelEventType::getName).map(NBTTagString::new).forEach(list::appendTag);
			}

			{
				NBTTagList list = new NBTTagList();
				tag.setTag(NetworkUtil.SELF_EVENT_LIST, list);
				events.stream().filter(MinecraftClientAccess.get()::requiresSelfEventForAnimation).
				map(ModelEventType::getName).map(NBTTagString::new).forEach(list::appendTag);
			}

			AnimationRegistry reg = def.getAnimations();
			NBTTagList list = new NBTTagList();
			NBTTagList na = new NBTTagList();
			if (serverCaps.contains(ServerCaps.NAMED_PARAMETERS))tag.setTag(NetworkUtil.NAMED_PARAMETERS, na);
			else tag.setTag(NetworkUtil.ANIMATIONS, list);
			reg.getCommandActionsMap().forEach((k, v) -> {
				if (v instanceof LegacyCommandActionWriter) {
					LegacyCommandActionWriter w = (LegacyCommandActionWriter) v;
					NBTTagCompound t = new NBTTagCompound();
					w.writeLegacy(t);
					list.appendTag(t);
				}
				NBTTagCompound t = new NBTTagCompound();
				v.write(t);
				t.setString("name", k);
				t.setString("type", v.getType().name());
				na.appendTag(t);
			});

			sendPacketToServer(new SubEventC2S(tag));
		}
	}

	public boolean hasServerCap(ServerCaps cap) {
		return hasModClient() && serverCaps.contains(cap);
	}

	public void onJump(P p) {
		onEvent(p, ModelEventType.JUMPING);
	}

	public void onEvent(P p, ModelEventType event) {
		ServerNetH net = getSNetH(p);
		PlayerData dt = net.cpm$getEncodedModelData();
		if(!event.autoSync() && dt != null && dt.eventSubs.contains(event)) {
			NBTTagCompound evt = new NBTTagCompound();
			event.write(dt.state, evt);
			sendPacketToTracking(p, new ReceiveEventS2C(getPlayerId.applyAsInt(p), evt));
		}
	}

	public void playAnimation(P p, String animation, int value) {
		sendPacketTo(getSNetH(p), new ServerAnimationS2C(animation, value));
	}

	public boolean sendPluginMessage(String id, NBTTagCompound msg, int flags) {
		if(hasModClient() && serverCaps.contains(ServerCaps.PLUGIN_MESSAGES)) {
			sendPacketToServer(new PluginMessageC2S(id, msg, flags));
			return true;
		}
		return false;
	}

	public boolean enableInvisGlow() {
		return hasServerCap(ServerCaps.INVIS_GLOW);
	}

	public int getAnimationPlaying(P player, String animation) {
		PlayerData pd = getSNetH(player).cpm$getEncodedModelData();
		ServerCommandAction id = pd.animNames.get(animation);
		if(id == null)return -1;
		return id.getValue();
	}

	public void requestPlayerState(UUID other) {
		if (!hasModClient())return;
		sendPacketToServer(new RequestPlayerC2S(other, false));
	}

	public void requestPlayerData(UUID other) {
		if (!hasModClient())return;
		sendPacketToServer(new RequestPlayerC2S(other, true));
	}

	public String getID(P pl) {
		return getPlayerUUID.apply(pl).toString();
	}

	public NET getClientNet() {
		P p = getClient.get();
		return p != null ? getNet.apply(p) : null;
	}

	public ServerNetH getSNetH(P player) {
		return (ServerNetH) getNet.apply(player);
	}

	public void sendPlayerData(P target, P to) {
		NetworkUtil.sendPlayerData(this, target, to);
	}

	@SuppressWarnings("unchecked")
	public void execute(NetH net, Runnable task) {
		executor.apply((NET) net).execute(task);
	}

	public List<ConfigChangeRequest<?, ?>> getRecommendedSettingChanges() {
		return recommendedSettingChanges;
	}

	// Time synchronization for time-bound encryption
	private long serverTimeOffsetSeconds;
	private long serverTimeWindowId;

	public void setServerTimeOffset(long offsetSeconds, long timeWindowId) {
		this.serverTimeOffsetSeconds = offsetSeconds;
		this.serverTimeWindowId = timeWindowId;
	}

	public long getServerTimeOffsetSeconds() { return serverTimeOffsetSeconds; }
	public long getServerTimeWindowId() { return serverTimeWindowId; }

	public void setGetPlayerUUID(Function<P, UUID> getPlayerUUID) {
		this.getPlayerUUID = getPlayerUUID;
	}

	public <PB> void setSendPacketDirect(Function<byte[], PB> wrapper, TriConsumer<NET, RL, PB> sendPacket, TriConsumer<P, RL, PB> sendToAllTracking) {
		this.sendPacket = (a, b, c) -> sendPacket.accept(a, b, wrapper.apply(c));
		this.sendToAllTracking = (a, b, c) -> sendToAllTracking.accept(a, b, wrapper.apply(c));
	}

	public void setSendPacketDirect(TriConsumer<NET, RL, byte[]> sendPacket, TriConsumer<P, RL, byte[]> sendToAllTracking) {
		this.sendPacket = sendPacket;
		this.sendToAllTracking = sendToAllTracking;
	}

	public <PB> void setSendPacketClient(Function<byte[], PB> wrapper, TriConsumer<NET, RL, PB> sendPacket) {
		this.sendPacket = (a, b, c) -> sendPacket.accept(a, b, wrapper.apply(c));
	}

	public void setSendPacketClient(TriConsumer<NET, RL, byte[]> sendPacket) {
		this.sendPacket = sendPacket;
	}

	private void sendPacketServer(P to, RL pck, byte[] data) {
		NET n = getNet.apply(to);
		if(n instanceof ServerNetH && ((ServerNetH)n).cpm$hasMod()) {
			sendPacket.accept(n, pck, data);
		}
	}

	public <PB, C> void setSendPacketServer(Function<byte[], PB> wrapper, TriConsumer<NET, RL, PB> sendPacket, Function<P, Collection<C>> forEachTracking, Function<C, P> toPlayer) {
		this.sendPacket = (a, b, c) -> sendPacket.accept(a, b, wrapper.apply(c));
		this.sendToAllTracking = (p, rl, d) -> {
			for (C t : forEachTracking.apply(p)) {
				sendPacketServer(toPlayer.apply(t), rl, d);
			}
			sendPacketServer(p, rl, d);
		};
	}

	public void setFindTracking(BiConsumer<P, Consumer<P>> findTracking) {
		this.findTracking = findTracking;
	}

	public void setSendChat(BiConsumer<P, IText> sendChat) {
		this.sendChat = sendChat;
	}

	public void setExecutor(Supplier<Executor> executor) {
		this.executor = v -> executor.get();
	}

	public void setExecutor(Function<NET, Executor> executor) {
		this.executor = executor;
	}

	public void setPlayerToLoader(Function<P, Object> playerToloader) {
		this.playerToLoader = playerToloader;
	}

	public void setGetClient(Supplier<P> getClient) {
		this.getClient = getClient;
	}

	public void setGetNet(Function<P, NET> getNet) {
		this.getNet = getNet;
	}

	public void setGetPlayer(Function<NET, P> getPlayer) {
		this.getPlayer = getPlayer;
	}

	public void setKickPlayer(BiConsumer<P, IText> kickPlayer) {
		this.kickPlayer = kickPlayer;
	}

	public void setGetPlayerById(IntFunction<P> getPlayerById) {
		this.getPlayerById = getPlayerById;
	}

	public void setGetPlayerId(ToIntFunction<P> getPlayerId) {
		this.getPlayerId = getPlayerId;
	}

	public void setDisplayText(Consumer<IText> displayText) {
		this.displayText = displayText;
	}

	public void setGetOnlinePlayers(Supplier<Collection<? extends P>> getOnlinePlayers) {
		this.getOnlinePlayers = getOnlinePlayers;
	}

	public void setGetPlayerAnimGetters(BiConsumer<? super P, ServerAnimationState> animStateUpdate) {
		this.animStateUpdate = animStateUpdate;
	}

	public <E extends Throwable> void registerOut(ThrowingConsumer<RL, E> reg) throws E {
		for (RL e : packetS2C.keySet()) {
			reg.accept(e);
		}
	}

	public <E extends Throwable> void registerIn(ThrowingConsumer<RL, E> reg) throws E {
		for (RL e : packetC2S.keySet()) {
			reg.accept(e);
		}
	}

	public <K> void addScaler(ScalerInterface<P, K> intf) {
		for (ScalingOptions opt : ScalingOptions.VALUES) {
			K key;
			try {
				key = intf.toKey(opt);
			} catch (Throwable e) {
				Log.warn("Failed to create scaler key for " + opt.name().toLowerCase(Locale.ROOT) + ". Make sure your scaling supported mods are up to date!", e);
				continue;
			}
			if(key != null)
				scaleSetters.computeIfAbsent(opt, __ -> new LinkedHashMap<>()).put(intf.getMethodName(), (p, v) -> intf.setScale(key, p, v));
		}
	}

	public static interface ScalerInterface<P, K> {
		public static final String ATTRIBUTE = "attribute";
		public static final String PEHKUI = "pehkui";

		void setScale(K key, P player, float value);
		K toKey(ScalingOptions opt);
		String getMethodName();
	}

	@FunctionalInterface
	public static interface Scaler<P> {
		void applyScaling(P player, float value);
	}

	public boolean isSupported(ScalingOptions o) {
		return scaleSetters.containsKey(o);
	}

	@SuppressWarnings("unchecked")
	public P getPlayer(ServerNetH net) {
		return getPlayer.apply((NET) net);
	}

	public void forEachTracking(P pl, Consumer<P> cons) {
		findTracking.accept(pl, cons);
	}

	@SuppressWarnings("unchecked")
	public void sendPacketTo(ServerNetH net, IPacket packet) {
		if(!net.cpm$hasMod())return;
		sendPacketTo0((NET) net, packet);
	}

	public void sendPacketToServer(IPacket packet) {
		if(!hasModClient())return;
		sendPacketTo0(getClientNet(), packet);
	}

	private void sendPacketTo0(NET net, IPacket packet) {
		RL id = packetLookup.get(packet.getClass());
		if(id == null)return;
		byte[] data;
		try {
			data = packet2byte(packet);
		} catch (IOException e) {
			return;
		}
		sendPacket.accept(net, id, data);
	}

	public void sendPacketToTracking(P player, IPacket packet) {
		RL id = packetLookup.get(packet.getClass());
		if(id == null)return;
		byte[] data;
		try {
			data = packet2byte(packet);
		} catch (IOException e) {
			return;
		}
		sendToAllTracking.accept(player, id, data);
	}

	private byte[] packet2byte(IPacket pckt) throws IOException {
		IOHelper h = new IOHelper();
		pckt.write(h);
		return h.toBytes();
	}

	public int getPlayerId(P target) {
		return getPlayerId.applyAsInt(target);
	}

	public void sendChat(P player, IText chatMsg) {
		sendChat.accept(player, chatMsg);
	}

	public Map<ScalingOptions, Map<String, Scaler<P>>> getScaleSetters() {
		return scaleSetters;
	}

	public void displayText(IText text) {
		displayText.accept(text);
	}

	public P getPlayerById(int entityId) {
		return getPlayerById.apply(entityId);
	}

	public P getPlayerByUUID(UUID uuid) {
		return getOnlinePlayers.get().stream().filter(p -> uuid.equals(getPlayerUUID.apply(p))).findFirst().orElse(null);
	}

	public List<P> getOnlinePlayers() {
		return new ArrayList<>(getOnlinePlayers.get());
	}

	public Object getLoaderId(P player) {
		return playerToLoader.apply(player);
	}

	public void setScalingWarning() {
		this.scalingWarning = true;
	}

	public boolean hasScalingWarning() {
		return scalingWarning;
	}

	public void setAllowPackets(Predicate<NET> allowPackets) {
		this.allowPackets = allowPackets;
	}

	public RL getPacketKey(Class<? extends IPacket> packetClass) {
		return packetLookup.get(packetClass);
	}
}
