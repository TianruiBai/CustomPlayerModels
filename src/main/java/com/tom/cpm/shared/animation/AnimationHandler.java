package com.tom.cpm.shared.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.tom.cpm.shared.animation.AnimationEngine.AnimationMode;
import com.tom.cpm.shared.definition.ModelDefinition;

public class AnimationHandler {
	private final Supplier<ModelDefinition> player;
	private final AnimationMode mode;

	private List<PlayingAnim> currentAnimations = new ArrayList<>();
	private List<NextAnim> nextAnims = new ArrayList<>();

	public AnimationHandler(Supplier<ModelDefinition> player, AnimationMode mode) {
		this.player = player;
		this.mode = mode;
	}

	public void animate(AnimationState state, long currentTime) {
		boolean needsSort = false;
		currentAnimations.removeIf(a -> nextAnims.stream().noneMatch(n -> n.animation == a.currentAnimation) && a.checkAndUpdateRemove(mode));
		for (NextAnim animation : nextAnims) {
			boolean found = false;
			for (int i = 0; i < currentAnimations.size(); i++) {
				if(currentAnimations.get(i).currentAnimation == animation.animation) {
					found = true;
					break;
				}
			}
			if(!found) {
				currentAnimations.add(new PlayingAnim(animation, currentTime));
				needsSort = true;
			}
		}
		if(needsSort)
			currentAnimations.sort(Comparator.comparingInt(e -> e.currentAnimation.getPriority(mode)));

		player.get().resetAnimationPos();

		for (PlayingAnim a : currentAnimations) {
			if(!a.finished || a.mustFinish) {
				long currentStep = (currentTime - a.currentStart);
				long playbackStep = a.finished ? a.lastFrameTime(mode) : currentStep;
				a.currentAnimation.animate(state, a.getTime(state, playbackStep), player.get(), mode);

				if(!a.finished && !a.loop && currentStep / a.currentAnimation.getDuration(mode) != a.lastFrame / a.currentAnimation.getDuration(mode)) {
					a.finished = true;
				}
				a.lastFrame = currentStep;
			}
		}

		nextAnims.clear();
	}

	public void addAnimations(AnimationState state, List<AnimationTrigger> next, IPose pose) {
		List<AnimationTrigger> playable = next.stream().filter(t -> t.canPlay(state, mode)).collect(Collectors.toList());
		int bestExclusiveScore = playable.stream()
			.filter(AnimationTrigger::isExclusiveMatch)
			.mapToInt(t -> t.getMatchScore(state, mode))
			.max().orElse(-1);
		if (bestExclusiveScore >= 0) {
			AnimationTrigger best = playable.stream()
				.filter(AnimationTrigger::isExclusiveMatch)
				.filter(t -> t.getMatchScore(state, mode) == bestExclusiveScore)
				.findFirst().orElse(null);
			playable = best != null ? List.of(best) : List.of();
		}
		playable.forEach(t -> {
			for (IAnimation a : t.animations) {
				nextAnims.add(new NextAnim(a, t));
			}
		});
	}

	public void clear() {
		nextAnims.clear();
		currentAnimations.clear();
	}

	private static class NextAnim {
		private IAnimation animation;
		private AnimationTrigger trigger;

		public NextAnim(IAnimation animation, AnimationTrigger trigger) {
			this.animation = animation;
			this.trigger = trigger;
		}

		public boolean isLoop() {
			return trigger.looping;
		}
	}

	private class PlayingAnim {
		private IAnimation currentAnimation;
		private AnimationTrigger trigger;
		private long currentStart, lastFrame;
		private boolean loop, finished, mustFinish;

		public PlayingAnim(NextAnim anim, long currentStart) {
			this.currentAnimation = anim.animation;
			currentAnimation.prepare(mode);
			this.trigger = anim.trigger;
			this.currentStart = currentStart;
			this.loop = anim.isLoop();
			this.finished = false;
			this.mustFinish = anim.trigger.mustFinish;
			if (anim.trigger.startsAtEnd()) {
				this.currentStart = currentStart - lastFrameTime(mode);
				this.lastFrame = lastFrameTime(mode);
				this.finished = true;
			}
		}

		public boolean checkAndUpdateRemove(AnimationMode mode) {
			if (mustFinish) {
				loop = false;
				return currentAnimation.checkAndUpdateRemove(mode) && finished;
			}
			return currentAnimation.checkAndUpdateRemove(mode);
		}

		public long getTime(AnimationState state, long time) {
			long animTime = trigger == null || !currentAnimation.useTriggerTime() ? time : trigger.getTime(state, time);
			return loop ? animTime : Math.min(animTime, lastFrameTime(mode));
		}

		private long lastFrameTime(AnimationMode mode) {
			return Math.max(0, currentAnimation.getDuration(mode) - 1L);
		}
	}
}
