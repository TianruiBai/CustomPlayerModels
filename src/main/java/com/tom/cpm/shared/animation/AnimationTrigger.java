package com.tom.cpm.shared.animation;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.tom.cpl.item.NamedSlot;
import com.tom.cpl.item.Stack;
import com.tom.cpl.math.MathHelper;
import com.tom.cpl.util.Hand;
import com.tom.cpl.util.HandAnimation;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.animation.AnimationEngine.AnimationMode;
import com.tom.cpm.shared.network.ServerCaps;

public class AnimationTrigger {
	public final AnimationRegistry reg;
	public final Set<IPose> onPoses;
	public final List<IAnimation> animations;
	public final boolean looping, mustFinish;
	public final VanillaPose valuePose;

	public AnimationTrigger(AnimationRegistry reg, Set<IPose> onPoses, VanillaPose valuePose, List<IAnimation> animations, boolean looping, boolean mustFinish) {
		this.reg = reg;
		this.onPoses = onPoses;
		this.valuePose = valuePose;
		this.animations = animations;
		this.looping = looping;
		this.mustFinish = mustFinish;
	}

	public long getTime(AnimationState state, long time) {
		return valuePose != null ? valuePose.getTime(state, time) : time;
	}

	public boolean canPlay(AnimationState state, AnimationMode mode) {
		return true;
	}

	public static class ItemAnimationTrigger extends AnimationTrigger {
		private final String itemFilter;
		private final String hand;
		private final String action;
		private final HandAnimation useAnimation;

		public ItemAnimationTrigger(AnimationRegistry reg, Set<IPose> onPoses, VanillaPose valuePose, List<IAnimation> animations,
				boolean looping, boolean mustFinish, String itemFilter, String hand, String action, String useAnimation) {
			super(reg, onPoses, valuePose, animations, looping, mustFinish);
			this.itemFilter = clean(itemFilter);
			this.hand = clean(hand);
			this.action = clean(action);
			this.useAnimation = parseUseAnimation(useAnimation);
		}

		@Override
		public boolean canPlay(AnimationState state, AnimationMode mode) {
			if (state == null) return false;
			Hand physicalHand = getPhysicalHand(state);
			if ("use".equals(action)) {
				if (state.usingAnimation == HandAnimation.NONE || state.activeHand != physicalHand) return false;
			} else if ("swing".equals(action)) {
				if (state.attackTime <= 0 || state.swingingHand != physicalHand) return false;
			}
			if (useAnimation != null && state.usingAnimation != useAnimation) return false;
			return matchesItem(getStack(state), itemFilter);
		}

		private static String clean(String value) {
			return value == null || value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
		}

		private static HandAnimation parseUseAnimation(String value) {
			if (value == null || value.isEmpty()) return null;
			for (HandAnimation anim : HandAnimation.VALUES) {
				if (anim.name().equalsIgnoreCase(value)) return anim;
			}
			return null;
		}

		private Hand getPhysicalHand(AnimationState state) {
			if ("offhand".equals(hand)) return state.mainHand == Hand.LEFT ? Hand.RIGHT : Hand.LEFT;
			if ("left".equals(hand)) return Hand.LEFT;
			if ("right".equals(hand)) return Hand.RIGHT;
			return state.mainHand;
		}

		private Stack getStack(AnimationState state) {
			if (state.playerInventory == null) return Stack.EMPTY;
			NamedSlot slot;
			if ("offhand".equals(hand)) {
				slot = NamedSlot.OFF_HAND;
			} else if ("left".equals(hand)) {
				slot = state.mainHand == Hand.LEFT ? NamedSlot.MAIN_HAND : NamedSlot.OFF_HAND;
			} else if ("right".equals(hand)) {
				slot = state.mainHand == Hand.RIGHT ? NamedSlot.MAIN_HAND : NamedSlot.OFF_HAND;
			} else {
				slot = NamedSlot.MAIN_HAND;
			}
			try {
				return state.playerInventory.getStack(state.playerInventory.getNamedSlotId(slot));
			} catch (RuntimeException e) {
				return Stack.EMPTY;
			}
		}

		private static boolean matchesItem(Stack stack, String filter) {
			if (filter == null || filter.isEmpty()) return true;
			String normalizedFilter = filter.replace('$', ':').toLowerCase(Locale.ROOT);
			String id = stack != null ? stack.getItemId().toLowerCase(Locale.ROOT) : "minecraft:air";
			boolean empty = stack == null || stack.getCount() <= 0 || "minecraft:air".equals(id);
			if ("empty".equals(normalizedFilter) || "air".equals(normalizedFilter) || "minecraft:air".equals(normalizedFilter)) return empty;
			if (empty) return false;
			if (normalizedFilter.indexOf(':') >= 0) return id.equals(normalizedFilter) || inNativeTag(stack, normalizedFilter);
			if (id.equals("minecraft:" + normalizedFilter) || id.endsWith(":" + normalizedFilter)) return true;
			switch (normalizedFilter) {
			case "sword":
				return id.endsWith("_sword") || inNativeTag(stack, "minecraft:swords");
			case "pickaxe":
				return id.endsWith("_pickaxe") || inNativeTag(stack, "minecraft:pickaxes");
			case "axe":
				return id.endsWith("_axe") || inNativeTag(stack, "minecraft:axes");
			case "shovel":
				return id.endsWith("_shovel") || inNativeTag(stack, "minecraft:shovels");
			case "hoe":
				return id.endsWith("_hoe") || inNativeTag(stack, "minecraft:hoes");
			case "fishing":
			case "fishingrod":
			case "fishing_rod":
				return "minecraft:fishing_rod".equals(id);
			case "spear":
			case "trident":
				return "minecraft:trident".equals(id);
			case "potion":
			case "throwablepotion":
			case "throwable_potion":
				return id.endsWith(":potion") || id.endsWith(":splash_potion") || id.endsWith(":lingering_potion");
			case "goathorn":
			case "goat_horn":
			case "horn":
				return "minecraft:goat_horn".equals(id);
			default:
				return id.endsWith("_" + normalizedFilter) || inNativeTag(stack, "minecraft:" + normalizedFilter) || inNativeTag(stack, "minecraft:" + normalizedFilter + "s");
			}
		}

		private static boolean inNativeTag(Stack stack, String tag) {
			try {
				return stack.isInNativeTag("#" + tag);
			} catch (RuntimeException e) {
				return false;
			}
		}
	}

	public static class LayerTrigger extends AnimationTrigger {
		private final int id, mask;
		private final boolean bitmask;

		public LayerTrigger(AnimationRegistry reg, Set<IPose> onPoses, List<IAnimation> animations, int id, int mask, boolean bitmask, boolean mustFinish) {
			super(reg, onPoses, null, animations, true, mustFinish);
			this.id = id;
			this.mask = mask;
			this.bitmask = bitmask;
		}

		@Override
		public boolean canPlay(AnimationState state, AnimationMode mode) {
			byte v;
			if (state.gestureData != null && state.gestureData.length > id) {
				v = state.gestureData[id];
			} else {
				v = reg.getParams().getDefaultParam(id);
			}
			if (bitmask)return (v & mask) == mask;
			else return v == mask;
		}
	}

	public static class GestureTrigger extends AnimationTrigger {
		private final int value, gid;

		public GestureTrigger(AnimationRegistry reg, Set<IPose> onPoses, List<IAnimation> animations, int value, int gid, boolean looping, boolean mustFinish) {
			super(reg, onPoses, null, animations, looping, mustFinish);
			this.value = value;
			this.gid = gid;
		}

		@Override
		public boolean canPlay(AnimationState state, AnimationMode mode) {
			if (MinecraftClientAccess.get().getNetHandler().hasServerCap(ServerCaps.GESTURES)) {
				if (state.gestureData != null && state.gestureData.length > 1) {
					byte v = state.gestureData[1];
					return v == value;
				}
			} else if(gid != -1) {
				return state.encodedState == gid;
			}
			return false;
		}
	}

	public static class ValueTrigger extends AnimationTrigger {
		private final int id;
		private boolean interpolate;

		public ValueTrigger(AnimationRegistry reg, Set<IPose> onPoses, List<IAnimation> animations, int id, boolean interpolate) {
			super(reg, onPoses, null, animations, true, false);
			this.id = id;
			this.interpolate = interpolate;
		}

		@Override
		public long getTime(AnimationState state, long animTime) {
			if (state != null && state.gestureData != null && state.gestureData.length > id) {
				float val = Byte.toUnsignedInt(state.gestureData[id]) / 256f;
				long time = MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().getTime();
				if (interpolate && state.prevGestureData != null && state.prevGestureData.length == state.gestureData.length && state.lastGestureReceiveTime + 50 >= time) {
					float prev = Byte.toUnsignedInt(state.prevGestureData[id]) / 256f;
					val = MathHelper.lerp((time - state.lastGestureReceiveTime) / 50f, prev, val);
				}
				return (long) (val * VanillaPose.DYNAMIC_DURATION_MUL);
			} else {
				float val = Byte.toUnsignedInt(reg.getParams().getDefaultParam(id)) / 256f;
				return (long) (val * VanillaPose.DYNAMIC_DURATION_MUL);
			}
		}
	}
}
