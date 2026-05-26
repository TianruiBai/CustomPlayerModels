package com.tom.cpm.shared.editor.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.KeyboardEvent;
import com.tom.cpl.gui.MouseEvent;
import com.tom.cpl.math.MathHelper;
import com.tom.cpl.math.MatrixStack;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.math.Vec4f;
import com.tom.cpl.render.VBuffers;
import com.tom.cpl.render.VertexBuffer;
import com.tom.cpl.util.Hand;
import com.tom.cpl.util.ItemSlot;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.animation.InterpolatorChannel;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.animation.interpolator.Interpolator;
import com.tom.cpm.shared.editor.DisplayItem;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.anim.AnimationDisplayData;
import com.tom.cpm.shared.editor.anim.AnimationDisplayData.Type;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.IElem;
import com.tom.cpm.shared.editor.tree.VecType;
import com.tom.cpm.shared.editor.util.FilterBuffers;
import com.tom.cpm.shared.gui.Keybinds;
import com.tom.cpm.shared.model.builtin.VanillaPlayerModel;
import com.tom.cpm.shared.model.render.PlayerModelSetup.ArmPose;
import com.tom.cpm.shared.model.render.RenderMode;
import com.tom.cpm.shared.util.PlayerModelLayer;

public class ViewportPanelAnim extends ViewportPanel {
	private List<AnimationDisplayData> anims;
	private Vec3f animPos, animRot;
	private FilterBuffers filter = new FilterBuffers(r -> types.get(RenderMode.OUTLINE) == r);

	public ViewportPanelAnim(Frame frm, Editor editor) {
		super(frm, editor);
		editor.setAnimPos.add(v -> animPos = v);
		editor.setAnimRot.add(v -> animRot = v);
	}

	@Override
	public void draw(MouseEvent event, float partialTicks) {
		super.draw(event, partialTicks);
		if (editor.selectedAnim != null && editor.selectedAnim.pose instanceof VanillaPose) {
			int spr = -1;
			switch ((VanillaPose) editor.selectedAnim.pose) {

			case HEALTH:
				spr = 0;
				break;

			case HUNGER:
				spr = 1;
				break;

			case AIR:
				spr = 2;
				break;

			case LIGHT:
				spr = 3;

			default:
				break;
			}
			if (spr != -1) {
				int frms = editor.selectedAnim.getFrames().size();
				int val = (int) (frms > 1 ? (editor.selectedAnim.getSelectedFrameIndex() / (float) (frms - 1) * 20) : 20);
				for (int i = 0;i<10;i++) {
					int sy = MathHelper.clamp(val - i * 2, 0, 2);
					gui.drawTexture(bounds.x + i * 9 + 1, bounds.y + bounds.h - 10, 9, 9, spr * 9, sy * 9 + 64, "editor");
				}
			}
		}
	}

	@Override
	public DisplayItem getHeldItem(ItemSlot hand) {
		if(editor.selectedAnim != null) {
			if(editor.selectedAnim.pose != null && editor.selectedAnim.pose instanceof VanillaPose) {
				AnimationDisplayData dt = AnimationDisplayData.getFor((VanillaPose) editor.selectedAnim.pose);
				if(dt.slot == hand) {
					return dt.item;
				}
			}
			if(!editor.forceHeldItemInAnim.get())return DisplayItem.NONE;
		} else if(anims != null) {
			return anims.stream().filter(p -> p.slot == hand).map(p -> p.item).filter(p -> p != null).findFirst().orElse(DisplayItem.NONE);
		}
		return super.getHeldItem(hand);
	}

	@Override
	protected int getItemState(ItemSlot slot, int maxStates) {
		float progress = 0;
		AnimationDisplayData data = null;
		if(editor.selectedAnim != null) {
			data = editor.selectedAnim.pose instanceof VanillaPose ? AnimationDisplayData.getFor((VanillaPose) editor.selectedAnim.pose) : null;
			progress = getAnimProgress();
		} else if(anims != null) {
			data = anims.stream().filter(p -> p.slot == slot && p.layerSlot != null).findFirst().orElse(null);
			progress = data != null ? editor.animTestSliders.getOrDefault("__pose", 0f) : 0f;
		}
		DisplayItem i = getHeldItem(slot);
		if(i == DisplayItem.CROSSBOW) {
			if(data == AnimationDisplayData.CROSSBOW_CH_LEFT || data == AnimationDisplayData.CROSSBOW_CH_RIGHT)
				return (int) Math.min(progress * 3 + 1, maxStates - 1);
		} else if(i == DisplayItem.BOW) {
			return (int) Math.min(progress * 3, maxStates - 1);
		}
		return 0;
	}

	@Override
	protected Hand poseModel0(VanillaPlayerModel p, MatrixStack matrixstack, float partialTicks) {
		float progress = 0;
		AnimationDisplayData data = null;
		if(editor.selectedAnim != null) {
			data = editor.selectedAnim.pose instanceof VanillaPose ? AnimationDisplayData.getFor((VanillaPose) editor.selectedAnim.pose) : null;
			progress = getAnimProgress();
		} else if(anims != null) {
			data = anims.stream().filter(e -> e.type == Type.HAND).findFirst().orElse(null);
			progress = data != null ? editor.animTestSliders.getOrDefault("__pose", 0f) : 0f;
		}
		if(data == AnimationDisplayData.CROSSBOW_CH_LEFT || data == AnimationDisplayData.CROSSBOW_CH_RIGHT) {
			p.useAmount = progress;
			if(p.leftArmPose == ArmPose.CROSSBOW_HOLD)p.leftArmPose = ArmPose.CROSSBOW_CHARGE;
			if(p.rightArmPose == ArmPose.CROSSBOW_HOLD)p.rightArmPose = ArmPose.CROSSBOW_CHARGE;
		} else if(data == AnimationDisplayData.BOW_LEFT || data == AnimationDisplayData.BOW_RIGHT)
			p.useAmount = progress;
		else if(data == AnimationDisplayData.PUNCH_LEFT) {
			p.attackTime = progress;
			return Hand.LEFT;
		} else if(data == AnimationDisplayData.PUNCH_RIGHT)
			p.attackTime = progress;
		return Hand.RIGHT;
	}

	private float getAnimProgress() {
		if(editor.playFullAnim) {
			long playTime = MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().getTime();
			long currentStep = (playTime - editor.playStartTime);
			return (currentStep % editor.selectedAnim.duration) / (float) editor.selectedAnim.duration;
		} else
			return editor.selectedAnim.getAnimProgess();
	}

	@Override
	public Set<PlayerModelLayer> getArmorLayers() {
		if(anims != null)
			return anims.stream().map(e -> e.layer).filter(e -> e != null).collect(Collectors.toSet());
		else
			return super.getArmorLayers();
	}

	@Override
	protected int drawParrots() {
		int r = editor.forceHeldItemInAnim.get() ? super.drawParrots() : 0;
		if(anims != null) {
			if(anims.contains(AnimationDisplayData.PARROT_LEFT))r |= 1;
			if(anims.contains(AnimationDisplayData.PARROT_RIGHT))r |= 2;
		}
		return r;
	}

	@Override
	protected VecType[] getVecTypes() {
		return VecType.MOUSE_EDITOR_ANIM_TYPES;
	}

	@Override
	protected Vec3f getVec(VecType type) {
		if(editor.selectedAnim != null) {
			editor.selectedAnim.beginDrag();
		}
		switch (type) {
		case POSITION:
			return animPos != null ? animPos : new Vec3f();
		case ROTATION:
			return animRot != null ? animRot : new Vec3f();
		default:
			return new Vec3f();
		}
	}

	@Override
	protected void setVec(VecType type, Vec3f vec, boolean temp) {
		if(temp) {
			switch (type) {
			case POSITION:
				editor.setAnimPos.accept(vec);
				if(editor.selectedAnim != null) {
					editor.selectedAnim.dragVal(type, vec);
				}
				break;

			case ROTATION:
				editor.setAnimRot.accept(vec);
				if(editor.selectedAnim != null) {
					editor.selectedAnim.dragVal(type, vec);
				}
				break;

			default:
				break;
			}
		} else {
			switch (type) {
			case POSITION:
				editor.setAnimPos(vec);
				editor.setAnimPos.accept(vec);
				break;

			case ROTATION:
				editor.setAnimRot(vec);
				editor.setAnimRot.accept(vec);
				break;

			default:
				break;
			}
		}
	}

	@Override
	protected void endGizmoDrag(boolean apply) {
		super.endGizmoDrag(apply);
		if(editor.selectedAnim != null)
			editor.selectedAnim.endDrag();
	}

	@Override
	public boolean canEdit() {
		return editor.selectedAnim != null && super.canEdit();
	}

	@Override
	public void render(MatrixStack stack, VBuffers buf, float partialTicks) {
		anims = null;
		if(editor.playFullAnim) {
			anims = editor.testPoses.stream().map(p -> AnimationDisplayData.getFor(p)).
					filter(d -> d.type == Type.LAYERS || d.type == Type.HAND || d.type == Type.PROGRESS).collect(Collectors.toList());
		}
		editor.applyAnim = true;
		ModelElement trackElement = editor.getSelectedElement();
		boolean renderPreviousFrame = editor.showPreviousFrame.get() && editor.selectedAnim != null && editor.selectedElement != null && editor.selectedAnim.getFrames().size() > 1;
		boolean drawMovementTrack = editor.showMovementTrack.get() && editor.selectedAnim != null && trackElement != null && editor.selectedAnim.getFrames().size() > 1;
		if(renderPreviousFrame && editor.selectedAnim != null) {
			List<AnimFrame> frames = editor.selectedAnim.getFrames();
			AnimFrame originalFrame = editor.selectedAnim.getSelectedFrame();
			int selectedFrame = editor.selectedAnim.getSelectedFrameIndex();
			int previousFrame = (selectedFrame - 1 + frames.size()) % frames.size();
			editor.definition.renderingPanel = this;
			editor.definition.outlineOnly = true;
			editor.selectedAnim.setSelectedFrame(frames.get(previousFrame));
			renderModel(stack, filter.filter(buf), partialTicks);
			editor.selectedAnim.setSelectedFrame(originalFrame);
			editor.definition.renderingPanel = null;
			editor.definition.outlineOnly = false;
		}
		super.render(stack, buf, partialTicks);
		if(drawMovementTrack) {
			Vec3f currentTrackPos = getElementPosition(trackElement);
			int displayFrame = getDisplayedFrameIndex();
			float displayTime = getDisplayedTrackTime();
			List<Vec3f> movementTrack = currentTrackPos != null ? buildMovementTrack(trackElement, currentTrackPos, displayTime) : null;
			VertexBuffer buffer = buf.getBuffer(types, RenderMode.OUTLINE);
			if(movementTrack != null)drawMovementTracks(buffer, movementTrack, displayFrame, editor.selectedAnim.getFrames().size());
		}
		editor.applyAnim = false;
		anims = null;
	}

	private Vec3f getElementPosition(ModelElement element) {
		if(element == null || element.matrixPosition == null)return null;
		Vec4f pos = new Vec4f(0, 0, 1, 1);
		pos.transform(element.matrixPosition);
		return new Vec3f(pos.x, pos.y, pos.z);
	}

	private int getDisplayedFrameIndex() {
		if(editor.selectedAnim == null)return 0;
		int frameCount = editor.selectedAnim.getFrames().size();
		if(frameCount <= 1)return 0;
		if(editor.playFullAnim) {
			long playTime = MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().getTime();
			long duration = Math.max(1, editor.selectedAnim.duration);
			float progress = Math.floorMod(playTime - editor.playStartTime, duration) / (float) duration;
			return Math.max(0, Math.min(frameCount - 1, (int) (progress * frameCount)));
		}
		return Math.max(0, Math.min(frameCount - 1, editor.selectedAnim.getSelectedFrameIndex()));
	}

	private float getDisplayedTrackTime() {
		if(editor.selectedAnim == null)return 0;
		int frameCount = editor.selectedAnim.getFrames().size();
		if(frameCount <= 1)return 0;
		if(editor.playFullAnim) {
			long playTime = MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().getTime();
			long duration = Math.max(1, editor.selectedAnim.duration);
			return Math.floorMod(playTime - editor.playStartTime, duration) / (float) duration * frameCount;
		}
		return editor.selectedAnim.getSelectedFrameIndex();
	}

	private List<Vec3f> buildMovementTrack(ModelElement element, Vec3f currentWorldPos, float currentTime) {
		int frameCount = editor.selectedAnim.getFrames().size();
		Interpolator[] posInterpolators = createPositionInterpolators(element);
		Vec3f currentLocal = sampleTrackPosition(posInterpolators, currentTime);
		int samples = Math.max(frameCount * 8, 16);
		List<Vec3f> positions = new ArrayList<>(samples + 1);
		for(int i = 0; i <= samples; i++) {
			float sampleTime = i / (float) samples * frameCount;
			Vec3f local = sampleTrackPosition(posInterpolators, sampleTime);
			positions.add(new Vec3f(currentWorldPos.x + (local.x - currentLocal.x) / 16f, currentWorldPos.y + (local.y - currentLocal.y) / 16f, currentWorldPos.z + (local.z - currentLocal.z) / 16f));
		}
		return positions;
	}

	private Interpolator[] createPositionInterpolators(ModelElement element) {
		Interpolator[] posInterpolators = new Interpolator[3];
		InterpolatorChannel[] channels = {InterpolatorChannel.POS_X, InterpolatorChannel.POS_Y, InterpolatorChannel.POS_Z};
		for(int i = 0; i < channels.length; i++) {
			Interpolator interpolator = editor.selectedAnim.intType.create();
			interpolator.init(AnimFrame.toArray(editor.selectedAnim, element, channels[i]), channels[i].createInterpolatorSetup());
			posInterpolators[i] = interpolator;
		}
		return posInterpolators;
	}

	private Vec3f sampleTrackPosition(Interpolator[] posInterpolators, float time) {
		return new Vec3f((float) posInterpolators[0].applyAsDouble(time), (float) posInterpolators[1].applyAsDouble(time), (float) posInterpolators[2].applyAsDouble(time));
	}

	private Vec3f getFramePosition(AnimFrame frame, ModelElement element) {
		IElem data = frame.getData(element);
		if(data != null)return data.getPosition();
		if(editor.selectedAnim.add)return new Vec3f();
		return new Vec3f(element.pos);
	}

	private void drawMovementTracks(VertexBuffer buffer, List<Vec3f> positions, int selectedFrame, int frameCount) {
		if(positions.size() < 2)return;
		boolean highContrast = editor.highContrastMovementTrack.get();
		for(int i = 1; i < positions.size(); i++) {
			if(positions.get(i - 1) == null || positions.get(i) == null)continue;
			drawMovementSegment(buffer, positions.get(i - 1), positions.get(i), highContrast ? 0.15f : 0.25f, highContrast ? 0.95f : 0.75f, 1, highContrast ? 0.85f : 0.55f, true);
		}
		int prevFrame = selectedFrame - 1;
		if(prevFrame < 0 && editor.selectedAnim.loop)prevFrame = frameCount - 1;
		int prevPrevFrame = prevFrame - 1;
		if(prevPrevFrame < 0 && editor.selectedAnim.loop)prevPrevFrame = frameCount - 1;
		if(editor.showPastMovementTrack.get() && prevPrevFrame >= 0 && prevFrame >= 0) {
			int pastStart = frameToTrackIndex(prevPrevFrame, positions.size(), frameCount);
			int pastEnd = frameToTrackIndex(prevFrame, positions.size(), frameCount);
			if(pastStart >= 0 && pastEnd > pastStart && positions.get(pastStart) != null && positions.get(pastEnd) != null) {
				drawMovementSegment(buffer, positions.get(pastStart), positions.get(pastEnd), highContrast ? 1 : 0.92f, highContrast ? 1 : 0.92f, highContrast ? 1 : 0.92f, highContrast ? 0.95f : 0.75f, false);
				drawPoint(buffer, positions.get(pastEnd), highContrast ? 1 : 0.9f, highContrast ? 1 : 0.9f, highContrast ? 1 : 0.9f, highContrast ? 1 : 0.85f, highContrast ? 0.04f : 0.03f);
			}
		}
		if(prevFrame >= 0) {
			int currentStart = frameToTrackIndex(prevFrame, positions.size(), frameCount);
			int currentEnd = frameToTrackIndex(selectedFrame == 0 && editor.selectedAnim.loop ? frameCount : selectedFrame, positions.size(), frameCount);
			if(currentStart >= 0 && currentEnd > currentStart && positions.get(currentStart) != null && positions.get(currentEnd) != null) {
				drawMovementSegment(buffer, positions.get(currentStart), positions.get(currentEnd), 1, highContrast ? 0.9f : 0.78f, 0.1f, 1, false);
				drawPoint(buffer, positions.get(currentStart), highContrast ? 1 : 0.85f, highContrast ? 1 : 0.85f, highContrast ? 1 : 0.85f, highContrast ? 1 : 0.8f, highContrast ? 0.04f : 0.03f);
				drawPoint(buffer, positions.get(currentEnd), 1, highContrast ? 0.95f : 0.84f, 0.1f, 1, highContrast ? 0.055f : 0.04f);
			}
		}
	}

	private int frameToTrackIndex(int frame, int pointCount, int frameCount) {
		if(frameCount <= 0 || pointCount <= 0)return -1;
		return Math.max(0, Math.min(pointCount - 1, Math.round(frame / (float) frameCount * (pointCount - 1))));
	}

	private void drawMovementSegment(VertexBuffer buffer, Vec3f from, Vec3f to, float r, float g, float b, float a, boolean dashed) {
		float dx = to.x - from.x;
		float dy = to.y - from.y;
		float dz = to.z - from.z;
		float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if(dist < 0.0001f)return;
		if(!dashed) {
			if(editor.highContrastMovementTrack.get())addLine(buffer, new Vec3f(from.x, from.y + 0.006f, from.z), new Vec3f(to.x, to.y + 0.006f, to.z), 0, 0, 0, 0.95f);
			addLine(buffer, from, to, r, g, b, a);
			return;
		}
		int steps = Math.max(6, Math.min(36, (int) (dist * 24)));
		for(int i = 0; i < steps; i += 2) {
			float s0 = i / (float) steps;
			float s1 = Math.min(i + 1, steps) / (float) steps;
			Vec3f start = lerp(from, to, s0);
			Vec3f end = lerp(from, to, s1);
			if(editor.highContrastMovementTrack.get())addLine(buffer, new Vec3f(start.x, start.y + 0.006f, start.z), new Vec3f(end.x, end.y + 0.006f, end.z), 0, 0, 0, 0.95f);
			addLine(buffer, start, end, r, g, b, a);
		}
	}

	private Vec3f lerp(Vec3f a, Vec3f b, float t) {
		return new Vec3f(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
	}

	private void drawPoint(VertexBuffer buffer, Vec3f p, float r, float g, float b, float a, float s) {
		addLine(buffer, new Vec3f(p.x - s, p.y, p.z), new Vec3f(p.x + s, p.y, p.z), r, g, b, a);
		addLine(buffer, new Vec3f(p.x, p.y - s, p.z), new Vec3f(p.x, p.y + s, p.z), r, g, b, a);
		addLine(buffer, new Vec3f(p.x, p.y, p.z - s), new Vec3f(p.x, p.y, p.z + s), r, g, b, a);
	}

	private void addLine(VertexBuffer buffer, Vec3f from, Vec3f to, float r, float g, float b, float a) {
		buffer.pos(from.x, from.y, from.z).color(r, g, b, a).normal(0, 1, 0).endVertex();
		buffer.pos(to.x, to.y, to.z).color(r, g, b, a).normal(0, 1, 0).endVertex();
	}

	@Override
	public void keyPressed(KeyboardEvent event) {
		super.keyPressed(event);
		frame.getKeybindHandler().registerKeybind(Keybinds.TOGGLE_HIDDEN_ACTION, editor::switchAnimShow);
	}
}
