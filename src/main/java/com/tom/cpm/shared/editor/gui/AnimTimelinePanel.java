package com.tom.cpm.shared.editor.gui;

import java.util.List;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.MouseEvent;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.math.Box;
import com.tom.cpl.math.Vec3f;
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
	private static final int MAX_H = 180;
	public static final int PANEL_H = MIN_H;
	public static final int COLLAPSED_H = HEADER_H;

	private final Editor editor;
	private final Button toggleBtn, prevBtn, playBtn, nextBtn, zoomOutBtn, zoomInBtn;
	private final Label frameLabel, durationLabel, zoomLabel;
	private boolean collapsed, resizing;
	private int resizeStartY, resizeStartH;
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
		editor.setAnimPlay.add(v -> playBtn.setText(v ? "Stop" : "Play"));
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
		editor.animTimelineZoom = Math.max(1, Math.min(8, editor.animTimelineZoom * mul));
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
		int trackW = Math.max(visibleW, Math.round(visibleW * editor.animTimelineZoom));
		int scroll = getScroll(anim.getSelectedFrameIndex(), numFrames, visibleW, trackW);
		int trackX = visibleX - scroll;
		int rowH = Math.max(16, (getPreferredHeight() - HEADER_H - BOTTOM_H) / 3);
		int firstRowY = b.y + HEADER_H + 3;
		int selIdx = anim.getSelectedFrameIndex();
		int selX = trackX + getFrameX(selIdx, numFrames, trackW);
		ModelElement selectedElement = editor.getSelectedElement();

		drawRow(frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, firstRowY, rowH, gui.i18nFormat("label.cpm.position"), 0xffff6655, 0);
		drawRow(frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, firstRowY + rowH, rowH, gui.i18nFormat("label.cpm.rotation"), 0xff77a7ff, 1);
		drawRow(frames, selectedElement, numFrames, selIdx, trackX, trackW, visibleX, visibleW, firstRowY + rowH * 2, rowH, gui.i18nFormat("label.cpm.scale"), 0xff7bd46d, 2);

		if(selX >= visibleX && selX <= visibleX + visibleW)gui.drawBox(selX, firstRowY - 2, 1, rowH * 3 + 3, 0xffffffff);
		drawFrameNumbers(numFrames, selIdx, trackX, trackW, visibleX, visibleW, firstRowY + rowH * 3 - 1);
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
		int trackW = Math.max(visibleW, Math.round(visibleW * editor.animTimelineZoom));
		int trackX = visibleX - getScroll(anim.getSelectedFrameIndex(), numFrames, visibleW, trackW);
		int rowH = Math.max(16, (getPreferredHeight() - HEADER_H - BOTTOM_H) / 3);
		int firstRowY = b.y + HEADER_H + 3;
		int clickedTrack = getTrackAt(event.y, firstRowY, rowH);
		if(clickedTrack != -1 && event.x >= b.x && event.x < visibleX) {
			long now = System.currentTimeMillis();
			if(lastClickedTrack == clickedTrack && now - lastTrackClick < 400) {
				editor.animTimelineCurveTrack = editor.animTimelineCurveTrack == clickedTrack ? -1 : clickedTrack;
			}
			lastClickedTrack = clickedTrack;
			lastTrackClick = now;
			event.consume();
			return;
		}
		if(event.isHovered(new Box(visibleX, firstRowY - 2, visibleW, rowH * 3 + BOTTOM_H))) {
			int idx = getFrameAt(event.x, trackX, trackW, numFrames);
			if(idx != anim.getSelectedFrameIndex()) {
				anim.setSelectedFrame(frames.get(idx));
				editor.setAnimFrame.accept(idx);
				editor.updateGui();
			}
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
		super.mouseDrag(event);
	}

	@Override
	public void mouseRelease(MouseEvent event) {
		if(resizing) {
			resizing = false;
			event.consume();
			return;
		}
		super.mouseRelease(event);
	}

	private void drawRow(List<AnimFrame> frames, ModelElement elem, int numFrames, int selIdx, int trackX, int trackW, int visibleX, int visibleW, int rowY, int rowH, String label, int color, int track) {
		int lineY = rowY + rowH / 2;
		boolean curve = editor.animTimelineCurveTrack == track;
		gui.drawBox(bounds.x, rowY - 1, bounds.w, 1, 0xff555555);
		gui.drawText(bounds.x + 8, rowY + Math.max(3, rowH / 2 - 5), label, curve ? 0xffffd740 : (elem != null ? 0xffffffff : 0xffbbbbbb));
		gui.drawBox(visibleX, lineY, visibleW, 2, curve ? 0xff353535 : 0xff454545);
		if(curve && elem != null)drawCurve(frames, elem, numFrames, trackX, trackW, visibleX, visibleW, rowY + 2, rowH - 4, track);
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
		int[] colors = {0xffff5555, 0xff55dd55, 0xff6699ff};
		for(int axis = 0; axis < 3; axis++) {
			float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
			for(AnimFrame frame : frames) {
				FrameData data = getData(frame, elem);
				if(data != null && hasTrackChanges(frame, elem, track)) {
					float v = getTrackValue(data, track, axis);
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
			}
			if(min == Float.MAX_VALUE)continue;
			if(Math.abs(max - min) < 0.001f) {max += 1; min -= 1;}
			int px = -1, py = -1;
			for(int i = 0; i < numFrames; i++) {
				FrameData data = getData(frames.get(i), elem);
				if(data == null || !hasTrackChanges(frames.get(i), elem, track))continue;
				int x = trackX + getFrameX(i, numFrames, trackW);
				float v = getTrackValue(data, track, axis);
				int y = rowY + rowH - 1 - Math.round((v - min) / (max - min) * Math.max(1, rowH - 2));
				if(px != -1)drawSolidLine(px, py, x, y, colors[axis], visibleX, visibleW);
				px = x;
				py = y;
			}
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
		int labelStep = Math.max(1, (int) Math.ceil(numFrames / Math.max(1f, visibleW / 34f)));
		for(int i = 0; i < numFrames; i++) {
			if(i % labelStep != 0 && i != selIdx && i != numFrames - 1)continue;
			int fx = trackX + getFrameX(i, numFrames, trackW);
			if(fx < visibleX || fx > visibleX + visibleW)continue;
			String label = String.valueOf(i + 1);
			int tw = gui.textWidth(label);
			gui.drawText(fx - tw / 2, y, label, i == selIdx ? 0xffffffff : 0xffaaaaaa);
		}
	}

	private int getScroll(int selectedFrame, int numFrames, int visibleW, int trackW) {
		if(trackW <= visibleW)return 0;
		int selX = getFrameX(Math.max(0, selectedFrame), numFrames, trackW);
		return Math.max(0, Math.min(trackW - visibleW, selX - visibleW / 2));
	}

	private int getTrackAt(int y, int firstRowY, int rowH) {
		for(int i = 0; i < 3; i++)if(y >= firstRowY + rowH * i && y < firstRowY + rowH * (i + 1))return i;
		return -1;
	}

	private int getFrameX(int frameIdx, int numFrames, int trackW) {
		if(numFrames <= 1)return trackW / 2;
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
