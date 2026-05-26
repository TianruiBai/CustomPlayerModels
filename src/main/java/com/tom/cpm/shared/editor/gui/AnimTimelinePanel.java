package com.tom.cpm.shared.editor.gui;

import java.util.List;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.MouseEvent;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.math.Box;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.animation.InterpolatorChannel;
import com.tom.cpm.shared.animation.interpolator.Interpolator;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.AnimFrame.FrameData;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.anim.IElem;
import com.tom.cpm.shared.editor.elements.ModelElement;

public class AnimTimelinePanel extends Panel {
	private static final int HEADER_H = 20;
	private static final int BOTTOM_H = 14;
	private static final int LABEL_W = 76;
	private static final int MIN_H = 86;
	private static final int MIN_CURVE_H = 128;
	private static final int MAX_H = 240;
	private static final float MIN_ZOOM = 0.25f;
	private static final float MAX_ZOOM = 8;
	public static final int PANEL_H = MIN_H;
	public static final int COLLAPSED_H = HEADER_H;

	private final Editor editor;
	private final Button toggleBtn, prevBtn, playBtn, nextBtn, zoomOutBtn, zoomInBtn;
	private final Label frameLabel, durationLabel, zoomLabel;
	private boolean collapsed, resizing, draggingCursor;
	private int resizeStartY, resizeStartH, dragTrackX, dragTrackW;
	private long lastTrackClick;
	private int lastClickedTrack = -1;
	private Runnable layoutListener;

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
		return collapsed ? COLLAPSED_H : Math.max(MIN_H, Math.min(MAX_H, editor.animTimelineHeight));
	}

	private void updateHeader() {
		EditorAnim anim = editor.selectedAnim;
		boolean enabled = anim != null;
		prevBtn.setEnabled(enabled);
		playBtn.setEnabled(enabled);
		nextBtn.setEnabled(enabled);
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
		updateHeader();
	}

	@Override
	public void draw(MouseEvent event, float partialTicks) {
		super.draw(event, partialTicks);
		Box b = getBounds();
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

		drawRow(frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, rowYs[0], rowHeights[0], gui.i18nFormat("label.cpm.position"), 0xffff6655, 0);
		drawRow(frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, rowYs[1], rowHeights[1], gui.i18nFormat("label.cpm.rotation"), 0xff77a7ff, 1);
		drawRow(frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, rowYs[2], rowHeights[2], gui.i18nFormat("label.cpm.scale"), 0xff7bd46d, 2);

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
		if(resizing) {
			editor.animTimelineHeight = Math.max(MIN_H, Math.min(MAX_H, resizeStartH + resizeStartY - event.y));
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
	public void mouseRelease(MouseEvent event) {
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

	private void drawRow(List<AnimFrame> frames, ModelElement elem, int numFrames, int selIdx, int trackX, int trackW, int visibleX, int visibleW, int rowY, int rowH, String label, int color, int track) {
		boolean curve = editor.animTimelineCurveTrack == track;
		int lineY = curve ? rowY + 11 : rowY + rowH / 2;
		gui.drawBox(bounds.x, rowY - 1, bounds.w, 1, 0xff555555);
		gui.drawText(bounds.x + 8, curve ? rowY + 5 : rowY + Math.max(3, rowH / 2 - 5), label, curve ? 0xffffd740 : (elem != null ? 0xffffffff : 0xffbbbbbb));
		gui.drawBox(visibleX, lineY, visibleW, 2, curve ? 0xff353535 : 0xff454545);
		if(curve && elem != null) {
			int curveY = lineY + 7;
			int curveH = Math.max(8, rowY + rowH - curveY - 4);
			gui.drawBox(visibleX, curveY, visibleW, curveH, 0xff242424);
			gui.drawBox(visibleX, curveY + curveH / 2, visibleW, 1, 0xff3c3c3c);
			drawCurve(frames, elem, numFrames, trackX, trackW, visibleX, visibleW, curveY + 2, curveH - 4, track);
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

	private void drawCurve(List<AnimFrame> frames, ModelElement elem, int numFrames, int trackX, int trackW, int visibleX, int visibleW, int rowY, int rowH, int track) {
		if(numFrames < 2)return;
		int[] colors = {0xffff5555, 0xff55dd55, 0xff6699ff};
		InterpolatorChannel[] channels = getChannels(track);
		for(int axis = 0; axis < 3; axis++) {
			Interpolator interpolator = editor.selectedAnim.intType.create();
			interpolator.init(AnimFrame.toArray(editor.selectedAnim, elem, channels[axis]), channels[axis].createInterpolatorSetup());
			float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
			int samples = Math.max(12, Math.min(160, trackW));
			for(int i = 0; i <= samples; i++) {
				float progress = i / (float) samples;
				float v = (float) interpolator.applyAsDouble(progress * numFrames);
				min = Math.min(min, v);
				max = Math.max(max, v);
			}
			if(min == Float.MAX_VALUE)continue;
			if(Math.abs(max - min) < 0.001f) {max += 1; min -= 1;}
			int px = -1, py = -1;
			for(int i = 0; i <= samples; i++) {
				float progress = i / (float) samples;
				int x = trackX + Math.round(progress * trackW);
				float v = (float) interpolator.applyAsDouble(progress * numFrames);
				int y = rowY + rowH - 1 - Math.round((v - min) / (max - min) * Math.max(1, rowH - 2));
				if(px != -1)drawSolidLine(px, py, x, y, colors[axis], visibleX, visibleW);
				px = x;
				py = y;
			}

			for(int i = 0; i < numFrames; i++) {
				FrameData data = getData(frames.get(i), elem);
				if(data == null || !hasTrackChanges(frames.get(i), elem, track))continue;
				int x = trackX + getFrameX(i, numFrames, trackW);
				float v = getTrackValue(data, track, axis);
				int y = rowY + rowH - 1 - Math.round((v - min) / (max - min) * Math.max(1, rowH - 2));
				if(x >= visibleX && x <= visibleX + visibleW)gui.drawBox(x - 1, y - 1, 3, 3, colors[axis]);
			}
		}
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

	private float getTrackValue(FrameData data, int track, int axis) {
		Vec3f v = track == 0 ? data.getPosition() : track == 1 ? data.getRotation() : data.getScale();
		return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
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
		return visibleX - getScroll(focusX, visibleW, trackW);
	}

	private int getScroll(int focusX, int visibleW, int trackW) {
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
