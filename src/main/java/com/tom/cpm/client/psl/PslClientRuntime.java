package com.tom.cpm.client.psl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleDescription;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.player.Player;

import com.mojang.blaze3d.platform.NativeImage;

import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO;
import com.tom.cpm.client.ClientBase;
import com.tom.cpm.shared.psl.IPslRuntime;
import com.tom.cpm.shared.psl.sound.SoundEmitter;

/**
 * NeoForge 1.21 client implementation of IPslRuntime.
 * Bridges shared PSL logic with Minecraft client APIs.
 */
public class PslClientRuntime implements IPslRuntime {

	private final Minecraft mc;
	private final SoundPlayer soundPlayer = new SoundPlayer();
	private Player currentPlayer;

	public PslClientRuntime() {
		this.mc = Minecraft.getInstance();
	}

	public void beginPlayer(Player player) {
		currentPlayer = player;
	}

	public void endPlayer() {
		currentPlayer = null;
	}

	@Override
	public InputStream getResource(String path) {
		// Phase 3+: Load from model's project cache
		return null;
	}

	@Override
	public float getParticleAmountFactor() {
		if (mc.options == null || mc.options.particles() == null) return 1.0f;
		return switch (mc.options.particles().get()) {
			case ALL -> 1.0f;
			case DECREASED -> 0.5f;
			case MINIMAL -> 0.25f;
		};
	}

	@Override
	public boolean isShaderPackActive() {
		return ClientBase.irisLoaded;
	}

	@Override
	public void registerDynamicLight(int entityId, float x, float y, float z,
	                                  int color, float level, float radius) {
		// Phase 5+: Implement dynamic light registration
	}

	@Override
	public void updateDynamicLight(int entityId, float x, float y, float z) {
		// Phase 5+: Implement dynamic light position update
	}

	@Override
	public void unregisterDynamicLight(int entityId) {
		// Phase 5+: Implement dynamic light removal
	}

	@Override
	public boolean isDynamicLightSupported() {
		return false; // Phase 5+
	}

	@Override
	public void spawnBuiltinParticle(String particleId, float x, float y, float z, float vx, float vy, float vz) {
		if(mc.level == null || particleId == null || particleId.isEmpty())return;
		try {
			var type = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse(particleId));
			if(type instanceof SimpleParticleType) {
				mc.level.addParticle((SimpleParticleType) type, x, y, z, vx, vy, vz);
			} else {
				spawnFallbackParticle(x, y, z, vx, vy, vz);
			}
		} catch (Exception ignored) {
			spawnFallbackParticle(x, y, z, vx, vy, vz);
		}
	}

	private void spawnFallbackParticle(float x, float y, float z, float vx, float vy, float vz) {
		var fallback = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse("minecraft:poof"));
		if(fallback instanceof SimpleParticleType)mc.level.addParticle((SimpleParticleType) fallback, x, y, z, vx, vy, vz);
	}

	@Override
	public Vec3f toWorldPosition(Vec3f modelPosition) {
		Vec3f pos = modelPosition != null ? modelPosition : Vec3f.ZERO;
		Player player = currentPlayer != null ? currentPlayer : mc.player;
		if(player == null)return pos;
		return new Vec3f((float)player.getX() + pos.x, (float)player.getY() + 1.4f + pos.y, (float)player.getZ() + pos.z);
	}

	@Override
	public void playSound(SoundEmitter emitter, Vec3f worldPosition) {
		soundPlayer.play(emitter, worldPosition);
	}

	@Override
	public boolean checkBlockCollision(float x, float y, float z) {
		if (mc.level == null) return false;
		var blockPos = new net.minecraft.core.BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		return !mc.level.getBlockState(blockPos).isAir();
	}

	@Override
	public Image loadParticleImage(String particleId) {
		if (particleId == null || particleId.isEmpty()) return null;
		try {
			ResourceLocation particleIdRl = ResourceLocation.parse(particleId);
			TextureAtlas atlas = getParticleAtlas();
			if(atlas != null) {
				Image fromDescription = loadFromParticleDefinition(atlas, particleIdRl);
				if(fromDescription != null)return fromDescription;

				Image directSprite = loadAtlasSprite(atlas, particleIdRl);
				if(directSprite != null)return directSprite;
			}
		} catch (Exception ignored) {
		}

		// Fallback for resource packs or custom ids that expose an individual PNG.
		try {
			ResourceLocation rl = ResourceLocation.parse(particleId);
			ResourceLocation texRl = ResourceLocation.fromNamespaceAndPath(rl.getNamespace(),
				"textures/particle/" + rl.getPath() + ".png");
			var opt = mc.getResourceManager().getResource(texRl);
			if (opt.isPresent()) {
				try (InputStream is = opt.get().open()) {
					return ImageIO.read(is);
				}
			}
		} catch (IOException ignored) {
		} catch (Exception ignored) {
		}
		return null;
	}

	private TextureAtlas getParticleAtlas() {
		AbstractTexture tex = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
		return tex instanceof TextureAtlas atlas ? atlas : null;
	}

	private Image loadFromParticleDefinition(TextureAtlas atlas, ResourceLocation particleId) {
		ResourceLocation definitionPath = ResourceLocation.fromNamespaceAndPath(particleId.getNamespace(), "particles/" + particleId.getPath() + ".json");
		try {
			var resource = mc.getResourceManager().getResource(definitionPath);
			if(resource.isEmpty())return null;
			try (Reader reader = resource.get().openAsReader()) {
				ParticleDescription description = ParticleDescription.fromJson(GsonHelper.parse(reader));
				for(ResourceLocation spriteId : description.getTextures()) {
					Image image = loadAtlasSprite(atlas, spriteId);
					if(image != null)return image;
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private Image loadAtlasSprite(TextureAtlas atlas, ResourceLocation spriteId) {
		try {
			TextureAtlasSprite sprite = atlas.getSprite(spriteId);
			if(sprite == null || sprite.contents() == null)return null;
			if(MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name()))return null;
			NativeImage ni = sprite.contents().getOriginalImage();
			if(ni != null && ni.getWidth() > 0 && ni.getHeight() > 0)return nativeImageToCpm(ni);
		} catch (Exception ignored) {
		}
		return null;
	}

	/** Convert a NativeImage (ABGR) to a CPM Image (ARGB). */
	private static Image nativeImageToCpm(NativeImage ni) {
		int w = ni.getWidth();
		int h = ni.getHeight();
		Image img = new Image(w, h);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int rgba = ni.getPixelRGBA(x, y);
				int a = (rgba >> 24) & 0xFF;
				int b = (rgba >> 16) & 0xFF;
				int g = (rgba >> 8) & 0xFF;
				int r = rgba & 0xFF;
				img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}
		return img;
	}
}
