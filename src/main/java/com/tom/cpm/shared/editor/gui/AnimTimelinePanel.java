package com.tom.cpm.shared.editor.gui;

import java.util.List;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.MouseEvent;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.math.Box;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.AnimFrame.FrameData;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ModelElement;

public class AnimTimelinePanel extends Panel {
	private static final int HEADER_H = 18;
	private static final int TRACK_H = 30;
	public static final int PANEL_H = HEADER_H + TRACK_H; // 48
	public static final int COLLAPSED_H = HEADER_H;

	private Editor editor;
	private boolean collapsed;
	private Button toggleBtn;
	private Label frameLabel;

	public AnimTimelinePanel(IGui gui, Editor editor, int width) {
		super(gui);
		this.editor = editor;
		setBounds(new Box(0, 0, width, PANEL_H));
		setBackgroundColor(gui.getColors().panel_background);

		// Toggle collapse button
		toggleBtn = new Button(gui, "\u25BC", this::toggleCollapse);
		toggleBtn.setBounds(new Box(2, 0, 16, HEADER_H));
		addElement(toggleBtn);

		// Duration label (right-aligned-ish)
		Label durationLabel = new Label(gui, "");
		durationLabel.setBounds(new Box(width - 110, 3, 100, 12));
		addElement(durationLabel);
		editor.setAnimDuration.add(d -> {
			if (d != null) {
				durationLabel.setText(gui.i18nFormat("label.cpm.timeline_duration", d));
			} else {
				durationLabel.setText("");
			}
		});

		// Frame index label (shows current frame / total)
		frameLabel = new Label(gui, "");
		frameLabel.setBounds(new Box(22, 3, 200, 12));
		addElement(frameLabel);
		editor.setAnimFrame.add(idx -> updateFrameLabel());
		editor.setSelAnim.add(a -> updateFrameLabel());
	}

	private void updateFrameLabel() {
		EditorAnim anim = editor.selectedAnim;
		if (anim != null) {
			int idx = anim.getSelectedFrameIndex();
			int total = anim.getFrames().size();
			frameLabel.setText(gui.i18nFormat("label.cpm.timeline_frame_info", idx + 1, total));
		} else {
			frameLabel.setText("");
		}
	}

	private void toggleCollapse() {
		collapsed = !collapsed;
		setBounds(new Box(bounds.x, bounds.y, bounds.w, collapsed ? COLLAPSED_H : PANEL_H));
		toggleBtn.setText(collapsed ? "\u25B2" : "\u25BC");
	}

	@Override
	public void draw(MouseEvent event, float partialTicks) {
		super.draw(event, partialTicks);

		if (collapsed) return;

		EditorAnim anim = editor.selectedAnim;
		if (anim == null) return;

		List<AnimFrame> frames = anim.getFrames();
		int numFrames = frames.size();
		if (numFrames == 0) return;

		int selIdx = anim.getSelectedFrameIndex();
		ModelElement selElem = editor.getSelectedElement();

		int trackY = HEADER_H + 2;
		int trackH = 6;
		int margin = 30;
		int rightMargin = 15;
		int trackW = bounds.w - margin - rightMargin;
		int trackX = bounds.x + margin;
		int trackCenterY = trackY + trackH / 2;
		int labelY = trackY + trackH + 2;
		int dataDotY = trackY - 6;

		int bgColor = 0xff3a3a3a;
		int barColor = 0xff666666;
		int markerColor = 0xffaaaaaa;
		int markerHoverColor = 0xffcccccc;
		int selColor = 0xffffd740;
		int selOutlineColor = 0xffffffff;
		int textColor = 0xffaaaaaa;
		int selTextColor = 0xffffffff;
		int dataColor = 0xff4fc3f7;
		int dataSelColor = 0xffffd740;
		int prevDashColor = 0x88ffab40;

		// Draw background track bar
		gui.drawBox(trackX, trackY, trackW, trackH, bgColor);

		// Draw connecting line
		if (numFrames > 1) {
			int startX = trackX + getFrameX(0, numFrames, trackW);
			int endX = trackX + getFrameX(numFrames - 1, numFrames, trackW);
			gui.drawBox(startX, trackCenterY - 1, endX - startX, 2, barColor);
		}

		// Draw per-part data indicators (when a single part is selected)
		if (selElem != null && editor.showMovementTrack.get()) {
			// Draw dashed line connecting frames that have data for this element
			int prevDataX = -1;
			for (int i = 0; i < numFrames; i++) {
				AnimFrame.FrameData fd = frames.get(i).getData(selElem) instanceof AnimFrame.FrameData ? 
						(AnimFrame.FrameData) frames.get(i).getData(selElem) : null;
				if (fd != null && fd.hasChanges()) {
					int fx = trackX + getFrameX(i, numFrames, trackW);
					if (prevDataX >= 0) {
						drawDashedLine(prevDataX, dataDotY + 3, fx, dataDotY + 3, prevDashColor);
					}
					prevDataX = fx;

					// Draw data dot (larger for selected frame)
					int dotColor = (i == selIdx) ? dataSelColor : dataColor;
					int dotSize = (i == selIdx) ? 3 : 2;
					gui.drawBox(fx - dotSize, dataDotY, dotSize * 2 + 1, dotSize * 2 + 1, dotColor);
				} else {
					// Small empty indicator for frames without data
					int fx = trackX + getFrameX(i, numFrames, trackW);
					gui.drawBox(fx - 1, dataDotY + 1, 2, 2, 0xff555555);
				}
			}
		}

		// Draw markers and labels
		MouseEvent evt = event.offset(bounds);
		for (int i = 0; i < numFrames; i++) {
			int fx = trackX + getFrameX(i, numFrames, trackW);
			boolean isSelected = (i == selIdx);
			boolean isHovered = evt.isHovered(new Box(fx - 5, trackY, 11, trackH + 6));

			// Marker diamond
			int color = isSelected ? selColor : (isHovered ? markerHoverColor : markerColor);
			drawMarker(fx, trackCenterY, color, isSelected);

			// Frame number label
			int labelColor = isSelected ? selTextColor : textColor;
			String label = String.valueOf(i + 1);
			int tw = gui.textWidth(label);
			gui.drawText(fx - tw / 2, labelY, label, labelColor);
		}

		// Draw current frame highlight line
		if (selIdx >= 0) {
			int selX = trackX + getFrameX(selIdx, numFrames, trackW);
			gui.drawBox(selX, trackY - 1, 1, labelY - trackY + 10, selOutlineColor);
		}
	}

	@Override
	public void mouseClick(MouseEvent event) {
		super.mouseClick(event);

		if (collapsed) return;

		EditorAnim anim = editor.selectedAnim;
		if (anim == null) return;

		List<AnimFrame> frames = anim.getFrames();
		int numFrames = frames.size();
		if (numFrames == 0) return;

		int trackY = HEADER_H + 2;
		int trackH = 6;
		int margin = 30;
		int rightMargin = 15;
		int trackW = bounds.w - margin - rightMargin;
		int trackX = bounds.x + margin;

		MouseEvent evt = event.offset(bounds);
		for (int i = 0; i < numFrames; i++) {
			int fx = trackX + getFrameX(i, numFrames, trackW);
			if (evt.isHovered(new Box(fx - 6, trackY - 4, 13, trackH + 20))) {
				if (i != anim.getSelectedFrameIndex()) {
					anim.setSelectedFrame(frames.get(i));
					editor.setAnimFrame.accept(i);
					editor.updateGui();
				}
				event.consume();
				return;
			}
		}
	}

	private int getFrameX(int frameIdx, int numFrames, int trackW) {
		if (numFrames <= 1) return trackW / 2;
		return (int) (frameIdx / (float) (numFrames - 1) * trackW);
	}

	private void drawMarker(int x, int y, int color, boolean selected) {
		int size = selected ? 5 : 4;
		for (int dy = -size; dy <= size; dy++) {
			int halfW = size - Math.abs(dy);
			if (halfW >= 0) {
				gui.drawBox(x - halfW, y + dy, halfW * 2 + 1, 1, color);
			}
		}
	}

	private void drawDashedLine(int x1, int y, int x2, int y2, int color) {
		int dx = Math.abs(x2 - x1);
		int dy = Math.abs(y2 - y1);
		int steps = Math.max(dx / 3, 2);
		for (int i = 0; i < steps; i += 2) {
			int sx = x1 + (x2 - x1) * i / steps;
			int sy = y + (y2 - y) * i / steps;
			int ex = x1 + (x2 - x1) * (i + 1) / steps;
			int ey = y + (y2 - y) * (i + 1) / steps;
			gui.drawBox(sx, sy, Math.max(ex - sx, 1), Math.max(ey - sy + 1, 1), color);
		}
	}
}
		if (numFrames <= 1) return trackW / 2;
		return (int) (frameIdx / (float) (numFrames - 1) * trackW);
	}

	private void drawMarker(int x, int y, int color, boolean selected) {
		int size = selected ? 5 : 4;
		// Diamond shape using small boxes
		for (int dy = -size; dy <= size; dy++) {
			int halfW = size - Math.abs(dy);
			if (halfW >= 0) {
				gui.drawBox(x - halfW, y + dy, halfW * 2 + 1, 1, color);
			}
		}
	}
}
