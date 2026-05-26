package com.tom.cpm.shared.editor.gui;

import java.util.List;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.MouseEvent;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Tooltip;
import com.tom.cpl.math.Box;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.animation.InterpolatorChannel;
import com.tom.cpm.shared.animation.interpolator.Interpolator;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.FormatLimits;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.AnimFrame.FrameData;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.anim.IElem;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.tree.VecType;

public class AnimTimelinePanel extends Panel {
	private static final int HEADER_H = 20;
	private static final int BOTTOM_H = 14;
	private static final int LABEL_W = 76;
	private static final int MIN_H = 86;
	private static final int MIN_CURVE_H = 128;
	private static final int MAX_H = 240;
	private static final int AXIS_FILTER_W = 18;
	private static final int AXIS_FILTER_H = 12;
	private static final int CURVE_SCALE_W = 34;
	private static final int CURVE_NODE_HIT_R = 5;
	private static final float MIN_ZOOM = 0.25f;
	private static final float MAX_ZOOM = 8;
	private static final int[] AXIS_COLORS = {0xffff5555, 0xff55dd55, 0xff6699ff};
	private static final String[] AXIS_LABELS = {"X", "Y", "Z"};
	public static final int PANEL_H = MIN_H;
	public static final int COLLAPSED_H = HEADER_H;

	private final Editor editor;
	private final Button toggleBtn, prevBtn, playBtn, nextBtn, addKeyframeBtn, zoomOutBtn, zoomInBtn;
	private final Label frameLabel, durationLabel, zoomLabel;
	private boolean collapsed, resizing, draggingCursor;
	private int resizeStartY, resizeStartH, dragTrackX, dragTrackW;
	private long lastTrackClick;
	private int lastClickedTrack = -1;
	private Runnable layoutListener;
	private CurveDragState curveDrag;
	private float timelineScroll = Float.NaN;

	private static class CurveScale {
		private final float min;
		private final float max;

		private CurveScale(float min, float max) {
			this.min = min;
			this.max = max;
		}
	}

	private static class CurveNodeHit {
		private final int track;
		private final int axis;
		private final int frameIndex;
		private final int nodeX;
		private final int nodeY;
		private final int trackX;
		private final int trackW;
		private final int plotY;
		private final int plotH;
		private final float value;

		private CurveNodeHit(int track, int axis, int frameIndex, int nodeX, int nodeY, int trackX, int trackW, int plotY, int plotH, float value) {
			this.track = track;
			this.axis = axis;
			this.frameIndex = frameIndex;
			this.nodeX = nodeX;
			this.nodeY = nodeY;
			this.trackX = trackX;
			this.trackW = trackW;
			this.plotY = plotY;
			this.plotH = plotH;
			this.value = value;
		}
	}

	private static class CurveDragState {
		private final int track;
		private final int axis;
		private final int trackX;
		private final int trackW;
		private final int plotY;
		private final int plotH;
		private final Vec3f originalValue;
		private Vec3f currentValue;

		private CurveDragState(int track, int axis, int trackX, int trackW, int plotY, int plotH, Vec3f originalValue) {
			this.track = track;
			this.axis = axis;
			this.trackX = trackX;
			this.trackW = trackW;
			this.plotY = plotY;
			this.plotH = plotH;
			this.originalValue = originalValue;
			this.currentValue = new Vec3f(originalValue);
		}
	}

	public AnimTimelinePanel(IGui gui, Editor editor, int width) {
		super(gui);
		this.editor = editor;
		setBounds(new Box(0, 0, width, PANEL_H));
		setBackgroundColor(gui.getColors().panel_background);

		toggleBtn = new Button(gui, "v", this::toggleCollapse);
		toggleBtn.setBounds(new Box(3, 2, 16, 16));
		addElement(toggleBtn);

		Label titleLabel = new Label(gui, gui.i18nFormat("label.cpm.timeline"));
		titleLabel.setBounds(new Box(24, 5, 80, 10));
		addElement(titleLabel);

		frameLabel = new Label(gui, "");
		frameLabel.setBounds(new Box(104, 5, 160, 10));
		addElement(frameLabel);

		prevBtn = new Button(gui, "<", editor::animPrevFrm);
		prevBtn.setBounds(new Box(268, 1, 18, 18));
		addElement(prevBtn);

		playBtn = new Button(gui, "Play", this::togglePlay);
		playBtn.setBounds(new Box(288, 1, 34, 18));
		addElement(playBtn);

		nextBtn = new Button(gui, ">", editor::animNextFrm);
		nextBtn.setBounds(new Box(324, 1, 18, 18));
		addElement(nextBtn);

		addKeyframeBtn = new Button(gui, "+", () -> editor.addNewAnimFrame(!gui.isShiftDown()));
		addKeyframeBtn.setBounds(new Box(344, 1, 18, 18));
		addKeyframeBtn.setTooltip(new Tooltip(gui.getFrame(), gui.i18nFormat("tooltip.cpm.anim.newFrame")));
		addElement(addKeyframeBtn);

		zoomOutBtn = new Button(gui, "-", () -> changeZoom(1 / 1.25f));
		zoomOutBtn.setBounds(new Box(width - 238, 1, 18, 18));
		addElement(zoomOutBtn);

		zoomLabel = new Label(gui, "");
		zoomLabel.setBounds(new Box(width - 216, 5, 42, 10));
		addElement(zoomLabel);

		zoomInBtn = new Button(gui, "+", () -> changeZoom(1.25f));
		zoomInBtn.setBounds(new Box(width - 170, 1, 18, 18));
		addElement(zoomInBtn);

		durationLabel = new Label(gui, "");
		durationLabel.setBounds(new Box(width - 145, 5, 135, 10));
		addElement(durationLabel);

		editor.setAnimFrame.add(idx -> updateHeader());
		editor.setSelAnim.add(a -> updateHeader());
		editor.setAnimDuration.add(d -> updateHeader());
		editor.setAnimPlay.add(v -> {
			playBtn.setText(v ? "Stop" : "Play");
			updateHeader();
		});
		updateHeader();
	}

	public void setLayoutListener(Runnable layoutListener) {
		this.layoutListener = layoutListener;
	}

	public int getPreferredHeight() {
		int minHeight = editor.animTimelineCurveTrack == -1 ? MIN_H : MIN_CURVE_H;
		return collapsed ? COLLAPSED_H : Math.max(minHeight, Math.min(MAX_H, editor.animTimelineHeight));
	}

	private void updateHeader() {
		EditorAnim anim = editor.selectedAnim;
		boolean enabled = anim != null;
		prevBtn.setEnabled(enabled);
		playBtn.setEnabled(enabled);
		nextBtn.setEnabled(enabled);
		addKeyframeBtn.setEnabled(enabled);
		if(enabled) {
			int idx = anim.getSelectedFrameIndex();
			int total = anim.getFrames().size();
			frameLabel.setText(gui.i18nFormat("label.cpm.timeline_frame_info", idx + 1, total));
			durationLabel.setText(gui.i18nFormat("label.cpm.timeline_duration", anim.duration));
		} else {
			frameLabel.setText("");
			durationLabel.setText("");
		}
		zoomLabel.setText((int)(editor.animTimelineZoom * 100) + "%");
	}

	private void toggleCollapse() {
		collapsed = !collapsed;
		toggleBtn.setText(collapsed ? "^" : "v");
		if(layoutListener != null)layoutListener.run();
	}

	private void togglePlay() {
		editor.playFullAnim = !editor.playFullAnim;
		editor.playStartTime = MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().getTime();
		editor.setAnimPlay.accept(editor.playFullAnim);
	}

	private void changeZoom(float mul) {
		editor.animTimelineZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, editor.animTimelineZoom * mul));
		if(!Float.isNaN(timelineScroll))timelineScroll = Math.max(0, timelineScroll);
		updateHeader();
	}

	private void changeZoomAround(EditorAnim anim, int numFrames, int mouseX, float mul) {
		Box b = getBounds();
		int visibleX = b.x + LABEL_W;
		int visibleW = b.w - LABEL_W - 14;
		int oldTrackW = getTrackWidth(visibleW);
		int focusX = getCursorTrackX(anim, numFrames, oldTrackW);
		int oldScroll = getTrackScroll(focusX, visibleW, oldTrackW);
		int anchorOffset = Math.max(0, Math.min(visibleW, mouseX - visibleX));
		float progress = (oldScroll + anchorOffset) / (float) Math.max(1, oldTrackW);
		changeZoom(mul);
		int newTrackW = getTrackWidth(visibleW);
		if(newTrackW <= visibleW) {
			timelineScroll = 0;
			return;
		}
		float newCoord = progress * newTrackW;
		timelineScroll = Math.max(0, Math.min(newTrackW - visibleW, newCoord - anchorOffset));
	}

	@Override
	public void draw(MouseEvent event, float partialTicks) {
		super.draw(event, partialTicks);
		gui.pushMatrix();
		gui.setPosOffset(getBounds());
		gui.setupCut();
		drawTimelineContent(event);
		gui.popMatrix();
		gui.setupCut();
	}

	private void drawTimelineContent(MouseEvent event) {
		Box b = new Box(0, 0, bounds.w, bounds.h);
		int mouseX = event.x - bounds.x;
		int mouseY = event.y - bounds.y;
		gui.drawBox(b.x, b.y, b.w, 2, 0xff333333);
		gui.drawBox(b.x, b.y + HEADER_H - 1, b.w, 1, 0xff3a3a3a);
		if(collapsed)return;

		EditorAnim anim = editor.selectedAnim;
		if(anim == null)return;

		List<AnimFrame> frames = anim.getFrames();
		int numFrames = frames.size();
		if(numFrames == 0)return;

		int visibleX = b.x + LABEL_W;
		int visibleW = b.w - LABEL_W - 14;
		int trackW = getTrackWidth(visibleW);
		int selIdx = anim.getSelectedFrameIndex();
		int focusX = getCursorTrackX(anim, numFrames, trackW);
		int trackX = getTrackX(visibleX, visibleW, trackW, focusX);
		int[] rowHeights = getRowHeights();
		int[] rowYs = getRowYs(b.y + HEADER_H + 3, rowHeights);
		int firstRowY = b.y + HEADER_H + 3;
		int selX = trackX + getFrameX(selIdx, numFrames, trackW);
		int cursorX = trackX + getCursorTrackX(anim, numFrames, trackW);
		int totalTrackH = rowHeights[0] + rowHeights[1] + rowHeights[2];
		ModelElement selectedElement = editor.getSelectedElement();
		if(editor.playFullAnim)frameLabel.setText(gui.i18nFormat("label.cpm.timeline_frame_info", getPlaybackFrameIndex(anim, numFrames) + 1, numFrames));

		drawRow(anim, frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, rowYs[0], rowHeights[0], gui.i18nFormat("label.cpm.position"), 0xffff6655, 0, mouseX, mouseY);
		drawRow(anim, frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, rowYs[1], rowHeights[1], gui.i18nFormat("label.cpm.rotation"), 0xff77a7ff, 1, mouseX, mouseY);
		drawRow(anim, frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, rowYs[2], rowHeights[2], gui.i18nFormat("label.cpm.scale"), 0xff7bd46d, 2, mouseX, mouseY);

		if(selX >= visibleX && selX <= visibleX + visibleW)gui.drawBox(selX, firstRowY - 2, 1, totalTrackH + 3, 0x88ffffff);
		drawPlayhead(Math.max(visibleX, Math.min(visibleX + visibleW, cursorX)), firstRowY - 2, totalTrackH + 3);
		drawFrameNumbers(numFrames, selIdx, trackX, trackW, visibleX, visibleW, firstRowY + totalTrackH - 1);
	}

	@Override
	public void mouseClick(MouseEvent event) {
		Box b = getBounds();
		if(event.isHovered(new Box(b.x, b.y, b.w, 4))) {
			resizing = true;
			resizeStartY = event.y;
			resizeStartH = editor.animTimelineHeight;
			event.consume();
			return;
		}
		super.mouseClick(event);
		if(collapsed || event.isConsumed())return;

		EditorAnim anim = editor.selectedAnim;
		if(anim == null)return;

		List<AnimFrame> frames = anim.getFrames();
		int numFrames = frames.size();
		if(numFrames == 0)return;

		int visibleX = b.x + LABEL_W;
		int visibleW = b.w - LABEL_W - 14;
		int trackW = getTrackWidth(visibleW);
		int selIdx = anim.getSelectedFrameIndex();
		int focusX = getCursorTrackX(anim, numFrames, trackW);
		int trackX = getTrackX(visibleX, visibleW, trackW, focusX);
		int[] rowHeights = getRowHeights();
		int firstRowY = b.y + HEADER_H + 3;
		int clickedTrack = getTrackAt(event.y, firstRowY, rowHeights);
		ModelElement selectedElement = editor.getSelectedElement();
		if(clickedTrack != -1 && editor.animTimelineCurveTrack == clickedTrack) {
			int axis = getAxisFilterAt(event.x, event.y, b.x + 8, getAxisFilterY(firstRowY, rowHeights, clickedTrack));
			if(axis != -1) {
				toggleAxisFilter(clickedTrack, axis);
				event.consume();
				return;
			}
			if(event.btn == 0 && selectedElement != null) {
				int rowY = getTrackRowY(firstRowY, rowHeights, clickedTrack);
				CurveNodeHit hit = findCurveNodeHit(event.x, event.y, frames, selectedElement, numFrames, trackX, trackW, visibleX, visibleW, rowY, rowHeights[clickedTrack], clickedTrack);
				if(hit != null) {
					startCurveDrag(anim, frames, selectedElement, hit);
					event.consume();
					return;
				}
			}
		}
		if(clickedTrack != -1 && event.x >= b.x && event.x < visibleX) {
			long now = System.currentTimeMillis();
			if(lastClickedTrack == clickedTrack && now - lastTrackClick < 400) {
				editor.animTimelineCurveTrack = editor.animTimelineCurveTrack == clickedTrack ? -1 : clickedTrack;
				if(editor.animTimelineCurveTrack != -1 && editor.animTimelineHeight < MIN_CURVE_H) {
					editor.animTimelineHeight = MIN_CURVE_H;
					if(layoutListener != null)layoutListener.run();
				}
			}
			lastClickedTrack = clickedTrack;
			lastTrackClick = now;
			event.consume();
			return;
		}
		int totalTrackH = rowHeights[0] + rowHeights[1] + rowHeights[2];
		if(event.isHovered(new Box(visibleX, firstRowY - 2, visibleW, totalTrackH + BOTTOM_H)) && event.x >= trackX - 6 && event.x <= trackX + trackW + 6) {
			draggingCursor = true;
			dragTrackX = trackX;
			dragTrackW = trackW;
			scrubCursor(event, anim, frames, trackX, trackW);
			event.consume();
		}
	}

	@Override
	public void mouseDrag(MouseEvent event) {
		if(curveDrag != null) {
			updateCurveDrag(event);
			event.consume();
			return;
		}
		if(resizing) {
			int minHeight = editor.animTimelineCurveTrack == -1 ? MIN_H : MIN_CURVE_H;
			editor.animTimelineHeight = Math.max(minHeight, Math.min(MAX_H, resizeStartH + resizeStartY - event.y));
			if(layoutListener != null)layoutListener.run();
			event.consume();
			return;
		}
		if(draggingCursor) {
			EditorAnim anim = editor.selectedAnim;
			if(anim != null) {
				List<AnimFrame> frames = anim.getFrames();
				if(!frames.isEmpty())scrubCursor(event, anim, frames, dragTrackX, dragTrackW);
			}
			event.consume();
			return;
		}
		super.mouseDrag(event);
	}

	@Override
	public void mouseWheel(MouseEvent event) {
		if(!collapsed && event.isHovered(getBounds()) && editor.selectedAnim != null) {
			changeZoomAround(editor.selectedAnim, editor.selectedAnim.getFrames().size(), event.x, event.btn > 0 ? 1.25f : 1 / 1.25f);
			event.consume();
			return;
		}
		super.mouseWheel(event);
	}

	@Override
	public void mouseRelease(MouseEvent event) {
		if(curveDrag != null) {
			finishCurveDrag();
			event.consume();
			return;
		}
		if(resizing) {
			resizing = false;
			event.consume();
			return;
		}
		if(draggingCursor) {
			draggingCursor = false;
			event.consume();
			return;
		}
		super.mouseRelease(event);
	}

	private void drawRow(EditorAnim anim, List<AnimFrame> frames, ModelElement elem, int numFrames, int selIdx, int trackX, int trackW, int visibleX, int visibleW, int rowY, int rowH, String label, int color, int track, int mouseX, int mouseY) {
		boolean curve = editor.animTimelineCurveTrack == track;
		int lineY = curve ? rowY + 11 : rowY + rowH / 2;
		gui.drawBox(bounds.x, rowY - 1, bounds.w, 1, 0xff555555);
		gui.drawText(bounds.x + 8, curve ? rowY + 5 : rowY + Math.max(3, rowH / 2 - 5), label, curve ? 0xffffd740 : (elem != null ? 0xffffffff : 0xffbbbbbb));
		gui.drawBox(visibleX, lineY, visibleW, 2, curve ? 0xff353535 : 0xff454545);
		if(curve && elem != null) {
			int curveY = lineY + 7;
			int curveH = Math.max(8, rowY + rowH - curveY - 4);
			drawAxisFilter(track, bounds.x + 8, curveY);
			gui.drawBox(visibleX, curveY, visibleW, curveH, 0xff242424);
			CurveScale scale = computeCurveScale(elem, numFrames, trackW, track);
			if(scale != null) {
				int plotY = curveY + 2;
				int plotH = curveH - 4;
				gui.drawBox(visibleX, curveY, Math.min(CURVE_SCALE_W, visibleW), curveH, 0xff1f1f1f);
				gui.drawBox(Math.min(visibleX + CURVE_SCALE_W - 1, visibleX + visibleW - 1), plotY, 1, plotH, 0xff3c3c3c);
				gui.drawBox(visibleX, plotY + plotH / 2, visibleW, 1, 0xff3c3c3c);
				drawCurveScale(scale, visibleX + 3, plotY, plotH, track);
				drawCurve(frames, elem, numFrames, trackX, trackW, visibleX, visibleW, plotY, plotH, track, scale);
				CurveNodeHit hoveredNode = findCurveNodeHit(mouseX, mouseY, frames, elem, numFrames, trackX, trackW, visibleX, visibleW, rowY, rowH, track);
				if(hoveredNode != null) {
					drawCurveNodeHighlight(hoveredNode);
					drawCurveNodeTooltip(anim, hoveredNode, mouseX, mouseY, numFrames);
				}
			}
		}
		int prevKeyX = -1;
		for(int i = 0; i < numFrames; i++) {
			int fx = trackX + getFrameX(i, numFrames, trackW);
			if(fx >= visibleX && fx <= visibleX + visibleW)gui.drawBox(fx, lineY - 3, 1, 8, 0xff555555);
			boolean key = elem != null && hasTrackChanges(frames.get(i), elem, track);
			if(key) {
				if(prevKeyX >= 0)drawDashedLine(prevKeyX, lineY, fx, lineY, color & 0x88ffffff, visibleX, visibleW);
				if(fx >= visibleX && fx <= visibleX + visibleW)drawKey(fx, lineY, i == selIdx ? 0xffffd740 : color, i == selIdx);
				prevKeyX = fx;
			} else if(i == selIdx && fx >= visibleX && fx <= visibleX + visibleW) {
				drawKey(fx, lineY, 0xff888888, false);
			}
		}
	}

	private void drawCurve(List<AnimFrame> frames, ModelElement elem, int numFrames, int trackX, int trackW, int visibleX, int visibleW, int rowY, int rowH, int track, CurveScale scale) {
		if(numFrames < 2)return;
		InterpolatorChannel[] channels = getChannels(track);
		int axisMask = getAxisMask(track);
		for(int axis = 0; axis < 3; axis++) {
			if((axisMask & (1 << axis)) == 0)continue;
			Interpolator interpolator = createInterpolator(elem, channels[axis]);
			int samples = Math.max(12, Math.min(160, trackW));
			int px = -1, py = -1;
			for(int i = 0; i <= samples; i++) {
				float progress = i / (float) samples;
				int x = trackX + Math.round(progress * trackW);
				float v = toDisplayValue(track, interpolator.applyAsDouble(progress * numFrames));
				int y = valueToCurveY(v, rowY, rowH, scale);
				if(px != -1)drawSolidLine(px, py, x, y, AXIS_COLORS[axis], visibleX, visibleW);
				px = x;
				py = y;
			}

			for(int i = 0; i < numFrames; i++) {
				FrameData data = getData(frames.get(i), elem);
				if(data == null || !hasTrackChanges(frames.get(i), elem, track))continue;
				int x = trackX + getFrameX(i, numFrames, trackW);
				float v = getTrackAxisValue(data, track, axis);
				int y = valueToCurveY(v, rowY, rowH, scale);
				if(x >= visibleX && x <= visibleX + visibleW)gui.drawBox(x - 1, y - 1, 3, 3, AXIS_COLORS[axis]);
			}
		}
	}

	private CurveScale computeCurveScale(ModelElement elem, int numFrames, int trackW, int track) {
		InterpolatorChannel[] channels = getChannels(track);
		int axisMask = getAxisMask(track);
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		int samples = Math.max(12, Math.min(160, trackW));
		for(int axis = 0; axis < 3; axis++) {
			if((axisMask & (1 << axis)) == 0)continue;
			Interpolator interpolator = createInterpolator(elem, channels[axis]);
			for(int i = 0; i <= samples; i++) {
				float progress = i / (float) samples;
				float value = toDisplayValue(track, interpolator.applyAsDouble(progress * numFrames));
				min = Math.min(min, value);
				max = Math.max(max, value);
			}
		}
		if(min == Float.MAX_VALUE)return null;
		if(Math.abs(max - min) < 0.001f) {
			max += 1;
			min -= 1;
		}
		return new CurveScale(min, max);
	}

	private void drawCurveScale(CurveScale scale, int x, int y, int h, int track) {
		gui.drawText(x, y - 1, formatCurveValue(scale.max, track), 0xffbfbfbf);
		gui.drawText(x, y + h / 2 - 5, formatCurveValue((scale.min + scale.max) / 2f, track), 0xff9f9f9f);
		gui.drawText(x, y + h - 9, formatCurveValue(scale.min, track), 0xffbfbfbf);
	}

	private void drawAxisFilter(int track, int x, int y) {
		int mask = getAxisMask(track);
		for(int axis = 0; axis < 3; axis++) {
			boolean active = (mask & (1 << axis)) != 0;
			int bx = x + axis * (AXIS_FILTER_W + 3);
			gui.drawBox(bx, y, AXIS_FILTER_W, AXIS_FILTER_H, active ? AXIS_COLORS[axis] : 0xff555555);
			gui.drawBox(bx + 1, y + 1, AXIS_FILTER_W - 2, AXIS_FILTER_H - 2, active ? 0xff303030 : 0xff222222);
			String label = AXIS_LABELS[axis];
			gui.drawText(bx + (AXIS_FILTER_W - gui.textWidth(label)) / 2, y + 2, label, active ? AXIS_COLORS[axis] : 0xff888888);
		}
	}

	private int getAxisFilterY(int firstRowY, int[] rowHeights, int track) {
		return getTrackRowY(firstRowY, rowHeights, track) + 18;
	}

	private int getTrackRowY(int firstRowY, int[] rowHeights, int track) {
		int rowY = firstRowY;
		for(int i = 0; i < track; i++)rowY += rowHeights[i];
		return rowY;
	}

	private int getAxisFilterAt(int x, int y, int filterX, int filterY) {
		if(y < filterY || y >= filterY + AXIS_FILTER_H)return -1;
		for(int axis = 0; axis < 3; axis++) {
			int bx = filterX + axis * (AXIS_FILTER_W + 3);
			if(x >= bx && x < bx + AXIS_FILTER_W)return axis;
		}
		return -1;
	}

	private int getAxisMask(int track) {
		if(track < 0 || track >= editor.animTimelineAxisMask.length)return 7;
		int mask = editor.animTimelineAxisMask[track] & 7;
		if(mask == 0) {
			editor.animTimelineAxisMask[track] = 7;
			return 7;
		}
		return mask;
	}

	private void toggleAxisFilter(int track, int axis) {
		if(track < 0 || track >= editor.animTimelineAxisMask.length || axis < 0 || axis > 2)return;
		int mask = getAxisMask(track);
		int bit = 1 << axis;
		if((mask & bit) != 0) {
			if(Integer.bitCount(mask) > 1)mask &= ~bit;
		} else mask |= bit;
		editor.animTimelineAxisMask[track] = mask & 7;
	}

	private CurveNodeHit findCurveNodeHit(int mouseX, int mouseY, List<AnimFrame> frames, ModelElement elem, int numFrames, int trackX, int trackW, int visibleX, int visibleW, int rowY, int rowH, int track) {
		if(numFrames <= 0)return null;
		CurveScale scale = computeCurveScale(elem, numFrames, trackW, track);
		if(scale == null)return null;
		int plotY = rowY + 20;
		int plotH = Math.max(4, rowH - 26);
		int axisMask = getAxisMask(track);
		int bestDistSq = CURVE_NODE_HIT_R * CURVE_NODE_HIT_R + 1;
		CurveNodeHit best = null;
		for(int axis = 0; axis < 3; axis++) {
			if((axisMask & (1 << axis)) == 0)continue;
			for(int i = 0; i < numFrames; i++) {
				FrameData data = getData(frames.get(i), elem);
				if(data == null || !hasTrackChanges(frames.get(i), elem, track))continue;
				int x = trackX + getFrameX(i, numFrames, trackW);
				if(x < visibleX || x > visibleX + visibleW)continue;
				float value = getTrackAxisValue(data, track, axis);
				int y = valueToCurveY(value, plotY, plotH, scale);
				int dx = mouseX - x;
				int dy = mouseY - y;
				int distSq = dx * dx + dy * dy;
				if(distSq <= CURVE_NODE_HIT_R * CURVE_NODE_HIT_R && distSq < bestDistSq) {
					bestDistSq = distSq;
					best = new CurveNodeHit(track, axis, i, x, y, trackX, trackW, plotY, plotH, value);
				}
			}
		}
		return best;
	}

	private void drawCurveNodeHighlight(CurveNodeHit hit) {
		int color = AXIS_COLORS[hit.axis];
		gui.drawBox(hit.nodeX - 4, hit.nodeY - 4, 9, 9, 0xffffffff);
		gui.drawBox(hit.nodeX - 3, hit.nodeY - 3, 7, 7, 0xff202020);
		gui.drawBox(hit.nodeX - 2, hit.nodeY - 2, 5, 5, color);
	}

	private void drawCurveNodeTooltip(EditorAnim anim, CurveNodeHit hit, int mouseX, int mouseY, int numFrames) {
		int timeMs = getFrameTimeMs(anim, hit.frameIndex, numFrames);
		String text = AXIS_LABELS[hit.axis] + ": " + formatCurveValue(hit.value, hit.track) + "  f" + (hit.frameIndex + 1) + "  " + timeMs + "ms";
		int tw = gui.textWidth(text);
		int tx = Math.max(2, Math.min(bounds.w - tw - 8, mouseX + 12));
		int ty = Math.max(HEADER_H, Math.min(bounds.h - 14, mouseY + 12));
		gui.drawBox(tx, ty, tw + 6, 12, 0xee101010);
		gui.drawBox(tx, ty, tw + 6, 1, AXIS_COLORS[hit.axis]);
		gui.drawText(tx + 3, ty + 2, text, 0xffffffff);
	}

	private int getFrameTimeMs(EditorAnim anim, int frameIndex, int numFrames) {
		if(anim == null || numFrames <= 1)return 0;
		return Math.round(frameIndex / (float) (numFrames - 1) * anim.duration);
	}

	private void startCurveDrag(EditorAnim anim, List<AnimFrame> frames, ModelElement elem, CurveNodeHit hit) {
		if(editor.playFullAnim) {
			editor.playFullAnim = false;
			editor.setAnimPlay.accept(false);
		}
		anim.setSelectedFrame(frames.get(hit.frameIndex));
		editor.updateGui();
		FrameData data = getData(frames.get(hit.frameIndex), elem);
		if(data == null)return;
		anim.beginDrag();
		curveDrag = new CurveDragState(hit.track, hit.axis, hit.trackX, hit.trackW, hit.plotY, hit.plotH, getTrackVector(data, hit.track));
	}

	private void updateCurveDrag(MouseEvent event) {
		if(curveDrag == null || editor.selectedAnim == null)return;
		moveCurveDragFrame(event.x);
		CurveScale scale = computeCurveScale(editor.getSelectedElement(), editor.selectedAnim.getFrames().size(), curveDrag.trackW, curveDrag.track);
		if(scale == null)return;
		float value = curveYToValue(event.y, curveDrag.plotY, curveDrag.plotH, scale);
		value = clampCurveValue(curveDrag.track, value);
		Vec3f vec = new Vec3f(curveDrag.currentValue);
		setAxisValue(vec, curveDrag.axis, value);
		curveDrag.currentValue = vec;
		applyCurveDragValue(curveDrag.track, vec, true);
	}

	private void moveCurveDragFrame(int mouseX) {
		EditorAnim anim = editor.selectedAnim;
		if(anim == null)return;
		List<AnimFrame> frames = anim.getFrames();
		int target = getFrameAt(mouseX, curveDrag.trackX, curveDrag.trackW, frames.size());
		int current = anim.getSelectedFrameIndex();
		while(current < target) {
			editor.animMoveFrame(1);
			current = anim.getSelectedFrameIndex();
		}
		while(current > target) {
			editor.animMoveFrame(-1);
			current = anim.getSelectedFrameIndex();
		}
	}

	private void finishCurveDrag() {
		if(curveDrag == null)return;
		CurveDragState drag = curveDrag;
		if(editor.selectedAnim != null) {
			applyCurveDragValue(drag.track, drag.originalValue, true);
			applyCurveDragValue(drag.track, drag.currentValue, false);
			editor.selectedAnim.endDrag();
			editor.updateGui();
		}
		curveDrag = null;
	}

	private void applyCurveDragValue(int track, Vec3f vec, boolean temp) {
		VecType type = getTrackVecType(track);
		switch(track) {
		case 0:
			if(temp) {
				editor.setAnimPos.accept(vec);
				if(editor.selectedAnim != null)editor.selectedAnim.dragVal(type, vec);
			} else {
				editor.setAnimPos(vec);
				editor.setAnimPos.accept(vec);
			}
			break;

		case 1:
			if(temp) {
				editor.setAnimRot.accept(vec);
				if(editor.selectedAnim != null)editor.selectedAnim.dragVal(type, vec);
			} else {
				editor.setAnimRot(vec);
				editor.setAnimRot.accept(vec);
			}
			break;

		case 2:
			if(temp) {
				editor.setAnimScale.accept(vec);
				if(editor.selectedAnim != null)editor.selectedAnim.dragVal(type, vec);
			} else {
				editor.setAnimScale(vec);
				editor.setAnimScale.accept(vec);
			}
			break;

		default:
			break;
		}
	}

	private VecType getTrackVecType(int track) {
		switch(track) {
		case 0:
			return VecType.POSITION;
		case 1:
			return VecType.ROTATION;
		case 2:
			return VecType.MESH_SCALE;
		default:
			return VecType.POSITION;
		}
	}

	private Interpolator createInterpolator(ModelElement elem, InterpolatorChannel channel) {
		Interpolator interpolator = editor.selectedAnim.intType.create();
		interpolator.init(AnimFrame.toArray(editor.selectedAnim, elem, channel), channel.createInterpolatorSetup());
		return interpolator;
	}

	private float toDisplayValue(int track, double value) {
		return track == 1 ? (float) Math.toDegrees(value) : (float) value;
	}

	private int valueToCurveY(float value, int rowY, int rowH, CurveScale scale) {
		return rowY + rowH - 1 - Math.round((value - scale.min) / (scale.max - scale.min) * Math.max(1, rowH - 2));
	}

	private float curveYToValue(int y, int rowY, int rowH, CurveScale scale) {
		float progress = 1f - (y - rowY) / (float) Math.max(1, rowH - 1);
		progress = Math.max(0, Math.min(1, progress));
		return scale.min + (scale.max - scale.min) * progress;
	}

	private float getTrackAxisValue(FrameData data, int track, int axis) {
		Vec3f vec = getTrackVector(data, track);
		return axis == 0 ? vec.x : axis == 1 ? vec.y : vec.z;
	}

	private Vec3f getTrackVector(FrameData data, int track) {
		switch(track) {
		case 0:
			return new Vec3f(data.getPosition());
		case 1:
			return new Vec3f(data.getRotation());
		case 2:
			return new Vec3f(data.getScale());
		default:
			return new Vec3f();
		}
	}

	private void setAxisValue(Vec3f vec, int axis, float value) {
		switch(axis) {
		case 0:
			vec.x = value;
			break;
		case 1:
			vec.y = value;
			break;
		case 2:
			vec.z = value;
			break;
		default:
			break;
		}
	}

	private float clampCurveValue(int track, float value) {
		if(track == 1) {
			while(value < 0)value += 360;
			while(value > 360)value -= 360;
			return value;
		}
		int limit = FormatLimits.getVectorLimit();
		return Math.max(-limit, Math.min(limit, value));
	}

	private String formatCurveValue(float value, int track) {
		float scale = track == 1 ? 10f : 100f;
		float rounded = Math.round(value * scale) / scale;
		if(Math.abs(rounded - Math.round(rounded)) < 0.0001f)return Integer.toString(Math.round(rounded));
		return Float.toString(rounded);
	}

	private InterpolatorChannel[] getChannels(int track) {
		switch(track) {
		case 0: return new InterpolatorChannel[] {InterpolatorChannel.POS_X, InterpolatorChannel.POS_Y, InterpolatorChannel.POS_Z};
		case 1: return new InterpolatorChannel[] {InterpolatorChannel.ROT_X, InterpolatorChannel.ROT_Y, InterpolatorChannel.ROT_Z};
		case 2: return new InterpolatorChannel[] {InterpolatorChannel.SCALE_X, InterpolatorChannel.SCALE_Y, InterpolatorChannel.SCALE_Z};
		default: return new InterpolatorChannel[] {InterpolatorChannel.POS_X, InterpolatorChannel.POS_Y, InterpolatorChannel.POS_Z};
		}
	}

	private FrameData getData(AnimFrame frame, ModelElement elem) {
		IElem data = frame.getData(elem);
		return data instanceof FrameData ? (FrameData) data : null;
	}

	private boolean hasTrackChanges(AnimFrame frame, ModelElement elem, int track) {
		FrameData data = getData(frame, elem);
		if(data == null)return false;
		switch(track) {
		case 0: return data.hasPosChanges();
		case 1: return data.hasRotChanges();
		case 2: return data.hasScaleChanges();
		default: return false;
		}
	}

	private void drawFrameNumbers(int numFrames, int selIdx, int trackX, int trackW, int visibleX, int visibleW, int y) {
		int labelStep = Math.max(1, (int) Math.ceil(numFrames / Math.max(1f, trackW / 34f)));
		for(int i = 0; i < numFrames; i++) {
			if(i % labelStep != 0 && i != selIdx && i != numFrames - 1)continue;
			int fx = trackX + getFrameX(i, numFrames, trackW);
			if(fx < visibleX || fx > visibleX + visibleW)continue;
			String label = String.valueOf(i + 1);
			int tw = gui.textWidth(label);
			gui.drawText(fx - tw / 2, y, label, i == selIdx ? 0xffffffff : 0xffaaaaaa);
		}
	}

	private int getTrackWidth(int visibleW) {
		return Math.max(24, Math.round(visibleW * editor.animTimelineZoom));
	}

	private int getTrackX(int visibleX, int visibleW, int trackW, int focusX) {
		if(trackW <= visibleW)return visibleX;
		return visibleX - getTrackScroll(focusX, visibleW, trackW);
	}

	private int getTrackScroll(int focusX, int visibleW, int trackW) {
		if(trackW <= visibleW)return 0;
		if(Float.isNaN(timelineScroll))return getAutoScroll(focusX, visibleW, trackW);
		timelineScroll = Math.max(0, Math.min(trackW - visibleW, timelineScroll));
		return Math.round(timelineScroll);
	}

	private int getAutoScroll(int focusX, int visibleW, int trackW) {
		if(trackW <= visibleW)return 0;
		return Math.max(0, Math.min(trackW - visibleW, focusX - visibleW / 2));
	}

	private int[] getRowHeights() {
		int available = Math.max(42, getPreferredHeight() - HEADER_H - BOTTOM_H - 3);
		int curveTrack = editor.animTimelineCurveTrack;
		if(curveTrack < 0 || curveTrack > 2) {
			int rowH = Math.max(14, available / 3);
			return new int[] {rowH, rowH, Math.max(14, available - rowH * 2)};
		}
		int normalH = Math.min(20, Math.max(14, available / 4));
		int curveH = available - normalH * 2;
		if(curveH < 28) {
			curveH = 28;
			normalH = Math.max(10, (available - curveH) / 2);
		}
		int[] heights = {normalH, normalH, normalH};
		heights[curveTrack] = Math.max(20, available - normalH * 2);
		return heights;
	}

	private int[] getRowYs(int firstRowY, int[] rowHeights) {
		return new int[] {firstRowY, firstRowY + rowHeights[0], firstRowY + rowHeights[0] + rowHeights[1]};
	}

	private int getTrackAt(int y, int firstRowY, int[] rowHeights) {
		int rowY = firstRowY;
		for(int i = 0; i < 3; i++) {
			if(y >= rowY && y < rowY + rowHeights[i])return i;
			rowY += rowHeights[i];
		}
		return -1;
	}

	private float getPlaybackProgress(EditorAnim anim) {
		long playTime = MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().getTime();
		long duration = Math.max(1, anim.duration);
		return Math.floorMod(playTime - editor.playStartTime, duration) / (float) duration;
	}

	private int getPlaybackFrameIndex(EditorAnim anim, int numFrames) {
		if(numFrames <= 1)return 0;
		return Math.max(0, Math.min(numFrames - 1, (int) (getPlaybackProgress(anim) * numFrames)));
	}

	private int getPlaybackTrackX(EditorAnim anim, int numFrames, int trackW) {
		if(numFrames <= 1)return 0;
		float progress = getPlaybackProgress(anim);
		if(anim.intType == InterpolatorType.NO_INTERPOLATE)return getFrameX(getPlaybackFrameIndex(anim, numFrames), numFrames, trackW);
		return Math.round(progress * trackW);
	}

	private int getCursorTrackX(EditorAnim anim, int numFrames, int trackW) {
		return editor.playFullAnim ? getPlaybackTrackX(anim, numFrames, trackW) : getFrameX(anim.getSelectedFrameIndex(), numFrames, trackW);
	}

	private void scrubCursor(MouseEvent event, EditorAnim anim, List<AnimFrame> frames, int trackX, int trackW) {
		float progress = Math.max(0, Math.min(1, (event.x - trackX) / (float) Math.max(1, trackW)));
		if(editor.playFullAnim) {
			long now = MinecraftClientAccess.get().getPlayerRenderManager().getAnimationEngine().getTime();
			editor.playStartTime = now - Math.round(progress * Math.max(1, anim.duration));
		}
		int idx = Math.max(0, Math.min(frames.size() - 1, Math.round(progress * (frames.size() - 1))));
		if(anim.getSelectedFrameIndex() != idx) {
			anim.setSelectedFrame(frames.get(idx));
			editor.setAnimFrame.accept(idx);
			editor.updateGui();
		}
	}

	private void drawPlayhead(int x, int y, int h) {
		gui.drawBox(x, y, 2, h, 0xffffa726);
		for(int i = 0; i < 5; i++)gui.drawBox(x - i, y - 5 + i, i * 2 + 2, 1, 0xffffa726);
	}

	private int getFrameX(int frameIdx, int numFrames, int trackW) {
		if(numFrames <= 1)return 0;
		return Math.round(frameIdx / (float) (numFrames - 1) * trackW);
	}

	private int getFrameAt(int x, int trackX, int trackW, int numFrames) {
		if(numFrames <= 1)return 0;
		float p = (x - trackX) / (float) trackW;
		return Math.max(0, Math.min(numFrames - 1, Math.round(p * (numFrames - 1))));
	}

	private void drawKey(int x, int y, int color, boolean selected) {
		int size = selected ? 5 : 4;
		for(int dy = -size; dy <= size; dy++) {
			int halfW = size - Math.abs(dy);
			gui.drawBox(x - halfW, y + dy, halfW * 2 + 1, 1, color);
		}
	}

	private void drawDashedLine(int x1, int y1, int x2, int y2, int color, int visibleX, int visibleW) {
		int steps = Math.max(Math.abs(x2 - x1) / 5, 2);
		for(int i = 0; i < steps; i += 2) {
			int sx = x1 + (x2 - x1) * i / steps;
			int sy = y1 + (y2 - y1) * i / steps;
			int ex = x1 + (x2 - x1) * (i + 1) / steps;
			int ey = y1 + (y2 - y1) * (i + 1) / steps;
			drawSolidLine(sx, sy, ex, ey, color, visibleX, visibleW);
		}
	}

	private void drawSolidLine(int x1, int y1, int x2, int y2, int color, int visibleX, int visibleW) {
		int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
		if(steps == 0)steps = 1;
		for(int i = 0; i <= steps; i++) {
			int x = x1 + (x2 - x1) * i / steps;
			if(x < visibleX || x > visibleX + visibleW)continue;
			int y = y1 + (y2 - y1) * i / steps;
			gui.drawBox(x, y, 1, 1, color);
		}
	}
}
