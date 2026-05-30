# PSL Light System Enhancement Plan

## Current State

| Component | Status |
|-----------|--------|
| `LightEmitter` (8 fields) | ✅ Basic — color, intensity, radius, flicker×3, dynamic, castShadows |
| `LightRuntime` (flicker only) | ✅ Basic — sinusoidal flicker driven by tick counter |
| `PslClientRuntime` light methods | ❌ Empty stubs — `registerDynamicLight`, `updateDynamicLight`, `unregisterDynamicLight` |
| Editor UI (`PslSettingsPanel.light()`) | ✅ Minimal — 2 sections, 6 controls |
| Editor preview (`PslEditorPreview.renderLights()`) | ✅ Basic — bounding box at element position |
| JSON persistence | ✅ All 8 fields save/load |
| Binary serialization | ✅ All 8 fields |

### Current LightEmitter Fields

```java
private int color = 0xFFFFFF;           // RGB packed int
private float intensity = 0.7f;         // Light strength [0–1]
private float radius = 3.0f;            // Light spread [1–15] blocks
private boolean flicker;                // Enable flicker animation
private float flickerSpeed = 1.0f;      // Animation speed multiplier
private float flickerAmount = 0.1f;     // Flicker amplitude [0–1]
private boolean dynamic = true;         // Enable dynamic light casting
private boolean castShadows = true;     // Cast shadows flag (future)
```

### Critical Finding: No Vanilla Dynamic Light API

Vanilla Minecraft 1.21.1 has **no entity-based dynamic light API**. `LevelLightEngine` only manages block and sky light propagation. The feasible approach is **shader-style additive glow rendering** — this is what mods like "Dynamic Lights" and OptiFine do: render light halos with additive blend, not actual block light modification.

---

## Enhancement Plan

### Phase 2a — Light Emitter Types & Shape

New enum `LightType { POINT, SPOT, AREA }`:

- **POINT** (current behavior): Omnidirectional light, uniform radius
- **SPOT** (new): Directional cone with angle + direction, brighter in center
- **AREA** (new): Flat plane light, rectangular, emits perpendicular to face

New fields on `LightEmitter`:

```java
private LightType lightType = LightType.POINT;

// Spot params
private float spotAngle = 45f;       // cone half-angle in degrees
private float spotSoftness = 0.2f;   // edge falloff [0–1]

// Area params
private float areaWidth = 1f;        // plane width
private float areaHeight = 1f;       // plane height
```

**LightType enum**:

```java
public enum LightType {
    POINT,    // omnidirectional sphere
    SPOT,     // directional cone
    AREA,     // flat plane
    ;
    public static final LightType[] VALUES = values();
}
```

### Phase 2b — Offset & Rotation

Add positional controls (follows particle offset pattern):

```java
private Vec3f offset = new Vec3f(0, 0, 0);
private Vec3f rotation = new Vec3f(0, 0, 0);  // pitch/yaw/roll in degrees
```

Light world position = element position + offset, oriented by rotation.
- For **SPOT** lights: beam direction follows the rotated forward vector
- For **AREA** lights: plane normal follows the rotated up vector
- For **POINT** lights: rotation has no effect (omnidirectional)

### Phase 2c — Color Temperature

Add color temperature control alongside RGB picker. Use a **warm↔cool slider** (0 = cool blue, 0.5 = neutral, 1 = warm orange) that modulates the base color. This is more intuitive for creators than raw Kelvin values.

```java
private float colorTemperature = 0.5f;  // 0=cool blue, 0.5=neutral, 1=warm orange
```

Helper method: `applyTemperature(int rgb, float temp)` blends the base RGB with warm/orange or cool/blue tint:

```
temp < 0.5 → lerp(rgb, COOL_BLUE, 1 - 2*temp)   // cool shift
temp > 0.5 → lerp(rgb, WARM_ORANGE, 2*temp - 1)  // warm shift
```

Preset reference points:
| Name | Value | Tone |
|------|-------|------|
| Cool White | 0.0 | Blue-tinted (~8000K) |
| Neutral | 0.5 | Pure white (~5500K) |
| Warm White | 1.0 | Orange-tinted (~3000K) |

### Phase 2d — In-game Light Rendering

Create `LightRenderer` (similar to `ParticleRenderer`) that renders per-light:

1. **Additive glow sphere** at light position:
   - Colored by base color × intensity × temperature
   - Sized by radius
2. Uses custom `RenderType` with additive blending, no depth write, no cull
3. Called from `PslClientRuntime.renderCurrentLights()` in the render event handler

**Glow texture**: A simple radial gradient (soft circle) uploaded once as a shared `DynamicTexture`. Render a camera-facing quad scaled to the light radius, using additive blend.

**Type-specific rendering**:
- **POINT**: Uniform circular glow
- **SPOT**: Cone-shaped glow, brighter at center, fades with angle from beam direction
- **AREA**: Rectangular glow panel, fades with distance from plane

### Phase 2e — Editor Preview Upgrade

Replace the current bounding-box preview with per-type wireframe visualization:

- **POINT**: Wireframe sphere (icosasphere-style rings) + glowing center dot
- **SPOT**: Cone wireframe showing angle and direction from emitter
- **AREA**: Rectangle wireframe showing plane extents
- All rendered with the light's color and alpha based on intensity

### Phase 2f — UI Redesign

The current 2-section layout expands to 4 sections with conditional content:

```
┌─ Source ─────────────────────────────────────┐
│ Type: [POINT ▼]   │  Target: Element 3       │
│ Offset:   [X]  [Y]  [Z]                      │
│ Rotation: [X°] [Y°] [Z°]                     │
├─ Emission ────────────────────────────────────┤
│ Color: [■■■] (color picker button)            │
│ Temperature: [━━━━━●━━━━━]  warm ↔ cool      │
│ Intensity: [━━━━●━━━━]  0.70                 │
│ Radius:    [━━━━━●━━━]  3.0                  │
├─ Type Config (conditional on type) ──────────┤
│ (POINT:)  ☑ Dynamic Light                    │
│ (SPOT:)   Angle: [45°]  Softness: [0.20]     │
│ (AREA:)   Width: [1.0]  Height: [1.0]        │
├─ Animation ───────────────────────────────────┤
│ ☑ Flicker                                    │
│ Speed:  [━━━━●━━━━]  1.0                     │
│ Amount: [━━●━━━━━━]  0.10                    │
└──────────────────────────────────────────────┘
```

### Phase 2g — Runtime Integration

Update `PslSystem.updateDynamicLight()`:
- Pass per-type parameters to the runtime
- Handle light type in the registration flow

Update `IPslRuntime`:
```java
// Replace individual register/update/unregister with:
default void renderLight(LightEmitter emitter, Vec3f worldPos, float currentIntensity,
                         PoseStack stack, MultiBufferSource buffers, Camera camera) {}
```

Update `PslClientRuntime`:
- Implement `renderLight()` with `LightRenderer`
- Add `renderCurrentLights()` called from render event
- Maintain light state per emitter instance

### Phase 2h — Persistence

**JSON** (`PslProjectLoader.java`):
```json
{
  "lightType": "SPOT",
  "spotAngle": 45.0,
  "spotSoftness": 0.2,
  "areaWidth": 1.0,
  "areaHeight": 1.0,
  "offset": { "x": 0, "y": 0, "z": 0 },
  "rotation": { "x": 0, "y": 0, "z": 0 },
  "colorTemperature": 0.5
}
```

**Binary** (`LightEmitter.writeData/readData`):
- Append new fields after existing 8 fields (append-only for backward compat)
- Order: lightType, spotAngle, spotSoftness, areaWidth, areaHeight, offset.xyz, rotation.xyz, colorTemperature

---

## Files to Create/Modify

| File | Action |
|------|--------|
| `src/main/java/com/tom/cpm/shared/psl/light/LightEmitter.java` | Add `LightType` enum + 8 new fields + serialization |
| `src/main/java/com/tom/cpm/shared/psl/light/LightRuntime.java` | Add per-type intensity attenuation logic |
| `src/main/java/com/tom/cpm/client/psl/LightRenderer.java` | **NEW** — Additive glow renderer |
| `src/main/java/com/tom/cpm/client/psl/PslClientRuntime.java` | Implement light registration + render loop |
| `src/main/java/com/tom/cpm/shared/psl/IPslRuntime.java` | Add light render hook method |
| `src/main/java/com/tom/cpm/shared/psl/PslSystem.java` | Update `updateDynamicLight()` for types |
| `src/main/java/com/tom/cpm/shared/editor/gui/PslSettingsPanel.java` | Redesign `light()` UI |
| `src/main/java/com/tom/cpm/shared/editor/gui/PslEditorPreview.java` | Upgrade `renderLights()` |
| `src/main/java/com/tom/cpm/shared/psl/io/PslProjectLoader.java` | Add new fields to JSON load/save |
| `src/main/resources/assets/cpm/lang/en_us.json` | Add ~15 new localization keys |

---

## Implementation Order

| Step | Description | Dependencies |
|------|-------------|-------------|
| 1 | `LightType` enum + new fields on `LightEmitter` | None |
| 2 | Binary serialization (append-only) | Step 1 |
| 3 | JSON persistence (PslProjectLoader) | Step 1 |
| 4 | `LightRuntime` per-type attenuation | Step 1 |
| 5 | `LightRenderer` glow rendering (client) | Step 1 |
| 6 | `IPslRuntime` + `PslClientRuntime` integration | Steps 4, 5 |
| 7 | `PslSystem` type-aware light tick | Step 6 |
| 8 | `PslSettingsPanel` UI redesign | Step 1 |
| 9 | `PslEditorPreview` type-specific viz | Step 1 |
| 10 | Localization keys | Step 8 |
| 11 | Compile + test | All |

---

## Localization Keys Needed

```
label.cpm.psl.light.type           → "Light Type"
label.cpm.psl.light.offset         → "Offset"
label.cpm.psl.light.rotation       → "Rotation"
label.cpm.psl.light.colorTemp      → "Color Temperature"
label.cpm.psl.light.spotAngle      → "Spot Angle"
label.cpm.psl.light.spotSoftness   → "Spot Softness"
label.cpm.psl.light.areaWidth      → "Area Width"
label.cpm.psl.light.areaHeight     → "Area Height"
label.cpm.psl.section.typeConfig   → "Type Configuration"
label.cpm.psl.light.type.point     → "Point Light"
label.cpm.psl.light.type.spot      → "Spot Light"
label.cpm.psl.light.type.area      → "Area Light"
label.cpm.psl.light.warm           → "Warm"
label.cpm.psl.light.cool           → "Cool"
```
