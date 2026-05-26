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
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.editor.DisplayItem;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimationDisplayData;
import com.tom.cpm.shared.editor.anim.AnimationDisplayData.Type;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.anim.IElem;
import com.tom.cpm.shared.editor.elements.ModelElement;
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
	private List<Vec3f> movementTrackCache;
	private ModelElement movementTrackElementCache;
	private EditorAnim movementTrackAnimCache;
	private int movementTrackHashCache;

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
		List<Vec3f> movementTrack = drawMovementTrack ? buildMovementTrack(stack, partialTicks, trackElement) : null;
		int displayFrame = getDisplayedFrameIndex();
		if(renderPreviousFrame && editor.selectedAnim != null) {
			List<AnimFrame> frames = editor.selectedAnim.getFrames();
			AnimFrame originalFrame = editor.selectedAnim.getSelectedFrame();
			boolean originalPlayFullAnim = editor.playFullAnim;
			int selectedFrame = editor.selectedAnim.getSelectedFrameIndex();
			int previousFrame = (selectedFrame - 1 + frames.size()) % frames.size();
			editor.definition.renderingPanel = this;
			editor.definition.outlineOnly = true;
			editor.playFullAnim = false;
			editor.selectedAnim.setSelectedFrame(frames.get(previousFrame));
			renderModel(stack, filter.filter(buf), partialTicks);
			editor.selectedAnim.setSelectedFrame(originalFrame);
			editor.playFullAnim = originalPlayFullAnim;
			editor.definition.renderingPanel = null;
			editor.definition.outlineOnly = false;
		}
		super.render(stack, buf, partialTicks);
		if(drawMovementTrack) {
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

	private List<Vec3f> buildMovementTrack(MatrixStack stack, float partialTicks, ModelElement element) {
		EditorAnim anim = editor.selectedAnim;
		int hash = getMovementTrackHash(anim);
		if(movementTrackCache != null && movementTrackElementCache == element && movementTrackAnimCache == anim && movementTrackHashCache == hash)
			return movementTrackCache;

		List<Vec3f> positions = captureMovementTrack(stack, partialTicks, anim, element);
		movementTrackCache = positions;
		movementTrackElementCache = element;
		movementTrackAnimCache = anim;
		movementTrackHashCache = hash;
		return positions;
	}

	private List<Vec3f> captureMovementTrack(MatrixStack stack, float partialTicks, EditorAnim anim, ModelElement element) {
		List<AnimFrame> frames = anim.getFrames();
		AnimFrame originalFrame = anim.getSelectedFrame();
		boolean originalPlayFullAnim = editor.playFullAnim;
		boolean originalOutlineOnly = editor.definition.outlineOnly;
		ViewportPanel originalRenderingPanel = editor.definition.renderingPanel;
		VBuffers nullBuffers = new VBuffers(t -> VertexBuffer.NULL, VertexBuffer.NULL);
		List<Vec3f> positions = new ArrayList<>(frames.size() + (anim.loop ? 1 : 0));

		editor.definition.renderingPanel = this;
		editor.definition.outlineOnly = false;
		editor.playFullAnim = false;
		for(AnimFrame frame : frames) {
			anim.setSelectedFrame(frame);
			renderModel(stack, nullBuffers, partialTicks);
			Vec3f position = getElementPosition(element);
			positions.add(position == null ? null : new Vec3f(position));
		}
		if(anim.loop && !positions.isEmpty() && positions.get(0) != null)
			positions.add(new Vec3f(positions.get(0)));

		anim.setSelectedFrame(originalFrame);
		editor.playFullAnim = originalPlayFullAnim;
		editor.definition.renderingPanel = originalRenderingPanel;
		editor.definition.outlineOnly = originalOutlineOnly;
		return positions;
	}

	private int getMovementTrackHash(EditorAnim anim) {
		List<AnimFrame> frames = anim.getFrames();
		int hash = 31 * frames.size() + anim.duration;
		hash = 31 * hash + (anim.loop ? 1 : 0);
		hash = 31 * hash + anim.intType.ordinal();
		for(AnimFrame frame : frames) {
			hash = 31 * hash + frame.getComponents().size();
			for(IElem data : frame.getComponents().values()) {
				hash = appendVecHash(hash, data.getPosition());
				hash = appendVecHash(hash, data.getRotation());
				hash = appendVecHash(hash, data.getScale());
				hash = 31 * hash + (data.isVisible() ? 1 : 0);
			}
		}
		return hash;
	}

	private int appendVecHash(int hash, Vec3f vec) {
		if(vec == null)return 31 * hash;
		hash = 31 * hash + Float.floatToIntBits(vec.x);
		hash = 31 * hash + Float.floatToIntBits(vec.y);
		hash = 31 * hash + Float.floatToIntBits(vec.z);
		return hash;
	}

	// Three-layer movement guide like Blockbench/Maya:
	//   Layer 1 – solid trace of the full animation path (whole movement)
	//   Layer 2 – brighter past-path when showPastMovementTrack is on
	//   Layer 3 – warm amber current-keyframe segment with endpoint markers
	private void drawMovementTracks(VertexBuffer buffer, List<Vec3f> positions, int selectedFrame, int frameCount) {
		if(positions.size() < 2)return;
		boolean highContrast = editor.highContrastMovementTrack.get();

		// Layer 1: full sampled animation path, solid low-opacity cyan guide
		for(int i = 1; i < positions.size(); i++) {
			Vec3f from = positions.get(i - 1);
			Vec3f to   = positions.get(i);
			if(from == null || to == null)continue;
			float dx = to.x - from.x;
			float dy = to.y - from.y;
			float dz = to.z - from.z;
			if(dx*dx + dy*dy + dz*dz < 0.000001f)continue;
			if(highContrast)addLine(buffer, new Vec3f(from.x, from.y + 0.006f, from.z), new Vec3f(to.x, to.y + 0.006f, to.z), 0, 0, 0, 0.95f);
			addLine(buffer, from, to, highContrast ? 0.05f : 0.10f, highContrast ? 0.65f : 0.55f, highContrast ? 1f : 0.95f, highContrast ? 0.95f : 0.68f);
		}

		int currentFrame = Math.max(0, Math.min(frameCount - 1, selectedFrame));
		int currentEndIdx = frameToTrackIndex(currentFrame, positions.size(), frameCount);

		// Layer 2: past-path overlay – all segments before the current frame
		if(editor.showPastMovementTrack.get() && currentEndIdx > 0) {
			for(int i = 1; i <= currentEndIdx; i++) {
				Vec3f from = positions.get(i - 1);
				Vec3f to   = positions.get(i);
				if(from == null || to == null)continue;
				float dx = to.x - from.x;
				float dy = to.y - from.y;
				float dz = to.z - from.z;
				if(dx*dx + dy*dy + dz*dz < 0.000001f)continue;
				if(highContrast)addLine(buffer, new Vec3f(from.x, from.y + 0.006f, from.z), new Vec3f(to.x, to.y + 0.006f, to.z), 0, 0, 0, 0.95f);
				addLine(buffer, from, to, highContrast ? 0.85f : 0.68f, highContrast ? 0.92f : 0.78f, highContrast ? 1f : 0.96f, highContrast ? 0.90f : 0.70f);
			}
			if(positions.get(currentEndIdx) != null)
				drawPoint(buffer, positions.get(currentEndIdx), highContrast ? 0.9f : 0.75f, highContrast ? 0.95f : 0.82f, highContrast ? 1f : 1f, highContrast ? 1f : 0.85f, highContrast ? 0.04f : 0.03f);
		}

		// Layer 3: current keyframe transition – brightest warm orange
		int prevFrame = currentFrame - 1;
		if(prevFrame < 0 && editor.selectedAnim.loop)prevFrame = frameCount - 1;
		if(prevFrame >= 0) {
			int segStart = frameToTrackIndex(prevFrame, positions.size(), frameCount);
			int segEnd   = frameToTrackIndex(currentFrame, positions.size(), frameCount);
			if(segStart >= 0 && segEnd >= 0 && segStart != segEnd && positions.get(segStart) != null && positions.get(segEnd) != null) {
				if(highContrast) {
					addLine(buffer, new Vec3f(positions.get(segStart).x, positions.get(segStart).y + 0.006f, positions.get(segStart).z), new Vec3f(positions.get(segEnd).x, positions.get(segEnd).y + 0.006f, positions.get(segEnd).z), 0, 0, 0, 0.95f);
					addLine(buffer, new Vec3f(positions.get(segStart).x, positions.get(segStart).y - 0.006f, positions.get(segStart).z), new Vec3f(positions.get(segEnd).x, positions.get(segEnd).y - 0.006f, positions.get(segEnd).z), 0, 0, 0, 0.95f);
				}
				addLine(buffer, positions.get(segStart), positions.get(segEnd), 1f, highContrast ? 0.70f : 0.60f, highContrast ? 0.10f : 0.15f, 1f);
				drawPoint(buffer, positions.get(segStart), 1f, highContrast ? 0.80f : 0.72f, highContrast ? 0.25f : 0.30f, highContrast ? 1f : 0.88f, highContrast ? 0.04f : 0.03f);
				drawPoint(buffer, positions.get(segEnd),   1f, highContrast ? 0.72f : 0.62f, highContrast ? 0.06f : 0.10f, 1f, highContrast ? 0.055f : 0.04f);
			}
		}
	}

	private int frameToTrackIndex(int frame, int pointCount, int frameCount) {
		if(frameCount <= 0 || pointCount <= 0)return -1;
		if(pointCount >= frameCount)return Math.max(0, Math.min(pointCount - 1, frame));
		if(frameCount <= 1)return 0;
		return Math.max(0, Math.min(pointCount - 1, Math.round(frame / (float) (frameCount - 1) * (pointCount - 1))));
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
