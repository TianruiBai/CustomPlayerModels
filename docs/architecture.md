# CustomPlayerModels (CPM) — Architecture & Feature Documentation

## Overview

**CustomPlayerModels** (mod ID: `cpm`) is a NeoForge Minecraft mod that allows players to design, load, and display completely custom player avatars. Players can create custom body geometries, apply custom textures, define animations and gestures, and optionally sync their models to other players on a server.

The mod is built to be multi-loader capable via an internal cross-platform abstraction library (`com.tom.cpl`), meaning a large portion of the logic is platform-agnostic and can be shared across NeoForge, Fabric, and server-side (Bukkit) implementations.

---

## High-Level Feature Set

| Feature | Description |
|---|---|
| Custom model editor | In-game GUI editor for building player avatar geometry and animations |
| Custom textures | Per-model textures, skin type support (Classic 64x64, Slim 64x64) |
| Custom animations | Poses, gestures, and layered animations driven by game state |
| Server distribution | Servers broadcast player models to all connected clients |
| Server-forced models | Servers can assign models to players (persistent or temporary) |
| Gesture & emote system | Players trigger named animations; state synced server-wide |
| Voice integration | External mods can register voice level providers for lip-sync |
| Cloud loaders | Import models from Pastebin, GitHub Gist, GitHub repos, ModelsCDN, HTTP |
| Plugin API | External mods integrate via `ICPMPlugin` / `IClientAPI` / `ICommonAPI` |
| OptiFine & Iris compat | Shader-compatible rendering paths detected at startup |
| ViveCraft VR support | VR-specific rendering handled via mixin plugin detection |

---

## Repository Layout

```
src/main/java/com/tom/
├── cpl/          # Cross-Platform Library — abstraction layer
│   ├── config/   # JSON config file system
│   ├── command/  # Command registration abstractions
│   ├── gui/      # GUI widget abstractions
│   ├── math/     # Vec2i, Vec3f, MatrixStack
│   ├── nbt/      # NBT data wrappers
│   ├── render/   # Rendering abstractions
│   ├── tag/      # Tag system (AllTagManagers)
│   ├── text/     # Text/style helpers
│   └── util/     # Image, logging, misc utilities
│
└── cpm/          # Mod core
    ├── api/      # Public plugin API
    ├── client/   # Client-only: rendering, GUI, key bindings
    ├── common/   # Shared server+client: network, config, handlers
    ├── mixin/    # Bytecode injection hooks
    ├── mixinplugin/ # Startup feature detection (OptiFine, Iris, VR)
    ├── externals/   # Third-party library integration
    └── shared/   # Platform-agnostic business logic (largest package)
        ├── animation/   # Animation engine, pose types, registry
        ├── config/      # ModConfig, ConfigKeys, player profiles
        ├── definition/  # ModelDefinition, ModelDefinitionLoader
        ├── editor/      # In-game editor GUI and rendering
        ├── io/          # ModelFile format, serialization
        ├── loaders/     # Remote resource loaders (HTTP, GitHub, etc.)
        ├── model/       # Model parts, cubes, bone hierarchy
        ├── network/     # Packet definitions and handlers
        ├── parts/       # Model composition interfaces
        ├── retro/       # Legacy player renderer
        ├── skin/        # TextureProvider, TextureType
        └── util/        # VersionCheck, ScalingOptions, ModelLoadingPool
```

---

## Module Descriptions

### `com.tom.cpl` — Cross-Platform Library

CPL is an internal library that decouples the mod's core logic from any specific mod loader or Minecraft version. Every significant Minecraft operation (NBT, items, blocks, text, rendering) is accessed through a CPL abstraction, with the NeoForge-specific implementation provided by `CommonBase` and `ClientBase` in the `cpm` package.

Key components:

- **`ModConfigFile`** — Hierarchical JSON config backed by a file; supports change listeners, string/int/boolean getters, and set serialization.
- **`AllTagManagers`** — Unified registry for Minecraft tag managers.
- **`Image`** — Platform-agnostic image wrapper used for model icons and textures.
- **`MatrixStack`, `Vec3f`, `Vec2i`** — Math utilities mirroring Minecraft's types.

---

### `com.tom.cpm.CustomPlayerModels` — Mod Entry Point

Annotated with `@Mod("cpm")`. Responsible for:

1. **`FMLClientSetupEvent`** → `CustomPlayerModelsClient.INSTANCE.init()` — register shaders, key bindings, GUI hooks.
2. **`FMLCommonSetupEvent`** → load `cpm.json` config file.
3. **`InterModProcessEvent`** → scan IMC messages on the `"api"` channel to register `ICPMPlugin` implementations.
4. **`ServerStartingEvent`** / **`ServerStoppingEvent`** → attach/detach `MinecraftServerObject` and persist world config.
5. **`RegisterPayloadHandlersEvent`** → register all CPM network payload handlers.

---

### `com.tom.cpm.common.CommonBase` — Platform Bridge (Abstract)

`CommonBase` implements `MinecraftCommonAccess` and serves as the bridge between CPL abstractions and the real NeoForge/Minecraft APIs. It initializes:

- `CPMApiManager` — manages registered plugins.
- `Log4JLogger` — wraps Log4J into the CPL `ILogger` interface.
- `BlockStateHandler`, `ItemStackHandler`, `EntityTypeHandler` — CPL adapters for block/item/entity APIs.

---

### `com.tom.cpm.client.CustomPlayerModelsClient` — Client-Side Coordinator

The singleton client-side manager. Responsibilities:

- Registers shaders (`RegisterShadersEvent`), render layers, and key bindings.
- Maintains a `PlayerRenderManager` instance (the rendering coordinator).
- Subscribes to `ClientTickEvent` to drive the `AnimationEngine`.
- Registers the gesture menu key binding and the render toggle.
- Hooks into the title screen and skin customization GUI to add the editor button.
- Manages `netHandler` (client-side packet routing).

---

### `com.tom.cpm.shared` — Core Business Logic

This is the largest and most important package. It contains all logic that is independent of the mod loader.

#### `shared/definition/` — Model Definition & Loading

**`ModelDefinition`** is the central data class for a loaded custom model. It contains:
- The bone/part hierarchy
- Texture sheet metadata
- Bound animation registry
- Scale data
- Render properties

**`ModelDefinitionLoader<GP>`** (where `GP` is the game-profile type) handles all model acquisition:
- Backed by a **Guava `LoadingCache`** with a 15-second expiry to limit memory usage.
- Loads models concurrently via a thread pool.
- Supports six source types: HTTP URL, Base64 embedded, Pastebin, GitHub Gist, GitHub repo, and ModelsCDN.
- Enforces safety limits (max model size) on user-supplied data.

#### `shared/animation/` — Animation Engine

**`AnimationEngine`** runs every game tick and on partial ticks for interpolation. It tracks:
- `tickCounter` — monotonically increasing tick index.
- `partial` — fractional tick for smooth interpolation.
- `gestureData` — the current active gesture/animation state.
- `quickAccessPressed[]` — hotbar-style quick access states.
- `gestureAutoResetTimer` — resets gesture after configurable idle time.

Animation types:
| Type | Description |
|---|---|
| `VanillaPose` | Mapped to standard Minecraft animation states (walk, sneak, swim, etc.) |
| `CustomPose` | User/mod-defined named poses |
| `StagedAnimation` | Keyframe-based scripted animations |
| `AnimatedTexture` | Frame-by-frame texture animation |

**`AnimationRegistry`** stores all available animations for a model, with `ParameterDetails` describing named layer parameters and gesture properties.

#### `shared/model/` — Model Structure

- **`PlayerModelParts`** — Enum of standard body parts (head, body, arms, legs).
- **`Cube`** — A single box element with position, size, and UV mapping.
- **`PartPosition`** — Pivot point, rotation, and translation for a part.
- **`TextureSheetType`** — Which texture atlas sheet a part samples from.
- **`SkinType`** — Classic (64×64 normal arms) or Slim (64×64 slim arms).

#### `shared/network/` — Packet Definitions

All packets are routed through a single `ByteArrayPayload` wrapper, then dispatched by ID via `NetHandler`. There are 18 packet types:

| Packet | Direction | Purpose |
|---|---|---|
| `HelloC2S` / `HelloS2C` | Both | Capability handshake on login |
| `SetSkinC2S` | C → S | Client uploads own model |
| `SetSkinS2C` / `GetSkinS2C` | S → C | Server distributes models to clients |
| `GestureC2S` | C → S | Trigger a gesture/animation |
| `ServerAnimationS2C` | S → C | Server broadcasts animation state |
| `SetScaleC2S` | C → S | Client sends model scale |
| `ScaleInfoS2C` | S → C | Server distributes scale data |
| `NBTC2S` / `NBTS2C` / `NBTEntityS2C` | Both | Generic NBT data exchange |
| `PluginMessageC2S` / `PluginMessageS2C` | Both | Plugin-to-plugin messaging |
| `ReceiveEventS2C` | S → C | Server pushes events |
| `SubEventC2S` | C → S | Client subscribes to server events |
| `RequestPlayerC2S` | C → S | Client requests specific player model |
| `RecommendSafetyS2C` | S → C | Server recommends safety profile |

#### `shared/config/` — Configuration

**`ModConfig`** wraps two `ModConfigFile` instances:
1. **Client config** (`cpm.json`) — stored in the run directory; controls UI settings, model properties, rendering toggles.
2. **World config** — stored per-world; holds server-forced model assignments.

Configuration keys are enumerated in **`ConfigKeys`** (e.g., `TITLE_SCREEN_BUTTON`, `MODEL_PROPERTIES`, `SHOW_INGAME_WARNINGS`).

Access pattern follows the hierarchical CPL `ConfigEntry` structure:
```java
config.getEntry("models").getEntry(modelId).getInt("scale", 100)
```

#### `shared/loaders/` — Remote Resource Loaders

Each loader implements a common `ResourceLoader` interface and handles URL resolution, HTTP fetching, and format normalization:
- `HttpResourceLoader` — direct HTTP/HTTPS URLs.
- `GithubRepoResourceLoader` — GitHub repository tree traversal.
- `PastebinResourceLoader` — Pastebin raw URL rewriting.
- `GistResourceLoader` — GitHub Gist raw content fetching.
- `ModelsCDNResourceLoader` — ModelsCDN-specific API.

#### `shared/io/` — Model File Format

**`ModelFile`** uses a binary format with:
- A magic byte header for validation.
- UTF string name and description fields.
- A primary data block (main model geometry and animations).
- An optional overflow/local resources block (embedded textures).
- An optional icon (`Image`, max 256×256).
- A checksum for integrity verification.

---

### `com.tom.cpm.client` — Client Rendering

#### Rendering Architecture

```
Minecraft PlayerRenderer
        │  (intercepted by mixin)
        ▼
RedirectHolder (per-player instance)
        │  delegates to
        ▼
PlayerRenderManager
        │  manages
        ▼
ModelRenderManager<MultiBufferSource, ModelTexture, ModelPart, Model>
        │  applies
        ▼
Custom model parts + AnimationEngine state
```

**`RedirectHolder`** is the central rendering interception point. A variant exists for each render context:
- `RedirectHolderPlayer` — standard humanoid player.
- `RedirectHolderSkull` — skull block/item rendering.
- `RedirectHolderElytra` — elytra layer.
- `RedirectHolderApi` — generic `HumanoidModel` for API consumers.

**`ModelRenderManager`** is generic over the buffer-source type, texture type, model-part type, and model type, enabling reuse across Minecraft versions.

**Custom render types** (`CustomRenderTypes`) define the render layer blend modes and depth modes for translucent and emissive model parts.

---

### `com.tom.cpm.mixin` — Mixin Hooks

The mixin configuration (`cpm.mixins.json`) injects into 13 Minecraft classes:

| Mixin Class | Target | Purpose |
|---|---|---|
| `PlayerRendererMixin` | `PlayerRenderer` | Entry point for custom model rendering |
| `CapeLayerMixin` | `CapeLayer` | Replace cape geometry |
| `ElytraLayerMixin` | `ElytraLayer` | Replace elytra geometry |
| `HumanoidArmorLayerMixin` | `HumanoidArmorLayer` | Replace armor rendering |
| `SkullBlockRendererMixin` | `SkullBlockRenderer` | Custom skull block rendering |
| `CustomHeadLayerMixin` | `CustomHeadLayer` | Custom head layer rendering |
| `BlockEntityWithoutLevelRendererMixin` | `BEWLR` | Custom item-in-hand rendering |
| `ParrotOnShoulderLayerMixin` | `ParrotOnShoulderLayer` | Parrot shoulder layer |
| `LocalPlayerMixin` | `LocalPlayer` | Local player tracking for animations |
| `LivingRendererMixin` | `LivingEntityRenderer` | Base living entity render hook |
| `SkullModelMixin` | `SkullModel` | Skull model geometry |
| `ClientPacketListenerMixin` | `ClientPacketListener` | Client-side packet reception |
| `GuiAccessor` | `Gui` | Field access for HUD rendering |

A second config (`cpm.mixins.compat.json`) holds compatibility mixins that are conditionally enabled by the mixin plugin.

**`CPMMixinPlugin`** runs at startup to detect:
- **OptiFine** (`OFDetector`) — enables `OptifineTexture` and `RedirectModelRendererOF` paths.
- **Iris shaders** (`IrisDetector`) — registers shader programs via `RegisterShadersEvent`.
- **ViveCraft VR** — enables VR-specific rendering.

---

### `com.tom.cpm.api` — Public Plugin API

External mods register an `ICPMPlugin` implementation either via IMC (`"api"` channel) or via the `@CPMPlugin` annotation (auto-discovered by `AnnotationFinder`).

**`ICPMPlugin`** lifecycle:
```java
String getOwnerModId();              // mod that owns this plugin
void initCommon(ICommonAPI api);     // called during common setup
void initClient(IClientAPI api);     // called during client setup
```

**`ICommonAPI`** (server-accessible):
```java
void setPlayerModel(Class, player, String b64, boolean forced, boolean persistent);
void setPlayerModel(Class, player, InputStream model, boolean forced);
void resetPlayerModel(Class, player);
void playerJumped(Class, player);
void playAnimation(Class, player, String name, Object value);
MessageSender registerPluginMessage(Class, String messageId, BiConsumer handler);
```

**`IClientAPI`** (client-only):
```java
void registerVoice(Class, Function<UUID, Float> getVoiceLevel);
void registerVoiceMute(Class, Function<UUID, Boolean> getMuted);
PlayerRenderer createPlayerRenderer(modelClass, RL, RT, MBS, GP);
LocalModel loadModel(String name, InputStream data);
void registerEditorGenerator(String name, String tooltip, Function generator);
MessageSender registerPluginMessage(..., boolean broadcastToTracking);
```

**`ISharedAPI`** (base, shared by both):
- Access to `AnimationRegistry`.
- Event tick listener subscription.

---

## Client/Server Separation Summary

| Concern | Client | Server | Shared |
|---|---|---|---|
| Player rendering | `CustomPlayerModelsClient`, `PlayerRenderManager`, `RedirectHolder*` | — | — |
| Animation playback | `AnimationEngine` (render-tick driven) | `ServerAnimationState` (tick driven) | `AnimationEngine` (shared state) |
| Model loading | `ModelDefinitionLoader` (with cache) | — | `ModelDefinitionLoader` base |
| Model distribution | Receives via `SetSkinS2C` | Sends via `SetSkinS2C` | `NetHandler` routing |
| Config storage | `cpm.json` (client dir) | World config (world dir) | `ModConfig` wrapper |
| Commands | Client-side `/cpmclient` | `/cpm setskin`, `/cpm setskin -r` | — |
| GUI | Editor, Gesture menu, Settings | — | — |
| Safety enforcement | Client-side size limits | Server-side size limits | `ModelFile` validation |

---

## Data Flow: Model Loading & Display

```
1. Player joins server
       │
       ▼
2. HelloC2S/HelloS2C handshake (capability negotiation)
       │
       ▼
3. Client sends SetSkinC2S with Base64 or URL model reference
       │
       ▼
4. Server validates, stores, and broadcasts SetSkinS2C to nearby players
       │
       ▼
5. Receiving client's ModelDefinitionLoader checks Guava cache
   ├─ Cache hit  → use cached ModelDefinition
   └─ Cache miss → fetch model (HTTP/Pastebin/Gist/etc.) on thread pool
                           │
                           ▼
                   Parse ModelFile binary format
                           │
                           ▼
                   Build ModelDefinition (parts, textures, animations)
       │
       ▼
6. PlayerRenderer renders frame
       │ (intercepted by PlayerRendererMixin)
       ▼
7. RedirectHolderPlayer applies ModelDefinition geometry
       │
       ▼
8. AnimationEngine supplies current pose/gesture state
       │
       ▼
9. Custom model parts rendered via ModelRenderManager
```

---

## Data Flow: Gesture/Animation Sync

```
Player presses gesture key
       │
       ▼
GestureGui / KeyBinding handler
       │
       ▼
AnimationEngine.setGesture(name, value)
       │
       ▼
GestureC2S packet → Server
       │
       ▼
Server stores ServerAnimationState for player
       │
       ▼
ServerAnimationS2C broadcast → all tracking clients
       │
       ▼
Each client's AnimationEngine updates for that player UUID
       │
       ▼
Next render frame applies updated pose
```

---

## Configuration Reference

### `cpm.json` (Client)
Stored in the Minecraft run/config directory.

| Key | Type | Description |
|---|---|---|
| `titleScreenButton` | boolean | Show CPM button on the title screen |
| `modelProperties` | object | Per-model scale and rendering overrides |
| `modelPropertiesValues` | object | Named value presets for model properties |
| `showIngameWarnings` | boolean | Display in-game warning overlays |
| Animation/gesture keys | various | Gesture hotbar and quick-access config |

### World Config (Server)
Stored per-world. Holds:
- Persistent player-to-model assignments (set by `/cpm setskin`).
- Server safety profile settings.

---

## Build & Platform Information

| Property | Value |
|---|---|
| Mod ID | `cpm` |
| Minecraft target | 1.21.1 |
| Mod loader | NeoForge 21.0.110-beta+ |
| Java version | 21 |
| License | MIT |
| Display test mode | `IGNORE_ALL_VERSION` (can coexist with vanilla servers) |
| Mixin configs | `cpm.mixins.json`, `cpm.mixins.compat.json` |

### Dependencies (runtime)
- NeoForge (required)
- Guava (bundled via Minecraft)
- Log4J (bundled via Minecraft)

### Optional Integration
- OptiFine — detected at startup via `OFDetector`
- Iris shaders — detected via `IrisDetector`
- ViveCraft — detected via mixin plugin
- Any mod implementing `ICPMPlugin`

---

## Extending CPM (Plugin Guide)

### 1. Implement `ICPMPlugin`

```java
@CPMPlugin // or send via IMC "api" channel
public class MyPlugin implements ICPMPlugin {
    @Override
    public String getOwnerModId() { return "mymod"; }

    @Override
    public void initCommon(ICommonAPI api) {
        // server-accessible operations
    }

    @Override
    public void initClient(IClientAPI api) {
        // register voice provider
        api.registerVoice(MyPlayer.class, uuid -> MyVoiceSystem.getLevel(uuid));
    }
}
```

### 2. Force a Model on a Player (Server-side)

```java
commonApi.setPlayerModel(ServerPlayer.class, player, modelInputStream, true /* forced */);
```

### 3. Play an Animation

```java
commonApi.playAnimation(ServerPlayer.class, player, "wave", 1.0f);
```

### 4. Plugin Messaging

```java
MessageSender sender = commonApi.registerPluginMessage(
    ServerPlayer.class, "my_channel",
    (player, data) -> handleMessage(player, data)
);
sender.send(player, myPayloadBytes);
```

---

## Security Considerations

- All user-supplied model data is validated against a maximum byte size before parsing.
- `ModelFile` checksums are verified on load to detect corruption.
- Server-forced models require operator (`/cpm setskin`) permission; clients cannot override forced assignments.
- Network packets are routed only to players with the CPM mod installed (capability handshake required).
- Remote URLs are fetched server-side to avoid exposing client IP to arbitrary hosts when server distribution is used.
