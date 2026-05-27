# CPM PSL System (Particle · Sound · Light) — Comprehensive Implementation Plan

**Date:** 2026-05-27  
**Status:** Planning  
**Context:** New subsystem allowing model creators to program particles, sounds, and light emitters via the editor, alongside the existing Model, Texture, and Animation tabs.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [PSL Feature Breakdown](#2-psl-feature-breakdown)
   - 2.1 [Particle System](#21-particle-system)
   - 2.2 [Sound System](#22-sound-system)
   - 2.3 [Light System](#23-light-system)
3. [Project Structure Changes](#3-project-structure-changes)
   - 3.1 [CPMProject Format v2](#31-cpmproject-format-v2)
   - 3.2 [New Folder Layout](#32-new-folder-layout)
   - 3.3 [Backward Compatibility](#33-backward-compatibility)
4. [Architecture Design](#4-architecture-design)
   - 4.1 [Layer Architecture (Portability-First)](#41-layer-architecture-portability-first)
   - 4.2 [Shared/Particle System Design](#42-sharedparticle-system-design)
   - 4.3 [Shared/Sound System Design](#43-sharedsound-system-design)
   - 4.4 [Shared/Light System Design](#44-sharedlight-system-design)
   - 4.5 [Network Synchronization](#45-network-synchronization)
5. [Editor Integration](#5-editor-integration)
   - 5.1 [New Tab: PSL Editor](#51-new-tab-psl-editor)
   - 5.2 [PSL Data Model in Editor](#52-psl-data-model-in-editor)
   - 5.3 [Editor GUI Panels](#53-editor-gui-panels)
6. [Platform-Specific Implementation](#6-platform-specific-implementation)
   - 6.1 [Client Render Pipeline](#61-client-render-pipeline)
   - 6.2 [Minecraft Particle Integration](#62-minecraft-particle-integration)
   - 6.3 [Minecraft Sound Integration](#63-minecraft-sound-integration)
   - 6.4 [Light Rendering (Shader Compatibility)](#64-light-rendering-shader-compatibility)
7. [Serialization & Model File Format](#7-serialization--model-file-format)
   - 7.1 [Binary Format Extension](#71-binary-format-extension)
   - 7.2 [JSON Representation (Editor)](#72-json-representation-editor)
8. [Portability Strategy](#8-portability-strategy)
   - 8.1 [Version Range Coverage (1.7.10 → 26.1)](#81-version-range-coverage-1710--261)
   - 8.2 [Abstraction Points](#82-abstraction-points)
   - 8.3 [Loader-Specific Considerations](#83-loader-specific-considerations)
9. [Implementation Phases](#9-implementation-phases)
10. [Risk Assessment](#10-risk-assessment)

---

## 1. Executive Summary

The PSL (Particle · Physics · Sound · Light) system adds a new dimension to CPM custom player models. Model creators will be able to:

- **Particles**: Design sprite-based particle emitters attached to model bones. Particles respect Minecraft's "Particle Amount" graphics setting.
- **Physics**: Define simple physical bones that respond to gravity and basic force simulation — cloth-like sway, hair bounce, tail wag, etc.
- **Sounds**: Trigger sound effects (.ogg files) stored in the project, primarily for SFX during animations.
- **Lights**: Mark model parts as glowing light sources — rendered as both an emissive (fullbright) layer AND a dynamic light source simultaneously. Designed for parts that glow.

This is implemented as a **new editor tab** (fourth tab: Model | Texture | Animation | **PSL**) with all logic in the shared `com.tom.cpm.shared.psl` package, following CPM's proven portability architecture.

---

## 2. PSL Feature Breakdown

### 2.1 Particle System

**Concept**: Model creators define particle emitters attached to specific model elements. Each emitter spawns 2D sprite particles that can be configured for appearance, motion, and lifecycle.

**Key Properties per Particle Emitter**:

| Property | Type | Description |
|---|---|---|
| `id` | long | Unique emitter identifier (StoreID) |
| `elementId` | int | Target model element (cube) this emitter attaches to |
| `texture` | String | Particle texture filename (from `particles/` folder, PNG) |
| `spriteSize` | Vec2f | Size of each particle in pixels (world units) |
| `spriteUV` | Vec4i | UV coordinates on the particle texture sheet (u, v, w, h) |
| `emitterType` | Enum | `POINT` (single origin), `BOX` (volume), `SPHERE` (volume) |
| `emitterSize` | Vec3f | Dimensions for box/sphere emitters |
| `rate` | float | Particles per second |
| `maxParticles` | int | Maximum alive particles at any time |
| `lifeMin` / `lifeMax` | float | Lifetime range in seconds (random between) |
| `velocity` | Vec3f | Initial velocity vector |
| `velocityVariation` | float | Random variation factor (0 = none, 1 = ±100%) |
| `gravity` | float | Gravity factor (0 = none, 1 = normal Minecraft gravity) |
| `scaleStart` / `scaleEnd` | float | Scale over lifetime (start → end) |
| `colorStart` / `colorEnd` | int | ARGB color over lifetime (with interpolation) |
| `alphaStart` / `alphaEnd` | float | Alpha fade over lifetime |
| `rotationStart` / `rotationEnd` | float | Rotation in degrees over lifetime |
| `collision` | boolean | Whether particles collide with blocks |
| `billboard` | Enum | `FIXED`, `VERTICAL`, `HORIZONTAL`, `CENTER` |
| `blendMode` | Enum | `ALPHA` (transparent), `ADDITIVE` (glow), `MULTIPLY` |
| `trigger` | PslTrigger | When this emitter is active (see trigger system below) |
| `respectGraphicsSetting` | boolean | Whether to respect Minecraft's particle amount setting (default: true) |

**Trigger System** (shared across Particle, Sound, Light):

All PSL elements use a unified trigger system. A trigger defines when the element is active:

```java
public class PslTrigger {
    public enum TriggerType {
        ALWAYS,              // Always active
        ANIMATION,           // Active during a specific animation
        GESTURE,             // Active when a specific gesture is playing
        VANILLA_POSE,        // Active during a specific vanilla pose (sneaking, swimming, etc.)
        VALUE_RANGE,         // Active when a named parameter is within range
        GAME_EVENT,          // Active on specific game events (damage, death, jump, etc.)
        KEYFRAME,            // Triggered at specific animation keyframes (one-shot)
    }

    TriggerType type;
    String animName;         // For ANIMATION / KEYFRAME triggers
    String gestureName;      // For GESTURE triggers
    VanillaPose pose;        // For VANILLA_POSE triggers
    String paramName;        // For VALUE_RANGE triggers
    float paramMin, paramMax;
    GameEvent event;         // For GAME_EVENT triggers
}
```

### 2.2 Physics System

**Concept**: Model creators mark specific bones/elements as "physical". These bones are excluded from standard animation-driven positioning and instead simulated with simple real-time physics — primarily gravity and damping. This enables natural-looking secondary motion: swaying hair, bouncing tails, cloth-like cape movement, jiggly accessories.

**Key Properties per Physics Bone**:

| Property | Type | Description |
|---|---|---|
| `id` | long | Unique identifier |
| `elementId` | int | Target model element (the bone to simulate) |
| `parentElementId` | int | Parent bone this physics bone attaches to (anchor point) |
| `simType` | Enum | `CHAIN` (linked chain of bones), `SINGLE` (one free bone), `CLOTH` (planar cloth patch) |
| `gravity` | float | Gravity multiplier (0 = none, 1 = normal, negative = float up) |
| `damping` | float | 0.0–1.0 velocity damping per tick (higher = more "stiff") |
| `stiffness` | float | 0.0–1.0 how strongly the bone returns to its rest position |
| `mass` | float | Relative mass (affects inertia) |
| `windInfluence` | float | 0.0–1.0 how much wind/movement affects this bone |
| `collisionRadius` | float | Radius for self-collision detection (0 = disabled) |
| `maxStretch` | float | Maximum stretch factor from rest length (1.0 = no stretching) |
| `limitAngleX` / `limitAngleY` / `limitAngleZ` | float | Angular limits in degrees (±, 0 = unlimited) |
| `iterations` | int | Solver iterations per tick (1–10, higher = more stable but costlier) |
| `trigger` | PslTrigger | When physics simulation is active |
| `inheritAnimation` | boolean | If true, blends animation pose with physics result (partial simulation) |

**Physics Simulation Flow**:
```
AnimationEngine.tick()
  → PslSystem.tick(def, playerState)
    → PhysicsRuntime.simulate() for each physics bone:
      1. Read parent bone's current world-space transform
      2. Apply gravity force to physics bone's velocity
      3. Apply damping (velocity *= 1-damping)
      4. Apply stiffness (nudge toward rest position)
      5. Integrate position (Verlet or Euler)
      6. Enforce angular limits (clamp rotation)
      7. Enforce max stretch (constrain distance from parent)
      8. Resolve self-collisions
      9. Write computed transform to bone for rendering
```

**Chain Simulation**: For multi-bone chains (e.g., a tail with 4 segments), each bone is simulated sequentially: parent bone position feeds into child bone anchor. The solver uses Verlet integration for stability.

**Performance**: Physics runs on the shared tick thread. Max 8 physics bones per model, max 5 solver iterations by default. Physics is client-side only (visual).

### 2.3 Sound System

**Concept**: Model creators add sound effect triggers that play .ogg files from the project's `sounds/` folder. Sounds are mono, short-duration SFX (not music).

**Key Properties per Sound Emitter**:

| Property | Type | Description |
|---|---|---|
| `id` | long | Unique identifier |
| `elementId` | int | Target element (positional audio source) |
| `soundFile` | String | .ogg filename from `sounds/` folder |
| `volume` | float | 0.0 – 1.0 |
| `pitch` | float | 0.5 – 2.0 |
| `pitchVariation` | float | Random pitch variation |
| `loop` | boolean | Whether to loop (primarily for ambient SFX) |
| `loopDelay` | float | Seconds between loop iterations (0 = seamless) |
| `attenuation` | Enum | `NONE` (2D, global), `LINEAR`, `INVERSE` (3D position-based) |
| `maxDistance` | float | Max audible distance for 3D sounds |
| `category` | Enum | `PLAYER` (player sounds), `AMBIENT`, `MASTER` |
| `trigger` | PslTrigger | Activation trigger |
| `cooldown` | float | Minimum seconds between trigger firings (debounce) |
| `oneShot` | boolean | If true, only plays once per trigger activation (not retriggering) |

**Sound File Constraints**:
- Format: OGG Vorbis (Minecraft's native sound format)
- Mono only (stereo may cause issues)
- Max file size: 512 KB per file
- Recommended sample rate: 44100 Hz

### 2.4 Light System

**Concept**: Mark model parts as glowing light sources. The part is rendered on BOTH an emissive (fullbright) render layer AND as a dynamic light that illuminates surroundings. The implementation focuses on modern Minecraft (1.20+) with a portable abstraction for version flexibility.

**Key Properties per Light Emitter**:

| Property | Type | Description |
|---|---|---|
| `id` | long | Unique identifier |
| `elementId` | int | Target model element that emits light |
| `color` | int | RGB light color (no alpha needed) |
| `intensity` | float | 0.0 – 1.0 (mapped to light level 0–15) |
| `radius` | float | Light radius in blocks (1.0 – 15.0) |
| `flicker` | boolean | Whether light flickers slightly (torch-like) |
| `flickerSpeed` | float | Flicker animation speed |
| `flickerAmount` | float | 0.0 – 1.0 flicker intensity variation |
| `dynamic` | boolean | Dynamic lighting (follows player movement) vs static |
| `trigger` | PslTrigger | Activation trigger |
| `castShadows` | boolean | If false, disables shadow casting (performance) |

**Light Implementation Strategy**:

Both rendering approaches are applied **simultaneously** — the part always glows visibly AND casts light:

| Layer | How it works |
|---|---|
| **Emissive Render Layer** | Render the element on a dedicated emissive render pass (fullbright, no diffuse lighting). Makes the part always appear at full brightness — it *looks* like it glows. Always active. |
| **Dynamic Light** | Register as a dynamic light source at the element's world-space position. Casts actual light on nearby blocks and entities. Uses Minecraft's `LightEngine` or mod-level dynamic light injection. |

**Dynamic Light — Portable Abstraction**:
Rather than coupling to OptiFine or Iris APIs (which break across versions and shader packs), dynamic light is implemented via a portable abstraction:

```java
// IPslRuntime — platform-agnostic
void registerDynamicLight(int entityId, float x, float y, float z,
                           int color, float level, float radius);
void updateDynamicLight(int entityId, float x, float y, float z);
void unregisterDynamicLight(int entityId);
```

Each platform port (NeoForge 1.21, Fabric 1.20, etc.) implements this using the best available method for that version:
- **1.21 NeoForge**: Inject into client `LightEngine` or use a custom `DynamicLightSource` pattern
- **1.20 Fabric**: Use Fabric API's rendering hooks or Sodium's light pipeline if present
- **1.16.5 Forge**: Use OptiFine's `DynamicLights` API if OF is present; otherwise emissive-only fallback
- **1.12.2**: Emissive-only (dynamic lights too invasive on older engines)

> **⚠ Shader Warning**: Dynamic lighting interacts poorly with most shader packs (Iris, OptiFine shaders). The plan drops OptiFine-specific API integration in favor of a generic approach. When a shader pack is detected, dynamic lights gracefully degrade to emissive-only. A user-facing warning is shown in the editor when dynamic lights are configured and the player has shaders active.

---

## 3. Project Structure Changes

### 3.1 CPMProject Format v2

The `.cpmproject` ZIP structure is extended from v1 to v2:

```
Before (v1):                          After (v2):
my-model.cpmproject                   my-model.cpmproject
├── config.json          (v1)         ├── config.json          (v2)
├── skin.png                          ├── skin.png             ← slot 0: stays at root (compat)
├── description.json                  ├── description.json
├── anim_enc.json                     ├── anim_enc.json
└── animations/                       ├── psl_config.json      ← NEW
│   ├── v_walking.json                ├── textures/            ← NEW: additional custom textures
│   └── c_myPose.json                 │   └── emissive.png     ← emissive map (slot 1+, etc.)
                                      ├── particles/           ← NEW
                                      │   ├── sparkle.png
                                      │   └── smoke.png
                                      ├── sounds/              ← NEW
                                      │   ├── swoosh.ogg
                                      │   └── footstep.ogg
                                      └── animations/
                                          ├── v_walking.json
                                          └── c_myPose.json
```

**Key changes**:
1. `config.json` version bumped to `2`
2. `skin.png` stays at project root for **slot 0** — zero migration pain for existing projects
3. New folders are **purely additive**: `textures/` (additional texture slots), `particles/`, `sounds/`
4. New file: `psl_config.json` — PSL data serialized as JSON
5. v1 projects open as-is; no files move. v2 only adds optional folders/files.

### 3.2 New Folder Layout

| Folder | Purpose | Constraints |
|---|---|---|
| _(root)_ | Slot 0 skin texture (`skin.png`) | PNG, max 8192×8192; kept at root for v1 compatibility |
| `textures/` | Additional texture slots (slot 1+) | PNG, max 4096×4096 per file |
| `particles/` | Particle sprite textures | PNG, max 256×256 per sheet |
| `sounds/` | Sound effect files | OGG Vorbis, mono, max 512 KB |

### 3.3 Backward Compatibility

- v1 projects (`config.json` version = 1) load as before. PSL data defaults to empty.
- v1 projects saved in the editor are **not** auto-upgraded to v2; upgrade occurs when the user explicitly adds a PSL element or an additional texture slot.
- `ProjectIO.loaders` map supports both v1 and v2 loaders. v2 inherits all v1 loaders plus adds `PslLoaderV1`.
- `skin.png` is **always** at the project root. No file moves ever happen. Texture slot 0 = root `skin.png`. Slots 1+ live in `textures/`.

---

## 4. Architecture Design

### 4.1 Layer Architecture (Portability-First)

Following the proven CPM pattern, PSL is split into:

```
cpm/shared/psl/           ← Platform-agnostic logic (largest layer)
├── PslSystem.java        ← Core system: manages all PSL elements for a model
├── PslElement.java       ← Base class for particle/physics/sound/light elements
├── PslTrigger.java        ← Unified trigger system
├── IPslRuntime.java       ← Interface for platform-specific runtime
├── particle/
│   ├── ParticleEmitter.java    ← Emitter definition (data)
│   ├── ParticleInstance.java   ← Single particle state (runtime)
│   └── ParticleRuntime.java    ← Shared particle simulation logic
├── physics/
│   ├── PhysicsBone.java        ← Physics bone definition (data)
│   ├── PhysicsState.java       ← Per-bone runtime state (velocity, position)
│   └── PhysicsRuntime.java     ← Verlet integration, constraint solver
├── sound/
│   ├── SoundEmitter.java       ← Sound emitter definition (data)
│   └── SoundRuntime.java       ← Shared sound trigger logic
├── light/
│   ├── LightEmitter.java       ← Light emitter definition (data)
│   └── LightRuntime.java       ← Shared light state (flicker, intensity)
└── io/
    ├── PslIO.java              ← Serialization (shared write/read logic)
    └── PslProjectLoader.java   ← Project part loader (extends ProjectPartLoader)

cpm/client/psl/           ← Client-specific implementations
├── ParticleRenderer.java       ← Minecraft particle rendering
├── PhysicsRenderer.java        ← Apply physics transforms before render
├── SoundPlayer.java            ← Minecraft sound engine bridge
├── LightRenderer.java          ← Emissive render layer + dynamic light
└── PslClientRuntime.java       ← Implements IPslRuntime for NeoForge client

cpm/common/psl/           ← Common (server-accessible) implementations
└── PslCommonRuntime.java       ← Implements IPslRuntime for server-side

cpl/render/               ← Cross-Platform Library additions
└── ParticleVertexBuffer.java   ← Abstract particle vertex submission (if needed)
```

### 4.2 Shared/Particle System Design

```java
// --- Data Classes ---

public class ParticleEmitter extends PslElement {
    long id;
    int elementId;
    String textureName;           // e.g., "particles/sparkle.png"
    EmitterType emitterType;      // POINT, BOX, SPHERE
    Vec3f emitterSize;
    float rate;
    int maxParticles;
    float lifeMin, lifeMax;
    Vec3f velocity;
    float velocityVariation;
    float gravity;
    float scaleStart, scaleEnd;
    int colorStart, colorEnd;
    float alphaStart, alphaEnd;
    float rotationStart, rotationEnd;
    boolean collision;
    BillboardMode billboard;
    BlendMode blendMode;
    boolean respectGraphicsSetting;
}

public class ParticleInstance {
    Vec3f position;
    Vec3f velocity;
    float age, maxAge;
    float scale;
    int color;
    float alpha;
    float rotation;
    // No Minecraft-specific types — plain Java data
}

// --- Runtime ---

public class ParticleRuntime {
    List<ParticleInstance> activeParticles;
    float spawnTimer;
    // Shared simulation: position update, age, collision (axis-aligned only)
    // Platform-specific: actual rendering, block collision queries

    void tick(ParticleEmitter def, Vec3f worldPos, IPslRuntime runtime) {
        // Spawn new particles based on rate
        // Update existing particles (velocity, gravity, age)
        // Remove dead particles
    }
}
```

**Particle Rendering Flow**:
```
AnimationEngine.tick()
  → PslSystem.tick(def, playerPos)
    → ParticleRuntime.tick() for each emitter
      → Updates particle positions/ages
  → During frame render (PlayerRenderManager)
    → ParticleRenderer.render(activeParticles, camera)
      → Submits billboarded quads to VertexConsumer
      → Uses CustomRenderTypes (translucent/additive blend)
      → Binds particle texture from TextureProvider
```

**Graphics Setting Integration**:
- When `respectGraphicsSetting` is true, query `Minecraft.getInstance().options.particles().get()` (NeoForge) or equivalent.
- Map: `ALL` → 100% particles, `DECREASED` → 50%, `MINIMAL` → 25%
- Multiply `rate` and `maxParticles` by the factor.

### 4.3 Shared/Sound System Design

```java
public class SoundEmitter extends PslElement {
    long id;
    int elementId;
    String soundFile;             // e.g., "sounds/swoosh.ogg"
    float volume;
    float pitch;
    float pitchVariation;
    boolean loop;
    float loopDelay;
    Attenuation attenuation;     // NONE, LINEAR, INVERSE
    float maxDistance;
    SoundCategory category;
    // Trigger inheritance from PslElement
    float cooldown;
    boolean oneShot;
    long lastTriggerTime;        // runtime state, not serialized
}

public class SoundRuntime {
    // Manages cooldown, one-shot state
    // Delegates actual playback to platform-specific SoundPlayer

    boolean shouldPlay(SoundEmitter def, long currentTick) {
        if (def.oneShot && hasPlayed) return false;
        if (currentTick - def.lastTriggerTime < def.cooldown * 20) return false;
        return def.trigger.isActive(currentState);
    }
}
```

**Sound Resource Loading**:
- OGG files are loaded from the project ZIP into a `Map<String, byte[]>` cache.
- On the client, they're registered as Minecraft `SoundEvent` instances via a custom `SoundDefinition`.
- Playback uses `Minecraft.getInstance().getSoundManager().play()`.
- Server-side: Only metadata exists (no playback). Sound triggers are purely client-side.

### 4.4 Shared/Physics System Design

```java
public class PhysicsBone extends PslElement {
    long id;
    int elementId;
    int parentElementId;         // Anchor bone (must not be another physics bone)
    SimType simType;             // CHAIN, SINGLE, CLOTH
    float gravity;               // Multiplier (1.0 = normal gravity)
    float damping;               // 0.0–1.0 per-tick velocity retention
    float stiffness;             // 0.0–1.0 return-to-rest force
    float mass;                  // Relative mass for inertia
    float windInfluence;         // 0.0–1.0 response to player movement
    float collisionRadius;       // Self-collision sphere radius (0 = off)
    float maxStretch;            // Max stretch from rest (1.0 = no stretch)
    float limitAngleX, Y, Z;    // ±degree angular limits (0 = unlimited)
    int iterations;              // 1–10 solver sub-steps per tick
    boolean inheritAnimation;    // Blend animation + physics result
}

public class PhysicsState {
    Vec3f position;              // Current world-space position
    Vec3f previousPosition;     // Previous position (Verlet integration)
    Vec3f velocity;              // Velocity (for damping/stiffness)
    Vec3f restPosition;          // Original animation pose (for stiffness)
    Vec3f restRotation;          // Original rotation
    boolean initialized;
}

public class PhysicsRuntime {
    // Verlet integration — more stable than Euler for constraints
    // Processes bones top-down (parent → child chain order)

    void simulate(PhysicsBone def, PhysicsState state, Vec3f parentWorldPos,
                  Vec3f parentVelocity, float dt, IPslRuntime runtime) {
        // 1. Compute forces: gravity + wind (from parent velocity * windInfluence)
        // 2. Verlet integrate: newPos = pos + (pos - prevPos) * damping + force * dt²
        // 3. Apply stiffness: nudge toward restPosition
        // 4. Constrain distance from parent: clamp to maxStretch * restLength
        // 5. Clamp angular limits on each axis
        // 6. Self-collision: sphere-sphere check against sibling physics bones
        // 7. Write result back to state for rendering
    }
}
```

**Physics Integration into Render Pipeline**:
```
AnimationEngine.tick()
  → Standard animation pose computed (existing)
  → PslSystem.tick()
    → PhysicsRuntime.simulate() for each physics bone (in parent→child order)
      → Computes new world-space transforms
  → During frame render (PlayerRenderManager):
    → If inheritAnimation: lerp(animPose, physicsPose, blendFactor)
    → If not: replace bone transform with physics result entirely
    → Render model with physics-modified transforms
```

**Chain Ordering**: Physics bones are automatically topologically sorted. A bone's parent must be simulated before the bone itself. The system detects cycles and rejects invalid configurations at edit time.

### 4.5 Shared/Light System Design

```java
public class LightEmitter extends PslElement {
    long id;
    int elementId;
    int color;                   // RGB packed int
    float intensity;             // 0.0 – 1.0
    float radius;                // 1.0 – 15.0 blocks
    boolean flicker;
    float flickerSpeed;
    float flickerAmount;
    boolean dynamic;
    boolean castShadows;
}

public class LightRuntime {
    // Computes current light level based on trigger state and flicker animation
    // Flicker: sinusoidal oscillation around intensity

    float getCurrentIntensity(LightEmitter def, long tickCounter) {
        if (!def.trigger.isActive(currentState)) return 0;
        float base = def.intensity;
        if (def.flicker) {
            float flicker = (float) Math.sin(tickCounter * def.flickerSpeed * 0.1);
            base += flicker * def.flickerAmount;
            base = Math.max(0, Math.min(1, base));
        }
        return base;
    }
}
```

**Light Rendering — Two Approaches**:

**Approach A: Emissive Render Layer** (Always Available)
```
During ModelRenderManager.render():
  1. Render normal model pass (existing)
  2. If light elements are active:
     a. Bind emissive render type (RenderType.eyes() with custom shader)
     b. Re-render only the emissive elements
     c. Use fullbright (disable diffuse lighting)
     d. Apply light color as vertex color multiplier
```
This makes the part appear to glow without casting light on surroundings.

**Approach B: Dynamic Light Injection** (OptiFine/Iris Only)
```
During player render:
  1. Check CPMMixinPlugin detection for OptiFine/Iris
  2. If supported, register light sources:
     - OptiFine: Use DynamicLights API or inject at entity position
     - Iris: Register custom light via Iris's pipeline
  3. Update light position each frame based on bone world position
  4. Light level = intensity * 15
  5. Radius = radius in blocks
```

**Shader Compatibility Matrix**:

| Environment | Emissive Layer | Dynamic Light | Notes |
|---|---|---|---|
| Vanilla (no shaders) | ✅ Always works | ✅ Works | Both layers active |
| Sodium (no shaderpack) | ✅ Works | ⚠️ May need Sodium-specific hook | Emissive always works; dynamic light port-dependent |
| OptiFine (no shaderpack) | ✅ Works | ✅ Works (generic injection) | No OF-specific API used |
| Shader pack active (any) | ⚠️ May look washed out | ❌ Degraded → emissive-only | **Warn user in editor** |

**Default behavior**: Both emissive and dynamic light are active by default. When shaders are detected at runtime, dynamic lights are silently disabled (emissive still works). The editor shows a warning badge on light elements when the player currently has shaders active.

### 4.6 Network Synchronization

Particle, sound, and light state needs to sync between server and clients for multiplayer.

**Synchronization Strategy**:

| Element | Sync Method | Rationale |
|---|---|---|
| **Particle emitters** | Model definition (embedded in .cpmmodel binary) + trigger state via existing animation sync | Emitter definitions are part of the model. Particle state is purely visual (client-side). Trigger activation is already synced via GestureC2S / ServerAnimationS2C. |
| **Physics bones** | Client-side only | Physics is purely visual — cosmetic secondary motion. Server has no concept of physics state. Each client simulates independently. |
| **Sound triggers** | Client-side only | Sounds are played on the client that renders the player. No server sync needed. Trigger state syncs via existing animation packets. |
| **Light emitters** | Model definition + trigger state | Same as particles. Both emissive and dynamic light are client-side only. |

**New Packet** (if needed):
- `PslEventS2C` — Server broadcasts PSL-specific events (e.g., "play sound X for player Y at position Z")
- Actually, this can reuse the existing `ReceiveEventS2C` + `ModelEventType` extension.

**ServerCaps Addition**:
```java
// Add to ServerCaps.java:
PSL_SYSTEM,  // Server supports PSL data in model definitions
```

---

## 5. Editor Integration

### 5.1 New Tab: PSL Editor

Add a fourth tab to `EditorGui.initFrame()`:

```java
// In EditorGui.initFrame(), after initAnimPanel():
initPslPanel(width, height);
```

Update `ViewType` enum:

```java
public static enum ViewType {
    MODEL,
    TEXTURE,
    ANIMATION,
    PSL,        // ← NEW
    ;
    public static final ViewType[] VALUES = values();
}
```

### 5.2 PSL Data Model in Editor

Add to `Editor.java`:

```java
// PSL state
public final List<PslElement> pslElements = new ArrayList<>();
public PslElement selectedPslElement;
public final Updater<Boolean> setPslSelection = updaterReg.create(null);

// Particle-specific
public final Updater<Float> setParticleRate = updaterReg.create(null);
// ... more updaters

// Sound-specific
public final Updater<String> setSoundFile = updaterReg.create(null);
// ... more updaters

// Light-specific
public final Updater<Integer> setLightColor = updaterReg.create(null);
public final Updater<Float> setLightIntensity = updaterReg.create(null);
// ... more updaters
```

### 5.3 Editor GUI Panels

**PSL Tab Layout** (similar to Animation tab):

```
┌──────────────────────────────────────────────────────────┐
│  File  Edit  Effect  Display  [Model][Texture][Anim][PSL]│
├────────┬─────────────────────────────────────┬───────────┤
│ PSL    │                                     │  Tree     │
│ List   │         Viewport (3D preview)       │  Panel    │
│ Panel  │         with particle preview       │           │
│ 170px  │                                     │  150px    │
│        │                                     │           │
│ [+]Add ├─────────────────────────────────────┤           │
│ [✕]Del │  Properties Panel (context-sensitive)│           │
│        │  ┌─────────────────────────────────┐│           │
│  Type: │  │ Particle | Sound | Light  tabs  ││           │
│  ☑ Par │  │                                 ││           │
│  ☐ Snd │  │ [property fields vary by type] ││           │
│  ☑ Lit │  │                                 ││           │
│        │  └─────────────────────────────────┘│           │
│        │  Quick Actions                     │           │
└────────┴─────────────────────────────────────┴───────────┘
```

**PSL List Panel** (left, 170px):
- Lists all PSL elements grouped by type (Particle, Physics, Sound, Light)
- Type filter checkboxes (show/hide each type)
- Add button → dropdown: "Add Particle Emitter", "Add Physics Bone", "Add Sound Emitter", "Add Light Emitter"
- Delete button for selected element
- Each entry shows: type icon + element name + trigger summary

**Properties Panel** (bottom, context-sensitive):
- Tabbed sub-panel with Particle/Physics/Sound/Light tabs (only relevant tab shown)
- Particle tab: texture picker, rate spinner, lifetime range, color picker with gradient preview, etc.
- Physics tab: sim type selector, gravity/damping/stiffness sliders, angular limit spinners, collision radius, preview play button
- Sound tab: file picker (browse `sounds/` folder), volume slider, pitch spinner, loop toggle
- Light tab: color picker, intensity slider, radius spinner, flicker settings

**Tree Panel** (right, 150px):
- Shows model element tree for selecting attachment targets
- PSL indicator icons on elements that have PSL emitters attached

---

## 6. Platform-Specific Implementation

### 6.1 Client Render Pipeline

The PSL runtime hooks into the existing `PlayerRenderManager` flow:

```
PlayerRenderManager.bindModel(model, bufferSource, def, player, state)
  → RedirectHolder.swapIn()
    → During render:
      1. Render custom model (existing)
      2. PslSystem.renderParticles(poseStack, bufferSource, camera, partialTicks)
      3. PslSystem.renderEmissiveLayer(poseStack, bufferSource) ← light approach A
      4. PslSystem.updateDynamicLights(player) ← light approach B (if enabled)
```

### 6.2 Minecraft Particle Integration

For NeoForge 1.21:

```java
// ParticleRenderer.java (client)
public class ParticleRenderer {
    private final Map<String, ResourceLocation> particleTextures = new HashMap<>();

    public void render(List<ParticleInstance> particles, PoseStack poseStack,
                       MultiBufferSource bufferSource, Camera camera, float partialTicks) {
        // Group particles by texture for batch rendering
        // For each particle:
        //   - Compute screen-space position
        //   - Compute billboard quad vertices
        //   - Apply color, alpha, scale, rotation
        //   - Submit to VertexConsumer with appropriate RenderType

        VertexConsumer builder = bufferSource.getBuffer(
            CustomRenderTypes.entityColorTranslucent() // or additive
        );

        for (ParticleInstance p : particles) {
            // Billboard computation
            Vec3f cameraPos = camera.getPosition();
            float x = p.position.x - (float)cameraPos.x;
            float y = p.position.y - (float)cameraPos.y;
            float z = p.position.z - (float)cameraPos.z;

            // Quad submission (similar to Minecraft's ParticleRenderType)
            submitBillboardQuad(builder, poseStack, x, y, z, p);
        }
    }
}
```

**Minecraft version adaptation**: For older versions (1.7.10, 1.12.2), the particle rendering API differs significantly. Each platform port will need its own `ParticleRenderer` implementation, but the shared `ParticleRuntime` and `ParticleInstance` classes remain the same.

### 6.3 Minecraft Sound Integration

```java
// SoundPlayer.java (client)
public class SoundPlayer {
    private final Map<String, SoundEvent> registeredSounds = new HashMap<>();

    public void playSound(SoundEmitter def, Vec3f worldPos) {
        SoundEvent event = getOrRegisterSound(def.soundFile);
        float pitch = def.pitch + (random.nextFloat() * 2 - 1) * def.pitchVariation;

        Minecraft.getInstance().getSoundManager().play(
            new MovingSoundInstance(event, SoundSource.valueOf(def.category.name()),
                worldPos, def.volume, pitch, def.loop, def.attenuation, def.maxDistance)
        );
    }
}
```

**Sound file hosting**: Sound files are embedded in the `.cpmmodel` binary or `.cpmproject` ZIP. On the client, they're extracted to a temporary cache directory and registered as Minecraft resources via the resource pack system.

### 6.4 Light Rendering (Shader Compatibility)

**Implementation in `CustomRenderTypes`**:

```java
// New render type for emissive elements
public static final RenderType ENTITY_EMISSIVE = create(
    "cpm:entity_emissive",
    DefaultVertexFormat.NEW_ENTITY,
    VertexFormat.Mode.QUADS,
    256,
    false,
    true,
    RenderType.CompositeState.builder()
        .setShaderState(RENDERTYPE_EYES_SHADER)     // Fullbright shader
        .setTextureState(BLOCK_SHEET_MIPPED)
        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
        .setCullState(NO_CULL)
        .setLightmapState(LIGHTMAP)                  // Still reads lightmap for ambient
        .setOverlayState(OVERLAY)
        .createCompositeState(false)
);
```

**OptiFine Dynamic Lights**:

```java
// In LightRenderer.java
if (CustomPlayerModelsClient.optifineLoaded) {
    // Use OptiFine's dynamic lights API
    // Register light at entity position each frame
    // The OF detector already exists in CPMMixinPlugin
    DynamicLightsAPI.addLight(player, lightLevel);
}
```

**Iris Compatibility**:

```java
if (ClientBase.irisLoaded) {
    // Register with Iris's light pipeline
    // This requires Iris-specific API access
    // Fallback to emissive rendering if Iris blocks dynamic lights
}
```

---

## 7. Serialization & Model File Format

### 7.1 Binary Format Extension (.cpmmodel)

The existing `ModelFile` binary format is extended with a new optional block for PSL data:

```
Existing format:
  HEADER(0x53) + [UTF name] + [UTF desc] + [byte[] dataBlock] +
  [byte[] overflowLocal] + [Link] + [Image icon] + [Checksum]

Extended format:
  HEADER(0x53) + [UTF name] + [UTF desc] + [byte[] dataBlock] +
  [byte[] overflowLocal] + [Link] + [Image icon] +
  [byte[] pslBlock] +     ← NEW: PSL data (optional, empty if no PSL)
  [Checksum]
```

The `pslBlock` is a serialized binary format using `IOHelper`:

```
PSL Block:
  int particleCount
  for each particle:
    long id
    int elementId
    UTF textureName
    byte emitterType (0=POINT, 1=BOX, 2=SPHERE)
    Vec3f emitterSize
    float rate
    int maxParticles
    float lifeMin, lifeMax
    Vec3f velocity
    float velocityVariation
    float gravity
    float scaleStart, scaleEnd
    int colorStart, colorEnd (ARGB)
    float alphaStart, alphaEnd
    float rotationStart, rotationEnd
    bool collision
    byte billboardMode
    byte blendMode
    bool respectGraphicsSetting
    PslTrigger trigger  ← serialized trigger

  int physicsCount
  for each physics bone:
    long id
    int elementId
    int parentElementId
    byte simType (0=CHAIN, 1=SINGLE, 2=CLOTH)
    float gravity
    float damping
    float stiffness
    float mass
    float windInfluence
    float collisionRadius
    float maxStretch
    float limitAngleX, Y, Z
    int iterations
    bool inheritAnimation
    PslTrigger trigger

  int soundCount
  for each sound:
    long id
    int elementId
    UTF soundFile
    float volume
    float pitch
    float pitchVariation
    bool loop
    float loopDelay
    byte attenuation
    float maxDistance
    byte category
    PslTrigger trigger
    float cooldown
    bool oneShot

  int lightCount
  for each light:
    long id
    int elementId
    int color (RGB)
    float intensity
    float radius
    bool flicker
    float flickerSpeed
    float flickerAmount
    bool dynamic
    bool castShadows
    PslTrigger trigger
```

### 7.2 JSON Representation (Editor)

**`psl_config.json`** (in .cpmproject):

```json
{
  "version": 1,
  "particles": [
    {
      "id": 1234567890,
      "name": "Sparkle Trail",
      "elementId": 5,
      "texture": "particles/sparkle.png",
      "emitterType": "POINT",
      "emitterSize": { "x": 0, "y": 0, "z": 0 },
      "rate": 10.0,
      "maxParticles": 50,
      "lifeMin": 0.5,
      "lifeMax": 1.5,
      "velocity": { "x": 0, "y": 0.1, "z": 0 },
      "velocityVariation": 0.5,
      "gravity": 0.0,
      "scaleStart": 1.0,
      "scaleEnd": 0.0,
      "colorStart": "#FFFFFFFF",
      "colorEnd": "#00FFFFFF",
      "alphaStart": 1.0,
      "alphaEnd": 0.0,
      "rotationStart": 0,
      "rotationEnd": 360,
      "collision": false,
      "billboard": "CENTER",
      "blendMode": "ADDITIVE",
      "respectGraphicsSetting": true,
      "trigger": {
        "type": "ALWAYS"
      }
    }
  ],
  "physics": [
    {
      "id": 1234567893,
      "name": "Tail Chain",
      "elementId": 12,
      "parentElementId": 3,
      "simType": "CHAIN",
      "gravity": 0.8,
      "damping": 0.3,
      "stiffness": 0.2,
      "mass": 0.5,
      "windInfluence": 0.6,
      "collisionRadius": 0.0,
      "maxStretch": 1.05,
      "limitAngleX": 45,
      "limitAngleY": 30,
      "limitAngleZ": 45,
      "iterations": 3,
      "inheritAnimation": false,
      "trigger": {
        "type": "ALWAYS"
      }
    }
  ],
  "sounds": [
    {
      "id": 1234567891,
      "name": "Swoosh SFX",
      "elementId": 3,
      "soundFile": "sounds/swoosh.ogg",
      "volume": 0.8,
      "pitch": 1.0,
      "pitchVariation": 0.1,
      "loop": false,
      "loopDelay": 0,
      "attenuation": "LINEAR",
      "maxDistance": 16.0,
      "category": "PLAYER",
      "cooldown": 0.5,
      "oneShot": true,
      "trigger": {
        "type": "ANIMATION",
        "animName": "c_attack"
      }
    }
  ],
  "lights": [
    {
      "id": 1234567892,
      "name": "Eye Glow",
      "elementId": 8,
      "color": "#FF4444",
      "intensity": 0.7,
      "radius": 3.0,
      "flicker": false,
      "flickerSpeed": 1.0,
      "flickerAmount": 0.1,
      "dynamic": false,
      "castShadows": false,
      "trigger": {
        "type": "ALWAYS"
      }
    }
  ]
}
```

---

## 8. Portability Strategy

### 8.1 Version Range Coverage (1.7.10 → 26.1)

The PSL system must work across Minecraft versions from 1.7.10 to 1.26.1. The key portability challenges:

| Concern | 1.7.10 | 1.12.2 | 1.16.5 | 1.20+ | 1.21+ (NeoForge) |
|---|---|---|---|---|---|
| Particle API | `World.spawnParticle()` simple | Same | `ParticleEngine` changes | `ParticleEngine` refactored | Current |
| Sound API | `SoundManager.playSound()` | Same | `SoundEngine` rename | Same | Current |
| Render Types | `GL11` direct | `GlStateManager` | `RenderType` system | Same | Same |
| Dynamic Lights | Manual GL | Manual GL | OptiFine | OptiFine/Iris | OptiFine/Iris |
| Resource Loading | `ResourceLocation` | Same | Same | Same | Same |
| Vertex Format | `Tessellator` | Same | `BufferBuilder` | Same | Same |

**Strategy**: Each platform port (NeoForge 1.21, Fabric 1.20, etc.) implements the `IPslRuntime` interface, which abstracts all platform-specific operations. The shared logic in `cpm/shared/psl/` never references Minecraft classes directly.

### 8.2 Abstraction Points

```java
public interface IPslRuntime {
    // Particle
    void spawnParticle(float x, float y, float z, float vx, float vy, float vz,
                       int color, float scale, ResourceLocation texture);
    boolean checkBlockCollision(float x, float y, float z);

    // Sound
    void playSound(ResourceLocation sound, float x, float y, float z,
                   float volume, float pitch, boolean loop, float maxDist);

    // Light
    void registerDynamicLight(int entityId, float x, float y, float z,
                              int color, float intensity, float radius);
    void unregisterDynamicLight(int entityId);
    boolean isDynamicLightSupported();

    // Graphics settings
    float getParticleAmountFactor();  // 1.0, 0.5, 0.25 based on setting
    boolean isShaderPackActive();

    // Resource access
    InputStream getResource(String path);  // reads from project cache
}
```

### 8.3 Loader-Specific Considerations

The existing CPL abstraction layer (`com.tom.cpl`) already handles most version differences. PSL extends this pattern:

- **`com.tom.cpl.render.ParticleVertexBuffer`** — Abstract particle vertex submission
- **`com.tom.cpl.audio.SoundHandle`** — Abstract sound playback handle
- **`com.tom.cpl.render.LightHandle`** — Abstract dynamic light registration

Each port provides concrete implementations via dependency injection through the existing `MinecraftClientAccess` / `MinecraftCommonAccess` pattern.

---

## 9. Implementation Phases

### Phase 1: Foundation (Estimated: 2–3 weeks)

**Goal**: Data model, serialization, project format v2, and editor tab skeleton.

| Task | Files | Notes |
|---|---|---|
| 1.1 Create `psl/` package structure | `shared/psl/**` | Following existing package conventions |
| 1.2 Define data classes | `PslElement.java`, `ParticleEmitter.java`, `SoundEmitter.java`, `LightEmitter.java`, `PslTrigger.java` | Pure data, no MC dependencies |
| 1.3 Implement PSL serialization | `PslIO.java`, extend `ModelFile.java` | Binary format for .cpmmodel |
| 1.4 Create `PslProjectLoader` | `shared/editor/project/loaders/PslLoaderV1.java` | Implements `ProjectPartLoader` |
| 1.5 Extend `ProjectIO` for v2 | `ProjectIO.java` | Register v2 loaders, bump `projectFileVersion` to 2 |
| 1.6 Add `IProject` folder support for `particles/`, `sounds/`, `textures/` | `ProjectFile.java` | Ensure new folders are read/written (additive only; root `skin.png` unchanged) |
| 1.7 `TexturesLoaderV1` — no changes needed | `TexturesLoaderV1.java` | `skin.png` stays at root; no lookup changes |
| 1.8 Add `ViewType.PSL`, editor tab skeleton | `EditorGui.java`, `ModeDisplayType.java` | Empty PSL tab with "Coming Soon" |
| 1.9 Add `PSL_SYSTEM` to `ServerCaps` | `ServerCaps.java` | Capability flag |

### Phase 2: Particle System (Estimated: 2–3 weeks)

**Goal**: Full particle editor and runtime.

| Task | Files | Notes |
|---|---|---|
| 2.1 Build PSL list panel GUI | `editor/gui/PslPanel.java`, `editor/gui/PslListPanel.java` | Add/edit/delete PSL elements |
| 2.2 Build particle properties panel | `editor/gui/ParticlePropertiesPanel.java` | All particle settings |
| 2.3 Build trigger editor | `editor/gui/PslTriggerEditor.java` | Reusable for all PSL types |
| 2.4 Implement `ParticleRuntime` (shared) | `shared/psl/particle/ParticleRuntime.java` | Tick simulation, particle lifecycle |
| 2.5 Implement `ParticleRenderer` (NeoForge 1.21) | `client/psl/ParticleRenderer.java` | Billboard rendering, texture binding |
| 2.6 Implement graphics setting integration | `ParticleRenderer.java` | Respect particle amount setting |
| 2.7 Add particle preview in viewport | `ViewportPanel.java` extension | In-editor particle visualization |
| 2.8 Texture picker for particles | `TexturePickerPopup.java` or reuse existing | Browse `particles/` folder |

### Phase 3: Physics System (Estimated: 2–3 weeks)

**Goal**: Full physics bone editor and runtime simulation.

| Task | Files | Notes |
|---|---|---|
| 3.1 Build physics properties panel | `editor/gui/PhysicsPropertiesPanel.java` | Gravity, damping, stiffness, limits |
| 3.2 Implement `PhysicsRuntime` (shared) | `shared/psl/physics/PhysicsRuntime.java` | Verlet integration, constraint solver |
| 3.3 Implement `PhysicsRenderer` (NeoForge 1.21) | `client/psl/PhysicsRenderer.java` | Apply physics transforms to render pipeline |
| 3.4 Chain ordering & cycle detection | `PhysicsRuntime.java` | Topological sort, validation |
| 3.5 Physics preview in editor | `ViewportPanel.java` extension | Live physics simulation in viewport |
| 3.6 Self-collision (sphere-sphere) | `PhysicsRuntime.java` | Simple sphere collision between sibling bones |

### Phase 4: Sound System (Estimated: 1–2 weeks)

**Goal**: Full sound editor and runtime.

| Task | Files | Notes |
|---|---|---|
| 3.1 Build sound properties panel | `editor/gui/SoundPropertiesPanel.java` | All sound settings |
| 3.2 Implement `SoundRuntime` (shared) | `shared/psl/sound/SoundRuntime.java` | Trigger debounce, cooldown |
| 3.3 Implement `SoundPlayer` (NeoForge 1.21) | `client/psl/SoundPlayer.java` | OGG loading, Minecraft sound playback |
| 3.4 Sound file picker | `SoundFilePicker.java` | Browse `sounds/` folder |
| 3.5 Audio preview in editor | `SoundPlayer.java` | "Test Sound" button |

### Phase 5: Light System (Estimated: 2–3 weeks)

**Goal**: Full light editor and rendering, with shader compatibility.

| Task | Files | Notes |
|---|---|---|
| 5.1 Build light properties panel | `editor/gui/LightPropertiesPanel.java` | All light settings |
| 5.2 Implement `LightRuntime` (shared) | `shared/psl/light/LightRuntime.java` | Flicker computation |
| 5.3 Implement emissive render layer | `client/psl/LightRenderer.java`, `CustomRenderTypes.java` | Fullbright pass (always active) |
| 5.4 Implement dynamic light via portable abstraction | `client/psl/LightRenderer.java`, `IPslRuntime.java` | `registerDynamicLight()` etc. |
| 5.5 Shader compatibility detection & graceful degradation | `LightRenderer.java` | Auto-disable dynamic lights when shader pack active; editor warning badge |
| 5.6 Light preview in editor | `ViewportPanel.java` extension | Visualize light radius |

### Phase 6: Network Sync & Polish (Estimated: 1 week)

**Goal**: Multiplayer sync, edge cases, documentation.

| Task | Files | Notes |
|---|---|---|
| 6.1 PSL data in model distribution | `SetSkinC2S`, `SetSkinS2C`, `NetHandler.java` | Ensure PSL block is included in model payload |
| 6.2 Add PSL-related `ModelEventType` entries | `ModelEventType.java` | If needed for custom events |
| 6.3 Backward compatibility testing | Various | Ensure v1 projects still load |
| 6.4 Performance profiling | `ParticleRuntime.java`, `PhysicsRuntime.java`, `LightRenderer.java` | Limit particles, cap physics iterations, cull distant lights |
| 6.5 User documentation | Wiki, tooltips | In-editor tooltips + external docs |

---

## 10. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Shader incompatibility with dynamic lights** | High | Medium | Default to emissive-only; make dynamic lights opt-in with warning |
| **Physics simulation cost** | Medium | Medium | Max 8 physics bones, solver iterations capped at 5; skip when model off-screen |
| **Particle rendering performance** | Medium | Medium | Limit max particles per emitter; cull off-screen; use batched rendering |
| **Sound file size bloating .cpmmodel** | Medium | Low | Enforce 512 KB per-file limit; compress OGG at low bitrate |
| **v1 → v2 project migration issues** | Low | Low | `skin.png` stays at root — no files move. v2 is additive-only; v1 projects open unchanged. |
| **Porting complexity (1.7.10 particle API)** | Low | Medium | `IPslRuntime` abstraction handles this; each port implements separately |
| **Network bandwidth (PSL data in model payload)** | Low | Low | PSL data is small (mostly numeric); only sent once on model change |
| **OptiFine/Iris version-specific API changes** | Low | Low | No OptiFine-specific API used. Dynamic lights use portable abstraction; degrade to emissive-only if unsupported. |

---

## Appendix A: Key Files Reference

### New Files to Create

```
src/main/java/com/tom/cpm/shared/psl/
├── PslSystem.java
├── PslElement.java
├── PslTrigger.java
├── IPslRuntime.java
├── particle/
│   ├── ParticleEmitter.java
│   ├── ParticleInstance.java
│   └── ParticleRuntime.java
├── physics/
│   ├── PhysicsBone.java
│   ├── PhysicsState.java
│   └── PhysicsRuntime.java
├── sound/
│   ├── SoundEmitter.java
│   └── SoundRuntime.java
├── light/
│   ├── LightEmitter.java
│   └── LightRuntime.java
└── io/
    ├── PslIO.java
    └── PslProjectLoader.java

src/main/java/com/tom/cpm/client/psl/
├── PslClientRuntime.java
├── ParticleRenderer.java
├── PhysicsRenderer.java
├── SoundPlayer.java
└── LightRenderer.java

src/main/java/com/tom/cpm/shared/editor/gui/
├── PslPanel.java
├── PslListPanel.java
├── ParticlePropertiesPanel.java
├── PhysicsPropertiesPanel.java
├── SoundPropertiesPanel.java
├── LightPropertiesPanel.java
└── PslTriggerEditor.java
```

### Existing Files to Modify

| File | Changes |
|---|---|
| `EditorGui.java` | Add `ViewType.PSL`, `initPslPanel()`, PSL tab |
| `Editor.java` | Add PSL state fields and updaters |
| `ModeDisplayType.java` | Add PSL-related display types |
| `ProjectIO.java` | Bump version to 2, register PslLoaderV1 |
| `ProjectFile.java` | Ensure new folders supported |
| `TexturesLoaderV1.java` | No changes — `skin.png` stays at root |
| `ModelFile.java` | Extended binary format with PSL block |
| `ServerCaps.java` | Add `PSL_SYSTEM` |
| `CustomRenderTypes.java` | Add emissive render type |
| `PlayerRenderManager.java` | Hook PSL rendering into pipeline (particles, physics transforms, lights) |
| `AnimationEngine.java` | Tick PSL runtime |
| `ModelDefinition.java` | Add PSL data to model definition |
| `Exporter.java` | Include PSL data in exports |
| `ConfigKeys.java` | Add PSL-related config keys |

---

## Appendix B: CPMProject v2 `config.json` Structure

```json
{
  "version": 2,
  "texturesFolder": "textures",
  "particlesFolder": "particles",
  "soundsFolder": "sounds",
  "elements": [ /* ... existing ... */ ],
  "textures": { /* ... existing ... */ }
}
```

Slot 0 (`skin.png`) is always at the project root — no folder field needed. The `texturesFolder`, `particlesFolder`, and `soundsFolder` fields are optional and default to the standard folder names. This allows future flexibility if folder naming conventions change.

**Texture slot resolution**:
- **Slot 0**: `<project root>/skin.png` (always, for v1 compat)
- **Slots 1+**: `<texturesFolder>/<slotName>.png` (e.g., `textures/emissive.png`)

---

*End of PSL System Comprehensive Plan*
