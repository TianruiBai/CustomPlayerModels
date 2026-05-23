package com.tom.cpm.shared.model;

import java.util.function.Function;

import com.google.common.base.Objects;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;

import com.tom.cpl.text.FormatText;
import com.tom.cpm.shared.animation.AnimationEngine.AnimationMode;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.model.render.ModelRenderManager;
import com.tom.cpm.shared.model.render.ModelRenderManager.BoundPlayer;
import com.tom.cpm.shared.network.ModelEventType;

public class RenderManager<G, P, M, D> {
	protected final ModelRenderManager<D, ?, ?, M> renderManager;
	protected final ModelDefinitionLoader<G> loader;
	protected Function<P, G> getProfile;
	protected Function<G, String> getSkullModel;
	protected Function<G, String> getTexture;

	public RenderManager(ModelRenderManager<D, ?, ?, M> renderManager,
			ModelDefinitionLoader<G> loader, Function<P, G> getProfile) {
		this.renderManager = renderManager;
		this.loader = loader;
		this.getProfile = getProfile;
	}

	@SuppressWarnings("unchecked")
	public Player<P> loadPlayerProfile(G gprofile, P player, String unique) {
		if(gprofile == null)gprofile = getProfile.apply(player);
		Player<P> profile = (Player<P>) loader.loadPlayer(gprofile, unique);
		if(profile == null) {
			return null;
		}
		if (!profile.isModel && getTexture != null) {
			String texture = getTexture.apply(gprofile);
			String ptexture = getTexture.apply((G) profile.getGameProfile());
			if (texture != null && !Objects.equal(texture, ptexture)) {
				profile = (Player<P>) loader.reloadPlayer(gprofile, unique);
			}
			if(profile == null) {
				return null;
			}
		}
		return profile;
	}

	public void prepareAnimationState(Player<P> profile, P player, AnimationState state) {
		if (player != null)
			profile.updatePlayer(player, state);

		ModelDefinition def = profile.getModelDefinition();
		if (def != null) {
			def.itemTransforms.clear();
		}
	}

	public Player<P> loadPlayerState(G gprofile, P player, String unique, AnimationState state) {
		Player<P> profile = loadPlayerProfile(gprofile, player, unique);
		ModelDefinition def = profile.getModelDefinition();
		if (def != null) {
			prepareAnimationState(profile, player, state);
			return profile;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public Player<P> loadSkull(G gprofile) {
		String unique;
		if(getSkullModel == null)unique = ModelDefinitionLoader.SKULL_UNIQUE;
		else {
			unique = getSkullModel.apply(gprofile);
			if(unique == null) {
				if(getTexture == null)unique = ModelDefinitionLoader.SKULL_UNIQUE;
				else unique = getTexture.apply(gprofile);
				if(unique == null)unique = ModelDefinitionLoader.SKULL_UNIQUE;
				else unique = "skull_tex:" + unique;
			}
			else unique = "model:" + unique;
		}

		Player<P> profile = (Player<P>) loader.loadPlayer(gprofile, unique);
		if(profile == null) {
			return null;
		}
		ModelDefinition def = profile.getModelDefinition();
		if(def != null) {
			return profile;
		}
		return null;
	}

	public void bindPlayerState(Player<P> player, D buffer, M toBind, String arg, AnimationState state) {
		if (player != null) {
			ModelDefinition def = player.getModelDefinition();
			renderManager.bindModel(toBind, arg, buffer, def, player, state);
			renderManager.getAnimationEngine().prepareAnimations(player.persistentState, state, def);
		}
	}

	public void unbind(M model) {
		renderManager.unbindModel(model);
	}

	public void unbindFlush(M model) {
		renderManager.flushBatch(model, null);
		unbind(model);
	}

	public void bindHand(P player, D buffer, M model) {
		Player<P> profile = loadPlayerProfile(null, player, ModelDefinitionLoader.PLAYER_UNIQUE);
		ModelDefinition def = profile.getModelDefinition();
		if (def != null) {
			AnimationState state = new AnimationState(AnimationMode.HAND);
			prepareAnimationState(profile, player, state);
			bindPlayerState(profile, buffer, model, null, state);
		}
	}

	public void bindSkull(G profile, D buffer, M model) {
		Player<P> modelProf = loadSkull(profile);
		if (modelProf != null) {
			AnimationState state = new AnimationState(AnimationMode.SKULL);
			bindPlayerState(modelProf, buffer, model, null, state);
		}
	}

	public void bindPlayer(P player, D buffer, M model) {
		Player<P> profile = loadPlayerProfile(null, player, ModelDefinitionLoader.PLAYER_UNIQUE);
		ModelDefinition def = profile.getModelDefinition();
		if (def != null) {
			AnimationState state = new AnimationState(AnimationMode.PLAYER);
			prepareAnimationState(profile, player, state);
			bindPlayerState(profile, buffer, model, null, state);
		}
	}

	public void bindArmor(M player, M model, int layer) {
		renderManager.bindSubModel(player, model, "armor" + layer);
	}

	public void bindElytra(M player, M model) {
		renderManager.bindSubModel(player, model, null);
	}

	public void bindSkin(M model, TextureSheetType tex) {
		renderManager.bindSkin(model, null, tex);
	}

	public void setGetSkullModel(Function<G, String> getSkullModel) {
		this.getSkullModel = getSkullModel;
	}

	public void setGetTexture(Function<G, String> getTexture) {
		this.getTexture = getTexture;
	}

	public <PR> void setGPGetters(Function<G, Multimap<String, PR>> getMap, Function<PR, String> getValue) {
		setGetSkullModel(profile -> {
			PR property = Iterables.getFirst(getMap.apply(profile).get("cpm:model"), null);
			if(property != null)return getValue.apply(property);
			return null;
		});
		setGetTexture(profile -> {
			PR property = Iterables.getFirst(getMap.apply(profile).get("textures"), null);
			if(property != null)return getValue.apply(property);
			return null;
		});
	}

	@SuppressWarnings("unchecked")
	public void jump(P player) {
		G gprofile = getProfile.apply(player);
		Player<P> profile = (Player<P>) loader.loadPlayer(gprofile, ModelDefinitionLoader.PLAYER_UNIQUE);
		if(profile == null)return;
		ModelEventType.JUMPING.trigger(profile.persistentState);
	}

	@SuppressWarnings("unchecked")
	public FormatText getStatus(G gprofile, String unique) {
		Player<P> profile = (Player<P>) loader.loadPlayer(gprofile, unique);
		if(profile == null)return null;
		ModelDefinition def = profile.getModelDefinition0();
		return def != null ? def.getStatus() : null;
	}

	public BoundPlayer getPlayerFromModel(M parentModel) {
		return renderManager.getBoundPlayer(parentModel, null);
	}
}
