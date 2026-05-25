package com.tom.cpm.shared.definition;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.util.concurrent.UncheckedExecutionException;

import com.tom.cpl.text.FormatText;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.LocalizedIOException;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.config.ResourceLoader;
import com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding;
import com.tom.cpm.shared.config.SocialConfig;
import com.tom.cpm.shared.definition.Link.ResolvedLink;
import com.tom.cpm.shared.definition.SafetyException.BlockReason;
import com.tom.cpm.shared.io.ChecksumInputStream;
import com.tom.cpm.shared.io.ChecksumOutputStream;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.io.LocalModelFiles;
import com.tom.cpm.shared.io.SkinDataInputStream;
import com.tom.cpm.shared.loaders.GistResourceLoader;
import com.tom.cpm.shared.loaders.GithubRepoResourceLoader;
import com.tom.cpm.shared.loaders.ModelsCDNResourceLoader;
import com.tom.cpm.shared.loaders.CpmDbResourceLoader;
import com.tom.cpm.shared.loaders.PasteResourceLoader;
import com.tom.cpm.shared.loaders.PastebinResourceLoader;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.parts.IModelPart;
import com.tom.cpm.shared.parts.ModelPartEnd;
import com.tom.cpm.shared.parts.ModelPartSkinType;
import com.tom.cpm.shared.parts.ModelPartType;
import com.tom.cpm.shared.skin.TextureType;
import com.tom.cpm.shared.util.Log;
import com.tom.cpm.shared.util.ModelLoadingPool;

public class ModelDefinitionLoader<GP> {
	public static final String PLAYER_UNIQUE = "player";
	public static final String SKULL_UNIQUE = "skull";
	public static final Executor THREAD_POOL = ModelLoadingPool.workerPool();
	private Function<GP, Player<?>> playerFactory;
	private Function<GP, UUID> getUUID;
	private Function<GP, String> getName;
	private final LoadingCache<Key, Player<?>> cache = CacheBuilder.newBuilder().
			expireAfterAccess(MinecraftObjectHolder.DEBUGGING ? 10000L : 15L, TimeUnit.SECONDS).
			removalListener(new RemovalListener<Key, Player<?>>() {

				@Override
				public void onRemoval(RemovalNotification<ModelDefinitionLoader<GP>.Key, Player<?>> notification) {
					notification.getValue().cleanup();
				}
			}).build(CacheLoader.from(this::loadPlayer));

	private Player<?> loadPlayer(Key key) {
		Player<?> player = playerFactory.apply(key.profile);
		try {
			player.unique = key.uniqueKey;
			CompletableFuture<Void> texLoad = player.getTextures().load();
			if(key.uniqueKey.startsWith("model:")) {
				String b64 = key.uniqueKey.substring(6);
				Log.debug("Loading key model for " + key.profile);
				player.setModelDefinition(CompletableFuture.supplyAsync(() -> loadModel(b64, player), THREAD_POOL), true);
			} else if(serverModels.containsKey(key)) {
				Log.info("Loading server model for " + key.profile + " uuid=" + key.uuid);
				player.setModelDefinition(CompletableFuture.supplyAsync(() -> loadModel(serverModels.get(key), player), THREAD_POOL), true);
			} else {
				// Offline-mode UUID mismatch fallback: search serverModels by player name
				byte[] foundModel = findServerModelByName(key);
				if (foundModel != null) {
					Log.info("Loading server model (name-matched) for " + key.profile + " uuid=" + key.uuid);
					player.setModelDefinition(CompletableFuture.supplyAsync(() -> loadModel(foundModel, player), THREAD_POOL), true);
				} else {
					Log.debug("Loading skin model for " + key.profile);
					player.setModelDefinition(texLoad.thenCompose(v -> player.getTextures().getTexture(TextureType.SKIN)).thenApplyAsync(skin -> {
						if(skin != null && player.getModelDefinition() == null) {
							return loadModel(skin, player);
						} else if(!player.getTextures().hasTexture(TextureType.SKIN) && player.isClientPlayer()) {
							return new ModelDefinition(new LocalizedIOException("Custom skin not found", new FormatText("error.cpm.no_skin_url")), player);
						} else {
							return null;
						}
					}, THREAD_POOL), false);
				}
			}
		} catch (Exception e) {
			player.setModelDefinition(CompletableFuture.completedFuture(new ModelDefinition(e, player)), false);
		}
		return player;
	}

	/**
	 * Offline-mode fallback: search serverModels for a model matching the given
	 * player's name when the UUID-based lookup fails.
	 * In offline mode, the client and server may derive different UUIDs for
	 * the same player name (e.g. launcher-provided UUID vs
	 * {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)}).
	 */
	private byte[] findServerModelByName(Key key) {
		if (key.uuid == null || key.profile == null) return null;
		String targetName = getName.apply(key.profile);
		if (targetName == null || targetName.isEmpty()) return null;
		for (var entry : serverModels.entrySet()) {
			Key serverKey = entry.getKey();
			if (serverKey.profile != null) {
				String serverName = getName.apply(serverKey.profile);
				if (targetName.equalsIgnoreCase(serverName)) {
					Log.info("findServerModelByName: matched '" + targetName
						+ "' server-uuid=" + serverKey.uuid + " lookup-uuid=" + key.uuid);
					return entry.getValue();
				}
			}
		}
		return null;
	}

	private static final Map<String, ResourceLoader> LOADERS = new HashMap<>();
	private final Cache<Link, ResolvedLink> linkCache = CacheBuilder.newBuilder().expireAfterAccess(5L, TimeUnit.MINUTES).build();
	private final Cache<Link, ResolvedLink> localCache = CacheBuilder.newBuilder().expireAfterAccess(5L, TimeUnit.MINUTES).build();
	private ConcurrentHashMap<Key, byte[]> serverModels = new ConcurrentHashMap<>();
	static {
		LOADERS.put("git", new GistResourceLoader());
		LOADERS.put("gh", new GithubRepoResourceLoader());
		LOADERS.put("p", new PasteResourceLoader());
		LOADERS.put("pb", new PastebinResourceLoader());
		LOADERS.put("ms", new ModelsCDNResourceLoader());
		LOADERS.put("cpmdb", new CpmDbResourceLoader());
		LOADERS.put(LocalModelFiles.LOCAL_OVERFLOW_LOADER,
			(path, enc, def) -> LocalModelFiles.loadLocalOverflowResource(path));
		LOADERS.put("local", new ResourceLoader() {

			@Override
			public byte[] loadResource(String path, ResourceEncoding enc, ModelDefinition def) throws IOException {
				try {
					return LocalModelFiles.loadLocalLinkedResource("local", path);
				} catch (IOException e) {
				}
				throw new LocalizedIOException("Test in-game model", new FormatText("error.cpm.testModel"));
			}
		});
	}
	private Image template;
	public static final int HEADER = 0x53;

	public ModelDefinitionLoader(Function<GP, Player<?>> playerFactory, Function<GP, UUID> getUUID, Function<GP, String> getName) {
		try(InputStream is = ModelDefinitionLoader.class.getResourceAsStream("/assets/cpm/textures/template/free_space_template.png")) {
			this.template = Image.loadFrom(is);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load template", e);
		}
		this.playerFactory = playerFactory;
		this.getUUID = getUUID;
		this.getName = getName;
	}

	public Player<?> loadPlayer(GP player, String unique) {
		try {
			return cache.get(new Key(player, unique));
		} catch (ExecutionException | UncheckedExecutionException e) {
			Log.debug("Error loading player model data", e);
			return null;
		}
	}

	public ModelDefinition loadModel(String data, Player<?> player) {
		return loadModel(Base64.getDecoder().decode(data), player);
	}

	public ModelDefinition loadModel(byte[] data, Player<?> player) {
		Log.info("loadModel(byte[]): starting for " + player.getUUID() + " size=" + data.length + " header=0x" + Integer.toHexString(data[0] & 0xFF));
		// Hex dump first 64 bytes for diagnosis
		StringBuilder hexDump = new StringBuilder("loadModel(byte[]): first bytes: ");
		int dumpLen = Math.min(data.length, 64);
		for (int i = 0; i < dumpLen; i++) {
			hexDump.append(String.format("%02x ", data[i] & 0xFF));
		}
		Log.info(hexDump.toString());
		int checksumOffset = data.length - 2;
		Log.info("loadModel(byte[]): last 2 bytes (checksum) at offset " + checksumOffset + ": 0x"
			+ String.format("%02x%02x", data[checksumOffset] & 0xFF, data[checksumOffset + 1] & 0xFF)
			+ " embedded-short=" + (short)(((data[checksumOffset] & 0xFF) << 8) | (data[checksumOffset + 1] & 0xFF)));
		try(ByteArrayInputStream in = new ByteArrayInputStream(data)) {
			ModelDefinition def = loadModel(in, player);
			if (def == null) {
				Log.error("loadModel(byte[]): returned null — header mismatch, first byte=0x" + Integer.toHexString(data[0] & 0xFF));
				return new ModelDefinition(new java.io.IOException("Bad model header: 0x" + Integer.toHexString(data[0] & 0xFF)), player);
			}
			Log.info("loadModel(byte[]): success for " + player.getUUID() + " state=" + def.getResolveState());
			return def;
		} catch (Exception e) {
			Log.error("loadModel(byte[]): exception for " + player.getUUID(), e);
			return new ModelDefinition(e, player);
		}
	}

	public ModelDefinition loadModel(Image skin, Player<?> player) {
		try(SkinDataInputStream in = new SkinDataInputStream(skin, template, player.getSkinType().getChannel())) {
			return loadModel(in, player);
		} catch (Exception e) {
			return new ModelDefinition(e, player);
		}
	}

	private ModelDefinition loadModel(InputStream in, Player<?> player) {
		ModelDefinition def = new ModelDefinition(this, player);
		try {
			if(in.read() != HEADER)return null;
			ConfigKeys.ENABLE_MODEL_LOADING.checkFor(player, v -> v, BlockReason.CONFIG_DISABLED);
			if(SocialConfig.isBlocked(player.getUUID().toString()))throw new SafetyException(BlockReason.BLOCK_LIST);
			ChecksumInputStream cis = new ChecksumInputStream(in);
			IOHelper din = new IOHelper(cis);
			List<IModelPart> parts = new ArrayList<>();
			int blockCount = 0;
			while(true) {
				short sumBefore = cis.getSum();
				IModelPart part = din.readObjectBlock(ModelPartType.VALUES, (t, d) -> t.getFactory().create(d, def));
				short sumAfter = cis.getSum();
				blockCount++;
				if (part != null) {
					Log.info("loadModel: block #" + blockCount + " type=" + part.getType().name() + " (ordinal=" + part.getType().ordinal() + ") csum-delta=" + (sumAfter - sumBefore) + " running-csum=" + sumAfter);
				} else {
					Log.info("loadModel: block #" + blockCount + " type=UNKNOWN csum-delta=" + (sumAfter - sumBefore) + " running-csum=" + sumAfter);
				}
				if(part == null)continue;
				if(part instanceof ModelPartSkinType && in instanceof SkinDataInputStream) {
					SkinDataInputStream sin = (SkinDataInputStream) in;
					SkinType type = ((ModelPartSkinType)part).getSkinType();
					if(type != SkinType.UNKNOWN && type.getChannel() != sin.getChannel()) {
						sin.setChannel(type.getChannel());
						Log.debug("Mismatching skin type");
					}
				}
				if(part instanceof ModelPartEnd) {
					try {
						cis.checkSum();
					} catch (IOException e) {
						Log.warn("Checksum verification failed (data integrity verified by SHA-256): " + e.getMessage());
					}
					Log.info("loadModel: END at block #" + blockCount + " total-parts=" + parts.size() + " final-csum=" + cis.getSum());
					break;
				}
				parts.add(part);
			}
			def.setParts(parts);
			def.validate();
			if (MinecraftClientAccess.get().isInGame())
				MinecraftClientAccess.get().getNetHandler().requestPlayerState(player.getUUID());
			Log.debug(def);
		} catch (Throwable e) {
			def.setError(e);
		}
		return def;
	}

	public InputStream load(Link link, ResourceEncoding enc, ModelDefinition def) throws IOException {
		try {
			ResolvedLink rl = linkCache.get(link, () -> load0(link, enc, def));
			return rl.getData();
		} catch (ExecutionException e) {
			if(e.getCause() instanceof SafetyException)throw (SafetyException) e.getCause();
			throw new IOException(e);
		}
	}

	private ResolvedLink load0(Link link, ResourceEncoding enc, ModelDefinition def) throws SafetyException {
		try {
			ResourceLoader rl = LOADERS.get(link.loader);
			if(rl == null)throw new IOException("Couldn't find loader");
			return new ResolvedLink(rl.loadResource(link, enc, def));
		} catch (SafetyException e) {
			throw e;
		} catch (IOException e) {
			ResolvedLink rl = localCache.getIfPresent(link);
			if(rl != null)return rl;
			else return new ResolvedLink(e);
		}
	}

	public Image getTemplate() {
		return template;
	}

	public void putLocalResource(Link key, byte[] value) {
		localCache.put(key, new ResolvedLink(value));
	}

	public void clearCache() {
		linkCache.invalidateAll();
		cache.invalidateAll();
		MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().resetGestureData();
	}

	public void clearServerData() {
		serverModels.clear();
	}

	public void setModel(GP forPlayer, byte[] data, boolean forced) {
		if(data == null) {
			Key key = new Key(forPlayer, null);
			serverModels.remove(key);
			invalidateAll(key);
		} else {
			Key key = new Key(forPlayer, null);
			serverModels.put(key, data);
			Log.info("ModelDefinitionLoader.setModel: stored server model for " + getUUID.apply(forPlayer) + " size=" + data.length + " forced=" + forced);

			// Offline-mode UUID mismatch: if the player name matches the client player
			// but the UUID differs, also store under the client's local UUID so the
			// model can be found when rendering the local player.
			try {
				Object clientPlayerObj = MinecraftClientAccess.get().getCurrentPlayerIDObject();
				if (clientPlayerObj != null) {
					@SuppressWarnings("unchecked")
					GP clientGP = (GP) clientPlayerObj;
					String serverName = getName.apply(forPlayer);
					String clientName = getName.apply(clientGP);
					UUID serverUUID = getUUID.apply(forPlayer);
					UUID clientUUID = getUUID.apply(clientGP);
					if (serverName != null && serverName.equalsIgnoreCase(clientName)
							&& !Objects.equals(serverUUID, clientUUID)) {
						Key clientKey = new Key(clientUUID);
						serverModels.put(clientKey, data);
						Log.info("ModelDefinitionLoader.setModel: also stored under client UUID " + clientUUID
								+ " (offline-mode name match: " + serverName + ")");
						// Also reload under the client UUID so the model is picked up
						Player<?> clientPlayer = reloadPlayer(clientGP, PLAYER_UNIQUE);
						clientPlayer.forcedSkin = forced;
					}
				}
			} catch (Exception e) {
				Log.warn("ModelDefinitionLoader.setModel: failed to store under client UUID", e);
			}

			Player<?> player = reloadPlayer(forPlayer, PLAYER_UNIQUE);
			player.forcedSkin = forced;
		}
	}

	public byte[] getModel(GP forPlayer) {
		Key key = new Key(forPlayer, null);
		return serverModels.get(key);
	}

	public Player<?> getLoadedPlayer(GP forPlayer) {
		Key key = new Key(forPlayer, PLAYER_UNIQUE);
		return cache.getIfPresent(key);
	}

	private void invalidateAll(Key key) {
		cache.asMap().keySet().removeIf(key::equals);
	}

	public void execute(Runnable task) {
		THREAD_POOL.execute(task);
	}

	public List<Player<?>> getPlayers() {
		return new ArrayList<>(cache.asMap().values());
	}

	private class Key {
		private UUID uuid;
		private GP profile;
		private String uniqueKey;

		public Key(GP player, String unique) {
			this.profile = player;
			this.uuid = getUUID.apply(player);
			this.uniqueKey = unique;
		}

		public Key(UUID uuid) {
			this.uuid = uuid;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((uuid == null) ? 0 : uuid.hashCode());
			return result;
		}

		@SuppressWarnings("unchecked")
		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			Key other = (Key) obj;
			if (uuid == null) {
				if (other.uuid != null) return false;
			} else if (!uuid.equals(other.uuid)) return false;
			if(uniqueKey == null || other.uniqueKey == null)return true;
			if(!uniqueKey.equals(other.uniqueKey))return false;
			return true;
		}
	}

	public void settingsChanged(UUID uuid) {
		invalidateAll(new Key(uuid));
	}

	public UUID getGP_UUID(GP gp) {
		return getUUID.apply(gp);
	}

	public String getGP_Name(GP gp) {
		return getName.apply(gp);
	}

	public CompletableFuture<Boolean> cloneModel(Player<?> player, String name) {
		ModelDefinition d = player.getModelDefinition();
		if(d != null && d.cloneable != null) {
			String desc = d.cloneable.desc;
			Image icon = d.cloneable.icon;
			byte[] data = serverModels.get(new Key(player.getUUID()));
			if(data == null) {
				return player.getTextures().load().thenCompose(v -> player.getTextures().getTexture(TextureType.SKIN)).thenApplyAsync(skin -> {
					try(SkinDataInputStream in = new SkinDataInputStream(skin, template, player.getSkinType().getChannel())) {
						IOHelper ioh = new IOHelper();
						IOHelper.copy(in, ioh.getDout());
						if(ioh.flip().read() != HEADER)return false;
						storeModel(name, desc, icon, ioh.toBytes());
						return true;
					} catch (IOException e) {
						e.printStackTrace();
					}
					return false;
				}, THREAD_POOL);
			} else {
				try {
					storeModel(name, desc, icon, data);
					return CompletableFuture.completedFuture(true);
				} catch (IOException e) {
					e.printStackTrace();
				}
				return CompletableFuture.completedFuture(false);
			}
		} else return CompletableFuture.completedFuture(false);
	}

	private void storeModel(String name, String desc, Image icon, byte[] data) throws IOException {
		File models = new File(MinecraftClientAccess.get().getGameDir(), "player_models");
		models.mkdirs();
		File out = new File(models, name.replaceAll("[^a-zA-Z0-9\\.\\-]", "") + ".cpmmodel");
		Random r = new Random();
		while(out.exists()) {
			out = new File(models, name.replaceAll("[^a-zA-Z0-9\\.\\-]", "") + "_" + Integer.toHexString(r.nextInt()) + ".cpmmodel");
		}
		try (FileOutputStream fout = new FileOutputStream(out)){
			fout.write(ModelDefinitionLoader.HEADER);
			ChecksumOutputStream cos = new ChecksumOutputStream(fout);
			IOHelper h = new IOHelper(cos);
			h.writeUTF(name);
			h.writeUTF(desc != null ? desc : "");
			h.writeVarInt(data.length);
			h.write(data);
			h.writeVarInt(0);
			if(icon != null) {
				h.writeImage(icon);
			} else {
				h.writeVarInt(0);
			}
			cos.close();
		}
	}

	public static Link parseLink(String link) throws LocalizedIOException, URISyntaxException {
		URI url = new URI(link);
		for(ResourceLoader rl : LOADERS.values()) {
			ResourceLoader.Validator v = rl.getValidator();
			if(v != null) {
				Link r = v.test(link);
				if(r != null)return r;
			}
		}
		throw new LocalizedIOException("Unknown domain: " + url.getHost(), new FormatText("label.cpm.link.unknownDomain", url.getHost()));
	}

	public Player<?> reloadPlayer(GP gprofile, String unique) {
		Key key = new Key(gprofile, null);
		invalidateAll(key);
		return loadPlayer(gprofile, unique);
	}
}
