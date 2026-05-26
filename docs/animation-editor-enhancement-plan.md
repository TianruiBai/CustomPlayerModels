# Animation Editor Enhancement Plan
## Branch: `feature/1.21-animation-editing-enhance`

---

## Implemented Scope

- Bottom animation timeline is integrated into the animation tab layout and can be collapsed.
- Timeline is split into Position, Rotation, and Scale rows, with keyed frames shown per transform channel.
- Timeline header supports previous frame, play/stop, next frame, zoom out, zoom in, frame info, and duration display.
- Timeline height is adjustable by dragging the top edge of the panel.
- Double-clicking a transform row label toggles curve display for that channel, showing X/Y/Z curves in red/green/blue.
- Selected-part movement guides in the viewport now render a full movement path, highlighted current segment, and optional dashed past segments.
- Animation panel and display menu include a toggle for dashed past movement segments.

---

## Current Architecture Summary

```
EditorGui (Animation Tab)
├─ Top: Tab bar (Model | Texture | Animation)
├─ Left (170px): AnimPanel + AnimTestPanel (tabbed)
│   ├─ Animation selector (ListPicker)
│   ├─ Frame nav: [<] Frame: N [>]
│   ├─ [New Frame] [Del Frame] [▶ Play]
│   ├─ Duration spinner
│   └─ Animation operations popup
├─ Center: ViewportPanelAnim (3D preview)
│   └─ Already renders previous frame as outline (showPreviousFrame)
├─ Right (150px): TreePanel (part hierarchy) + Quick actions
└─ Bottom: AnimTimelinePanel (foldable timeline)
```

**Key data model:**
- `EditorAnim` → has `List<AnimFrame> frames`, `int duration`, `AnimFrame currentFrame`
- `AnimFrame` → `Map<ModelElement, FrameData>` (per-part transforms: pos, rot, scale, color, visibility)
- Frame navigation: `prevFrame()`, `nextFrame()`, `getSelectedFrameIndex()`
- Previous frame ghost: rendered via `editor.definition.outlineOnly = true` in `ViewportPanelAnim.render()`

---

## Enhancement 1: Animation Timeline Panel

### New File: `AnimTimelinePanel.java`
**Location:** `src/main/java/com/tom/cpm/shared/editor/gui/AnimTimelinePanel.java`

A foldable bottom panel (like the UV panel in the texture editor) that visualizes keyframes on a horizontal timeline.

### Layout
```
┌──────────────────────────────────────────────────────────┐
│ [▼] Timeline                              Duration: 1000ms│
├──────────────────────────────────────────────────────────┤
│  0    1  ●  2    3    4    5    6    7    8    9        │
│  ◆────◆────◆────◆────◆────◆────◆────◆────◆────◆────◆    │
│       ^current                                           │
└──────────────────────────────────────────────────────────┘
```

### Features:
- **Collapse/expand toggle** (▼/▲ button) — panel slides up/down
- **Horizontal track bar** spanning the animation duration
- **Keyframe markers**: diamond/circle nodes at each frame's time position
  - Color-coded: current frame highlighted (e.g., gold), others in grey
  - Clickable: clicking a marker jumps to that frame via `editor.selectedAnim.setSelectedFrame(frames.get(i))`
- **Frame number labels** below each marker
- **Hover tooltip**: shows frame index and which parts have data in that frame
- **Playhead indicator**: a vertical line or highlighted segment showing current playback position
- **Duration display**: shows total `editor.selectedAnim.duration`

### State (in `Editor.java`):
```java
public BooleanUpdater showTimeline = updaterReg.createBool(true);
public float animTimelineZoom = 1;
public int animTimelineHeight = 86;
public int animTimelineCurveTrack = -1;
```

### Integration (in `EditorGui.initAnimPanel()`):
- Add `AnimTimelinePanel` as a collapsible element at the bottom of the animation tab
- Resize viewport and tree panel when timeline is shown (subtract ~40px from height)
- Add a toggle button next to the playback button or in the display menu

---

## Enhancement 2: Movement Track Lines

### Modified File: `ViewportPanelAnim.java`

Render visual guides showing how a selected body part moves across keyframes.

### Feature A: Previous Keyframe Dashline
When editing a frame (not the first frame), draw a **dashed line** in the 3D viewport connecting:
- The **current position** of the selected element
- The **previous keyframe position** of the same element

This builds on the existing `showPreviousFrame` feature (which renders the previous frame as an outline). The dashline adds a visual arrow/path showing the movement arc.

**Rendering approach:**
```java
// In ViewportPanelAnim.render():
if (editor.showMovementTrack.get() && editor.selectedAnim != null && editor.selectedElement != null) {
    AnimFrame current = editor.selectedAnim.getSelectedFrame();
    int idx = editor.selectedAnim.getSelectedFrameIndex();
    if (idx > 0) {
        AnimFrame prev = editor.selectedAnim.getFrames().get(idx - 1);
        Vec3f prevPos = prev.getData(el).getPosition();
        Vec3f currPos = current.getData(el).getPosition();
        // Draw dashed line from prevPos to currPos in world space
        drawDashedLine(stack, buf, prevPos, currPos, DASH_COLOR);
    }
}
```

### Feature B: Multi-Frame Movement Trail (optional extension)
Show a fading trail of the last N keyframe positions as a polyline:
- More opaque near current frame, fading out for earlier frames
- Togglable separately from the single dashline

### State (in `Editor.java`):
```java
public BooleanUpdater showMovementTrack = updaterReg.createBool(true);
public BooleanUpdater showPastMovementTrack = updaterReg.createBool(true);
```

---

## Enhancement 3: Keyframe Visual Polish

### Modified File: `ViewportPanelAnim.java`

The existing previous-frame ghost (outline rendering) can be enhanced:
- Instead of just rendering the outline of the previous frame model, also draw thin colored lines connecting corresponding joints/parts between the previous and current positions
- This gives creators immediate visual feedback on what moved and by how much

### Option: Color-coded per channel
- **Red dash** = position change
- **Blue dash** = rotation change  
- **Green dash** = scale change
But this may be over-engineering for initial release.

---

## Implementation Order

### Step 1: `AnimTimelinePanel.java` — The core timeline widget
1. Create the panel class with:
   - `draw()` method rendering the timeline bar, markers, labels
   - `mouseClick()` handling for marker selection
   - Integration with `Editor.setAnimFrame` updater
2. Add `showTimeline` boolean to `Editor.java`
3. Add display menu toggle

### Step 2: Integrate timeline into `EditorGui.java`
1. Modify `initAnimPanel()` to add the timeline as a collapsible bottom element
2. Adjust viewport bounds when timeline is visible
3. Wire up the collapse toggle

### Step 3: Movement track dashline in `ViewportPanelAnim.java`
1. Add `showMovementTrack` state to `Editor.java`
2. Implement dashed line rendering in viewport
3. Add toggle in display menu or as checkbox

### Step 4: Localization
Add new i18n keys to `en_us.json` and `zh_cn.json`:
- `label.cpm.timeline` — "Timeline"
- `label.cpm.timeline.show` — "Show Timeline"
- `label.cpm.timeline.movement_track` — "Movement Track"
- `tooltip.cpm.timeline.click_frame` — "Click to select frame"

---

## Key Design Decisions

1. **Timeline is foldable, not a popup** — Matches existing pattern (UV panel in texture editor), keeps context visible
2. **Timeline shows ALL frames** — Space-efficient horizontal layout; for animations with many frames (>20), add horizontal scrolling or zoom
3. **Movement track uses existing render pipeline** — Same `RenderMode.OUTLINE` buffer, same coordinate system as the viewport
4. **No data model changes** — All enhancements are pure visual/GUI additions; no changes to animation file format or runtime playback
5. **All features toggleable** — Users can disable movement tracks or timeline if they prefer the current minimal UI

---

## Files to Create
| File | Purpose |
|------|---------|
| `src/main/java/com/tom/cpm/shared/editor/gui/AnimTimelinePanel.java` | Timeline widget |

## Files to Modify
| File | Change |
|------|--------|
| `src/main/java/com/tom/cpm/shared/editor/gui/EditorGui.java` | Add timeline panel, adjust layout |
| `src/main/java/com/tom/cpm/shared/editor/gui/ViewportPanelAnim.java` | Add movement track dashline rendering |
| `src/main/java/com/tom/cpm/shared/editor/Editor.java` | Add `showTimeline`, `showMovementTrack` state |
| `src/main/resources/assets/cpm/lang/en_us.json` | New i18n keys |
| `src/main/resources/assets/cpm/lang/zh_cn.json` | Chinese translations |
