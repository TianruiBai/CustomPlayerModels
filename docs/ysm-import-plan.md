# YSM (Yes Steve Model) → CPM Import — Implementation Plan

## 0. Executive Summary

Add YSM `.ysmproject` import to the CPM skin editor. A user opens a `.ysmproject` file in the editor; CPM reads the ZIP archive, parses Bedrock-format models/animations/textures, and converts them into the CPM editor's native `Editor.elements`, `Editor.animations`, and `Editor.textures` data structures. The result is a fully editable CPM project.

**Scope**: Phase 1 handles **unencrypted** `.ysmproject` ZIP files (the older YSM format). Encrypted newer `.ysm` files are out of scope.

---

## 1. Format Analysis

### 1.1 YSM Archive Structure (`.ysmproject` = ZIP)

```
Avali_零幻.ysmproject/
├── ysm.json                          # Metadata, file references, properties
├── models/
│   ├── main.json                     # Bedrock geometry (body model)
│   └── arm.json                      # Bedrock geometry (arm/held-item model)
├── animations/
│   ├── main.animation.json           # Bedrock animation keyframes
│   ├── arm.animation.json            # Arm animation keyframes
│   └── extra.animation.json          # Extra gesture animations
├── controller/
│   └── main.animation_controllers.json  # State machine controllers
├── textures/
│   ├── default.png
│   ├── 作战服.png
│   └── ...
└── avatar/                           # Author avatar images (skip)
```

### 1.2 `ysm.json` Metadata (key fields)

| Field | Purpose |
|---|---|
| `metadata.name` | Model display name |
| `metadata.authors[].name` | Author info |
| `properties.height_scale` / `width_scale` | Model scaling |
| `properties.extra_animation` | Named gesture → animation mapping |
| `properties.default_texture` | Default texture name |
| `files.player.model.main` | Path to main body model JSON |
| `files.player.model.arm` | Path to arm model JSON |
| `files.player.animation.main` | Path to main animation JSON |
| `files.player.animation.arm` | Path to arm animation JSON |
| `files.player.animation.extra` | Path to extra animation JSON |
| `files.player.animation_controllers[]` | Path to controller JSONs |
| `files.player.texture[]` | Array of `{uv: "textures/name.png"}` |

### 1.3 Bedrock Model JSON (`models/main.json`)

```json
{
  "format_version": "1.21.0",
  "minecraft:geometry": [{
    "description": {
      "identifier": "geometry.rainbow",
      "texture_width": 1024,
      "texture_height": 1024
    },
    "bones": [
      {
        "name": "Mroot",
        "pivot": [1.9, 15, 0]
      },
      {
        "name": "Chest",
        "parent": "UpperBody",
        "pivot": [0, 0, 0],
        "cubes": [{
          "origin": [-3, 19.04558, -2.52094],
          "size": [6, 4, 4],
          "inflate": 0.1,
          "uv": {
            "north": {"uv": [10, 74], "uv_size": [12, 8]},
            "east":  {"uv": [2, 74],  "uv_size": [8, 8]},
            ...
          }
        }]
      }
    ]
  }]
}
```

**Key concepts:**
- **Bones**: hierarchical with `parent`, `pivot` (rotation center in Bedrock coords)
- **Cubes**: `origin` (corner position), `size` (dimensions), `inflate` (grow factor), per-face UV
- **Coordinate system**: Bedrock uses X=right, Y=up, Z=forward (out of screen)
  - CPM uses Minecraft coords: X=right, Y=up, Z=south
  - Bedrock pivot/origin are in world units (1 unit = 1 pixel at default scale)

### 1.4 Bedrock Animation JSON (`animations/main.animation.json`)

```json
{
  "format_version": "1.8.0",
  "animations": {
    "idle": {
      "loop": true,
      "animation_length": 3,
      "bones": {
        "Upbody": {
          "rotation": {
            "0.0": {
              "post": [0, 0, 0],
              "lerp_mode": "catmullrom"
            },
            "0.5417": {
              "post": [0, 0.08201, 0],
              "lerp_mode": "catmullrom"
            }
          },
          "position": {
            "0.0": {"post": [0, 0, 0], "lerp_mode": "catmullrom"},
            "0.5417": {"post": [0, 0.08201, 0], "lerp_mode": "catmullrom"}
          }
        },
        "molang": {
          "rotation": ["v.bv=math.cos(1*query.anim_time*360/(72/24))*2", 0, 0]
        }
      }
    }
  }
}
```

**Key concepts:**
- **Animations** are named (e.g., "idle", "walk", "emp")
- Each animation has `loop`, `animation_length` (seconds)
- **Bones** have keyframed `rotation`, `position`, `scale`
- Each keyframe is at a time `"0.0"` with `"post"` value `[x, y, z]` and `"lerp_mode"`
- Rotation values are in **degrees** (Euler angles: pitch=X, yaw=Y, roll=Z)
- **Molang expressions**: embedded scripting for dynamic values (e.g., `query.anim_time`)
- Timeline events: `"0.1": ["v.roaming.f= 0;", ...]`

### 1.5 Animation Controllers (`controller/main.animation_controllers.json`)

State machines that select which animation plays based on conditions:

```json
{
  "animation_controllers": {
    "player.parallel_1": {
      "states": {
        "default": {
          "animations": ["emp"],
          "transitions": [{"default1": "v.roaming.f== 1"}]
        },
        "default1": {
          "animations": [{"emp-1": "v.roaming.f== 1"}],
          "transitions": [{"default2": "v.roaming.f== 1"}],
          "blend_transition": {"0.0": 1, "0.05": 0}
        }
      }
    }
  }
}
```

**This is the hardest part to convert.** CPM does not have a state-machine animation system. Controllers use molang queries and YSM-specific variables (`v.roaming.*`, `ctrl.*`). These must be converted to CPM's gesture/pose trigger system or skipped.

---

## 2. CPM Editor Target Data Structures

### 2.1 Model Elements (`Editor.elements`)

```
Editor.elements: List<ModelElement>
├── ModelElement(type=ROOT_PART, typeData=HEAD)
│   ├── children: [ModelElement("Hat"), ModelElement("EarLeft"), ...]
├── ModelElement(type=ROOT_PART, typeData=BODY)
│   ├── children: [ModelElement("Chest"), ModelElement("Belly"), ...]
├── ModelElement(type=ROOT_PART, typeData=LEFT_ARM)
├── ModelElement(type=ROOT_PART, typeData=RIGHT_ARM)
├── ModelElement(type=ROOT_PART, typeData=LEFT_LEG)
└── ModelElement(type=ROOT_PART, typeData=RIGHT_LEG)
```

Each `ModelElement` (extends `Cube`):
| Field | Description |
|---|---|
| `name` | Display name |
| `pos` | Position relative to parent |
| `offset` | Pivot offset from pos |
| `size` | Cube dimensions |
| `rotation` | Euler rotation (degrees) |
| `scale` | Render scale (separate from mesh) |
| `meshScale` | Mesh vertex scale |
| `u`, `v` | UV offset on texture sheet |
| `textureSize` | 0=colored, 1=normal texture, >1=scaled |
| `rgb` | Color when textureSize=0 |
| `mcScale` | Minecraft scale multiplier |
| `hidden` | Visibility toggle |
| `parent` | Parent ModelElement |
| `children` | Child ModelElements |
| `storeID` | Unique persistent ID for animation reference |

### 2.2 Animations (`Editor.animations`)

```
Editor.animations: List<EditorAnim>
└── EditorAnim
    ├── type: AnimationType (POSE, CUSTOM_POSE, GESTURE, etc.)
    ├── pose: IPose (VanillaPose or CustomPose)
    ├── add: boolean (additive vs absolute)
    ├── loop: boolean
    ├── duration: int (ms)
    ├── priority: int
    ├── intType: InterpolatorType
    └── frames: List<AnimFrame>
        └── AnimFrame
            └── components: Map<ModelElement, FrameData>
                └── FrameData
                    ├── pos: Vec3f (relative displacement)
                    ├── rot: Vec3f (euler degrees)
                    ├── scale: Vec3f
                    ├── color: Vec3f (RGB)
                    └── show: boolean (visibility)
```

### 2.3 Textures (`Editor.textures`)

```
Editor.textures: Map<TextureSheetType, ETextures>
├── TextureSheetType.SKIN  → ETextures (the main texture atlas)
├── TextureSheetType.EYES  → ETextures (optional)
└── ...other sheet types
```

Each `ETextures`:
- `provider.size`: texture dimensions
- `provider.texture`: Image object
- `customGridSize`: whether grid != image size

---

## 3. Conversion Mapping: Bedrock → CPM

### 3.1 Coordinate System Conversion

**Bedrock coords → CPM/Minecraft coords:**

| Axis | Bedrock | Minecraft (CPM) | Conversion |
|---|---|---|---|
| X | Right | Right | Same |
| Y | Up | Up | Same |
| Z | Forward (out of screen) | South | Same (both Z+) |

**But there IS a 16× scale difference:**
- Bedrock works in world units (~1 unit = 1 Minecraft meter = 16 pixels)
- CPM works in **pixel units** (1 pixel = 1/16 of a block)
- Bedrock `origin` and `pivot` values like `[1.9, 15, 0]` are in pixel units

**Actually**, looking at the data more closely:
- Bedrock `pivot: [1.9, 15, 0]` — these are pixel values, same coordinate space as CPM
- Bedrock `origin: [-3, 19.04558, -2.52094]` — also pixel values
- Both use the same scale: position in pixels relative to the model root

**So conversion is 1:1 for position values!** But rotation conventions differ:
- Bedrock: `rotation: [pitch, yaw, roll]` = `[X, Y, Z]` in degrees
- CPM: `rotation: [x, y, z]` in degrees, same convention
- **Both use the same rotation system**: X=pitch, Y=yaw, Z=roll, all in degrees
- But check if CPM uses `[x, y, z]` or `[pitch, yaw, roll]` → CPM uses `[x, y, z]` where x=pitch, y=yaw, z=roll → matches Bedrock

**However subtlety:** Bedrock bone rotation is around the pivot point. CPM rotation is around the element's origin (=pos+offset). We need to convert pivot-based rotation to offset-based rotation.

### 3.2 Bone → ModelElement Mapping

| Bedrock Bone Property | CPM ModelElement Equivalent |
|---|---|
| `name` | `name` |
| `parent` | `parent` (set via hierarchy) |
| `pivot` [x, y, z] | `pos` = pivot (position relative to parent) |
| — | `offset` = (0,0,0) initially; set to center cube for rotation |
| `cubes[].origin` | Cube `offset` (position relative to bone) |
| `cubes[].size` | Cube `size` |
| `cubes[].inflate` | Cube `meshScale` (or expand size) |
| `cubes[].uv.north.uv` [u, v] | Per-face UV → CPM single UV (see Section 3.3) |
| `cubes[].uv.north.uv_size` [w, h] | Per-face UV size |
| `rotation` (bone-level) | `rotation` |
| `visible_bounds_width/height` | (ignored — editor rendering config) |

**Bone pivot handling:**
In Bedrock, a bone's pivot is like a rotation anchor. Cubes are placed relative to that pivot. In CPM, we should:
1. Set `ModelElement.pos` = bone's pivot (relative to parent bone's pivot)
2. Set cube `offset` = cube's `origin` - bone's `pivot`
3. The bone's rotation is around pivot, CPM's rotation is around the element's world position

### 3.3 UV Mapping Conversion

Bedrock uses **per-face UV** — each cube face has independent UV coordinates.
CPM uses **single UV** per cube — one UV origin applied to all faces.

**Conversion approach:**
- For the simplest case (all faces share the same UV area), use the first face's UV
- For models with complex per-face UV, we should use CPM's `PerFaceUV` feature (`ModelElement.faceUV`)
- CPM supports `PerFaceUV` through `EffectPerFaceUV` and `PerFaceUV` class
- Map Bedrock face directions to CPM directions:
  - `north` → `Direction.NORTH` (CPM Z-)
  - `south` → `Direction.SOUTH` (CPM Z+)
  - `east` → `Direction.EAST` (CPM X+)
  - `west` → `Direction.WEST` (CPM X-)
  - `up` → `Direction.UP` (CPM Y+)
  - `down` → `Direction.DOWN` (CPM Y-)
- UV coordinates are 1:1 (both use pixel coords on the texture atlas)

### 3.4 Animation Keyframe Conversion

| Bedrock Animation Property | CPM EditorAnim/FrameData Equivalent |
|---|---|
| Animation name (e.g., "idle") | `EditorAnim.displayName` |
| `loop: true/false` | `EditorAnim.loop` |
| `animation_length` (seconds) | `EditorAnim.duration` = length × 1000 (ms) |
| Keyframe time (e.g., 0.5417) | Frame index in CPM (need to quantize) |
| `bones.X.rotation.post` [x, y, z] | `FrameData.rot` (degrees → CPM uses degrees) |
| `bones.X.position.post` [x, y, z] | `FrameData.pos` |
| `bones.X.scale.post` [x, y, z] | `FrameData.scale` |
| `lerp_mode: "catmullrom"` | `InterpolatorType.POLY_LOOP` (or nearest equivalent) |
| `timeline` events (molang) | Can be stored as AnimationTrigger commands |

**CPM animation types mapping:**
- YSM `main` animations (idle, walk, etc.) → `AnimationType.POSE` mapped to corresponding `VanillaPose`
- YSM `extra` animations (gestures) → `AnimationType.CUSTOM_POSE` or `AnimationType.GESTURE`
- YSM `arm` animations (hold, use) → `AnimationType.POSE` for hand poses

**Keyframe conversion details:**
- CPM `EditorAnim` has a fixed set of frames; Bedrock keyframes are at arbitrary times
- Convert: for each Bedrock keyframe time `t` (in seconds), create a CPM frame at index `round(t / animation_length * total_frames)`
- Or: use a fixed frame count (e.g., divide animation_length into N evenly-spaced frames) and interpolate Bedrock keyframes to those times
- **Recommended**: Convert each Bedrock keyframe into an individual CPM frame, preserving the original time positions but normalizing to CPM's duration format

### 3.5 Animation Controller Mapping

YSM animation controllers use **state machines with molang transitions**. CPM does not have an equivalent.

**Simplified approach (Phase 1):**
- Parse controllers and extract which animations are referenced
- Import all animations as named **CustomPose** or **Gesture** animations
- Create a **gesture button configuration** mimicking the `extra_animation` section from `ysm.json`
- State transitions (molang) are **not converted**; users manually trigger gestures in CPM

**The `extra_animation` section in ysm.json maps directly to CPM's gesture system:**
```json
"extra_animation": {
  "hello": "你好！",
  "roll": "我来给你整个活！",
  "superearth": "为了超级地球！"
}
```
→ CPM `Gesture` buttons with display names.

### 3.6 Texture Handling

| YSM Texture | CPM Equivalent |
|---|---|
| `textures/*.png` | Load as `ETextures` for `TextureSheetType.SKIN` |
| Multiple textures | CPM uses a single stitched atlas. Either: (a) pick the default texture, (b) stitch all into one atlas |

**Recommended**: Import all textures and let the user choose which to apply, or create a stitched atlas.

---

## 4. Implementation Architecture

### 4.1 New Files to Create

```
src/main/java/com/tom/cpm/shared/editor/ysm/
├── YsmProjectLoader.java       # Main orchestrator: opens ZIP, parses ysm.json
├── BedrockModelParser.java     # Parses Bedrock geometry JSON → CPM ModelElement
├── BedrockAnimationParser.java # Parses Bedrock animation JSON → CPM EditorAnim
├── BedrockControllerParser.java# Parses animation controllers (Phase 1: basic extraction)
├── YsmTextureLoader.java       # Loads textures into CPM ETextures
├── YsmToCpmConverter.java      # Coordinates the full conversion pipeline
└── YsmModelData.java           # Intermediate data class for parsed YSM data
```

### 4.2 Modified Files

| File | Change |
|---|---|
| `EditorGui.java` | Add "Import YSM..." menu item in File menu |
| `Editor.java` | Add `importYsmProject(File)` method |
| `Exporter.java` | (possibly) Add YSM export option in future |

### 4.3 Data Flow

```
User selects .ysmproject file
        │
        ▼
Editor.importYsmProject(zipFile)
        │
        ▼
YsmProjectLoader.load(zipFile)
  ├── Open ZIP, read ysm.json
  ├── Parse ysm.json metadata
  │     └──→ YsmModelData (intermediate DTO)
  │
  ├── Read models/main.json
  │     └──→ BedrockModelParser.parse(json)
  │           └──→ List<ModelElement> (bone hierarchy + cubes)
  │
  ├── Read models/arm.json
  │     └──→ BedrockModelParser.parse(json)
  │           └──→ List<ModelElement> (arm bone hierarchy)
  │
  ├── Read animations/main.animation.json
  │     └──→ BedrockAnimationParser.parse(json)
  │           └──→ List<EditorAnim>
  │
  ├── Read animations/arm.animation.json + extra.animation.json
  │     └──→ BedrockAnimationParser.parse(json)
  │           └──→ List<EditorAnim>
  │
  ├── Read controller/main.animation_controllers.json
  │     └──→ BedrockControllerParser.parse(json)
  │           └──→ Gesture mappings (name → animation name)
  │
  └── Read textures/*.png
        └──→ YsmTextureLoader.load(pngBytes)
              └──→ ETextures
        │
        ▼
YsmToCpmConverter.convert(ysmData, editor)
  ├── Map YSM bones → CPM root parts (HEAD, BODY, LEFT_ARM, etc.)
  ├── Map child bones → ModelElement children
  ├── Convert UV mappings
  ├── Convert animations to EditorAnim
  ├── Setup gesture buttons
  └── Load textures into editor.textures
        │
        ▼
editor.updateGui() → Editor renders imported model
```

### 4.4 Bone-to-Root-Part Mapping Strategy

YSM models have custom bone hierarchies. We need to map them to CPM's fixed set of root parts.

**Option A — Heuristic name matching:**
- Match bone names against known patterns:
  - "Head", "MHead", "AllHead" → `HEAD`
  - "Body", "Allbody", "UpperBody", "Chest", "MUpperBody" → `BODY`
  - "LeftArm", "LArm" → `LEFT_ARM`
  - "RightArm", "RArm" → `RIGHT_ARM`
  - "LeftLeg", "LLeg" → `LEFT_LEG`
  - "RightLeg", "RLeg" → `RIGHT_LEG`

**Option B — Structure-based mapping (better):**
- YSM has well-known bone structure. Common bones:
  - `root` → root of hierarchy
  - `MHead`, `AllHead` → head group
  - `MAllBody`, `Allbody`, `UpperBody`, `MUpperBody` → body group
  - `LeftArm`, `RightArm` → arm groups
  - `LeftLeg`, `RightLeg` → leg groups

**Recommended**: Use heuristic matching with a configurable mapping table. For unrecognized bones, group them under `CUSTOM_PART` or the closest matching root part.

### 4.5 CPM Project Integration

The import creates an `IProject`-like structure (in-memory only, since we're going directly to Editor data structures):

```java
public void importYsmProject(File ysmFile) {
    loadDefaultPlayerModel(); // Reset to vanilla base
    YsmModelData ysmData = YsmProjectLoader.load(ysmFile);
    YsmToCpmConverter.convert(ysmData, this);
    restitchTextures();
    updateGui();
    this.file = null; // Mark as unsaved new project
}
```

---

## 5. Detailed Class Designs

### 5.1 `YsmModelData` (Intermediate Data Transfer Object)

```java
public class YsmModelData {
    String modelName;
    String description;
    List<String> authors;
    
    // Model data
    JsonObject mainModelJson;    // Parsed main.json
    JsonObject armModelJson;     // Parsed arm.json
    
    // Animation data
    JsonObject mainAnimJson;     // Parsed main.animation.json
    JsonObject armAnimJson;      // Parsed arm.animation.json
    JsonObject extraAnimJson;    // Parsed extra.animation.json
    JsonObject controllerJson;   // Parsed main.animation_controllers.json
    
    // Texture data
    Map<String, byte[]> textures; // filename.png → PNG bytes
    
    // Properties
    float heightScale;
    float widthScale;
    String defaultTexture;
    Map<String, String> extraAnimations; // gesture name → animation name
}
```

### 5.2 `BedrockModelParser`

```java
public class BedrockModelParser {
    
    /** Parse a Bedrock geometry JSON into a flat list of bones with cubes */
    public static List<BedrockBone> parse(JsonObject modelJson);
    
    /** Convert Bedrock bones to CPM ModelElement hierarchy */
    public static List<ModelElement> convertToElements(
        List<BedrockBone> bones, 
        Editor editor, 
        PlayerModelParts targetPart
    );
    
    // Internal data class
    public static class BedrockBone {
        String name;
        String parent;
        Vec3f pivot;          // [x, y, z] in pixel coords
        Vec3f rotation;       // [pitch, yaw, roll] in degrees
        List<BedrockCube> cubes;
    }
    
    public static class BedrockCube {
        Vec3f origin;          // corner position
        Vec3f size;            // dimensions
        float inflate;         // inflation factor
        Map<String, BedrockFaceUV> faces; // per-face UV
    }
    
    public static class BedrockFaceUV {
        Vec2i uv;             // [u, v] pixel coords
        Vec2i uvSize;         // [width, height] can be negative
    }
}
```

**Bone → ModelElement conversion logic:**

```java
ModelElement createElement(BedrockBone bone, ModelElement parent, Editor editor) {
    ModelElement elem = new ModelElement(editor);
    elem.name = bone.name;
    elem.pos = bone.pivot; // Position = pivot in CPM coords
    
    // For each cube in the bone
    for (BedrockCube cube : bone.cubes) {
        ModelElement cubeElem = new ModelElement(editor);
        cubeElem.name = bone.name + "_cube";
        cubeElem.size = cube.size;
        // offset = cube origin - bone pivot (relative to bone position)
        cubeElem.offset = cube.origin.sub(bone.pivot);
        cubeElem.texture = true;
        cubeElem.textureSize = 1;
        // Map UV from first face, or use PerFaceUV
        if (hasSameUV(cube)) {
            cubeElem.u = firstFaceUV.u;
            cubeElem.v = firstFaceUV.v;
        } else {
            cubeElem.faceUV = buildPerFaceUV(cube);
        }
        cubeElem.meshScale = new Vec3f(1 + cube.inflate, 1 + cube.inflate, 1 + cube.inflate);
        cubeElem.parent = elem;
        elem.children.add(cubeElem);
    }
    
    return elem;
}
```

### 5.3 `BedrockAnimationParser`

```java
public class BedrockAnimationParser {
    
    /** Parse animation JSON and return CPM EditorAnim list */
    public static List<EditorAnim> parse(
        JsonObject animJson, 
        Editor editor,
        Map<String, ModelElement> boneNameToElement,
        AnimationType defaultType
    );
}
```

**Keyframe conversion:**

```java
EditorAnim convertAnimation(String animName, JsonObject animData, Editor editor) {
    EditorAnim anim = new EditorAnim(editor, filenamePrefix + animName, type, false);
    anim.displayName = animName;
    anim.loop = animData.get("loop").getAsBoolean();
    anim.duration = (int)(animData.get("animation_length").getAsFloat() * 1000);
    
    JsonObject bones = animData.getAsJsonObject("bones");
    for (String boneName : bones.keySet()) {
        ModelElement target = boneNameToElement.get(boneName);
        if (target == null) continue;
        
        JsonObject boneData = bones.getAsJsonObject(boneName);
        
        // Process rotation keyframes
        if (boneData.has("rotation")) {
            processKeyframes(boneData.get("rotation"), target, 
                (frame, value) -> frame.setRot(new Vec3f(value[0], value[1], value[2])));
        }
        // Process position keyframes
        if (boneData.has("position")) {
            processKeyframes(boneData.get("position"), target,
                (frame, value) -> frame.setPos(new Vec3f(value[0], value[1], value[2])));
        }
        // Process scale keyframes
        if (boneData.has("scale")) {
            processKeyframes(boneData.get("scale"), target,
                (frame, value) -> frame.setScale(new Vec3f(value[0], value[1], value[2])));
        }
    }
    return anim;
}
```

### 5.4 `BedrockControllerParser`

```java
public class BedrockControllerParser {
    
    /** Extract gesture→animation mappings from animation controllers */
    public static Map<String, String> extractGestures(JsonObject controllerJson);
    
    /** Extract all referenced animation names */
    public static List<String> extractAnimationNames(JsonObject controllerJson);
}
```

### 5.5 `YsmProjectLoader`

```java
public class YsmProjectLoader {
    
    public static YsmModelData load(File ysmProjectFile) throws IOException {
        YsmModelData data = new YsmModelData();
        
        try (ZipFile zip = new ZipFile(ysmProjectFile)) {
            // Parse ysm.json
            ZipEntry ysmEntry = zip.getEntry("ysm.json");
            String ysmJsonStr = readEntry(zip, ysmEntry);
            JsonObject ysmJson = parseJson(ysmJsonStr);
            
            data.modelName = ysmJson.getAsJsonObject("metadata").get("name").getAsString();
            // ... parse other metadata
            
            // Read models
            String mainModelPath = ysmJson.getAsJsonObject("files")
                .getAsJsonObject("player").getAsJsonObject("model").get("main").getAsString();
            data.mainModelJson = parseJson(readEntry(zip, zip.getEntry(mainModelPath)));
            
            // Read animations
            // ...
            
            // Read textures
            for (JsonElement tex : textureArray) {
                String path = tex.getAsJsonObject().get("uv").getAsString();
                byte[] pngData = readEntryBytes(zip, zip.getEntry(path));
                data.textures.put(getFileName(path), pngData);
            }
        }
        return data;
    }
}
```

### 5.6 `YsmToCpmConverter`

```java
public class YsmToCpmConverter {
    
    public static void convert(YsmModelData ysmData, Editor editor) {
        // 1. Parse bones from model JSONs
        List<BedrockBone> mainBones = BedrockModelParser.parse(ysmData.mainModelJson);
        List<BedrockBone> armBones = BedrockModelParser.parse(ysmData.armModelJson);
        
        // 2. Map bones to CPM root parts
        Map<String, PlayerModelParts> boneToRootPart = mapBonesToParts(mainBones);
        
        // 3. Build ModelElement hierarchy under each root part
        for (PlayerModelParts part : PlayerModelParts.VALUES) {
            if (part == PlayerModelParts.CUSTOM_PART) continue;
            ModelElement rootElem = editor.elements.stream()
                .filter(e -> e.typeData == part).findFirst().orElse(null);
            if (rootElem == null) continue;
            
            // Find bones mapped to this part
            String rootBoneName = findRootBoneForPart(mainBones, part);
            if (rootBoneName != null) {
                BedrockBone rootBone = findBone(mainBones, rootBoneName);
                // Disable vanilla part rendering (hide the default cube)
                rootElem.hidden = true;
                // Build YSM bone hierarchy under this root
                buildHierarchy(rootBone, mainBones, rootElem, editor);
            }
        }
        
        // 4. Build bone name → ModelElement lookup map
        Map<String, ModelElement> boneToElement = buildBoneElementMap(editor.elements);
        
        // 5. Convert animations
        // ... parse all animation JSONs, create EditorAnims
        
        // 6. Setup gesture buttons from ysm.json extra_animation
        // ...
        
        // 7. Load textures
        // ...
    }
}
```

---

## 6. Implementation Phases

### Phase 1: Core Model Import (MVP)
**Goal**: Open `.ysmproject`, see the model in the editor.

1. Create `YsmModelData`, `YsmProjectLoader` — ZIP reading, ysm.json parsing
2. Create `BedrockModelParser` — parse geometry JSON into `BedrockBone`/`BedrockCube` DTOs
3. Create `YsmToCpmConverter` — bone hierarchy → `ModelElement` tree
4. Implement bone name → `PlayerModelParts` heuristic mapping
5. Implement simple UV conversion (use first face UV, skip PerFaceUV initially)
6. Implement texture loading (first/default texture)
7. Add "Import YSM..." button to `EditorGui` File menu
8. Wire up `Editor.importYsmProject()`

### Phase 2: Animation Import
**Goal**: Animations from YSM play in CPM.

1. Create `BedrockAnimationParser` — parse animation JSON → `EditorAnim`
2. Implement keyframe time → CPM frame mapping
3. Implement animation type mapping (pose/gesture)
4. Handle rotation/position/scale keyframes
5. Handle visibility keyframes
6. Handle molang timeline events (basic: store as commands)

### Phase 3: Per-Face UV & Advanced Model Features
1. Implement `PerFaceUV` mapping for Bedrock per-face UV
2. Handle `inflate` → `meshScale` conversion
3. Handle bone-level rotation around pivot
4. Handle `mirror` property
5. Handle multiple textures (stitched atlas or texture picker)

### Phase 4: Animation Controllers & Gestures
1. Parse animation controllers → extract gesture→animation mappings
2. Map YSM `extra_animation` to CPM gesture button data
3. Handle controller state machine concepts (basic)

### Phase 5: Polish
1. Handle model properties (height_scale, width_scale)
2. Preserve model metadata (author, license) in CPM description
3. Error handling & reporting for malformed YSM files
4. Progress bar for large imports
5. Support for newer YSM format versions

---

## 7. Key Technical Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Bone hierarchy mismatch (YSM has arbitrary bones, CPM has 6 fixed root parts) | High | Heuristic name matching + manual mapping table; unmapped bones go under BODY |
| Per-face UV cannot be losslessly converted to single UV | Medium | Use CPM's PerFaceUV feature; if unsupported, pick the largest UV face |
| Molang expressions in animations | High | Skip molang-driven animations; flag them for manual review |
| Animation controllers state machines | High | Phase 1: skip; Phase 4: basic extraction of gesture mappings |
| Bedrock rotation around pivot ≠ CPM rotation convention | Medium | Test with sample models; adjust offset/pivot conversion math |
| Multiple textures (YSM supports many, CPM uses stitched atlas) | Medium | Import all; default to first; provide texture switcher |
| Large model/texture sizes (1024×1024 textures are common) | Low | CPM already supports up to 8192×8192 textures |

---

## 8. Test Data

Use the attached `Avali_零幻.ysmproject` as primary test data:
- Contains full model (body + arm), multiple animations, multiple textures
- Has complex bone hierarchy with parent chains
- Has animation controllers with state machines
- Has per-face UV mappings
- Has extra_animation gesture definitions

---

## 9. Dependencies

- **JSON parsing**: CPM already uses a custom `JsonMap`/`JsonList` system. For YSM import, use Gson or manual JSON parsing (CPM project uses `com.tom.cpl.util.Json` or custom parsing)
- **ZIP reading**: Java's `java.util.zip.ZipFile` (already used by LgeacyYSM's `ZipFormat`)
- **Image loading**: CPM's `Image` class (supports PNG)
- **No new external dependencies needed**

---

## 10. Estimated Effort

| Phase | Effort Estimate |
|---|---|
| Phase 1: Core Model Import | 3-5 days |
| Phase 2: Animation Import | 3-4 days |
| Phase 3: Per-Face UV & Advanced | 2-3 days |
| Phase 4: Controllers & Gestures | 3-4 days |
| Phase 5: Polish | 2-3 days |
| **Total** | **13-19 days** |

---

## 11. Phase 6 Polish Plan — Detailed Implementation Steps

### 11.0 Architectural Decision: Multi-Texture as a Cross-Cutting CPM Feature

Rather than building YSM-specific texture switching, this phase introduces **multi-texture-slot support** as a first-class CPM editor and runtime capability. This benefits ALL CPM models (not just YSM imports) and is non-breaking for existing `.cpmproject` files which still use a single texture slot.

**Core design principle**: CPM's single-SKIN-texture-per-frame render model stays unchanged. A new `AnimationType.TEXTURE` + `InterpolatorChannel.TEXTURE_ID` drives which texture slot is active at any point in an animation timeline. When no TEXTURE animation is playing, the first slot (slot 0) is used — preserving 100% backward compatibility.

---

### 11.1 Cross-Cutting Architecture: Texture Slot System

#### 11.1.1 New Data Structures

**`TextureSlot` (new class, `src/main/java/com/tom/cpm/shared/editor/TextureSlot.java`)**
```java
public class TextureSlot {
    public String name;        // Display name, e.g. "default", "作战服"
    public Image image;        // The texture image
    public Vec2i gridSize;     // Texture grid dimensions (may differ from image size)
    public boolean customGridSize;
    // Serialization support
    public JsonMap toJson();
    public static TextureSlot fromJson(JsonMap map);
}
```

**`Editor` additions:**
```java
// Replaces the ad-hoc importedTextures map with a proper slot system
public List<TextureSlot> textureSlots = new ArrayList<>();  // All texture slots
public int activeTextureSlot = 0;                            // Currently active slot index
@Deprecated public transient Map<String, byte[]> importedTextures; // Migrated to textureSlots on next save
```

**`ModelDefinition` additions:**
```java
// Multi-texture support at runtime
private List<TextureProvider> textureSlotProviders;  // Parallel to editor's textureSlots
private int activeTextureSlot = 0;
// Animation-driven texture slot selection
public void setActiveTextureSlot(int index);
public TextureProvider getActiveSkinTexture();
```

#### 11.1.2 New Animation Channel

**`InterpolatorChannel` addition:**
```java
TEXTURE_ID(12, 0),  // Integer channel: which texture slot is active. Default value = 0 (first slot).
```

This fits cleanly after the existing 12 channels (0–11). The channel value is an integer index into the texture slot list. During interpolation, the nearest integer frame value is used (no fractional blending between textures).

**`FrameData` addition:**
```java
private int textureId;  // Which texture slot this frame references

public int getTextureId() { return textureId; }
public void setTextureId(int id) { this.textureId = id; }
public boolean hasTextureChange() { return textureId != activeTextureSlot; }
```

**`AnimFrame.toArray()` and `AnimFrame.getValue()`**: Include `TEXTURE_ID` channel in the interpolation array, using step interpolation (nearest neighbor, not linear — textures can't blend).

#### 11.1.3 New Animation Type

**`AnimationType` addition:**
```java
TEXTURE,  // Animation that controls texture slot selection via TEXTURE_ID channel
```

Properties:
- `isCustom()` → true
- `canLoop()` → true (texture swaps can loop)
- `isLayer()` → false (explicit animation, not continuous layer)
- When saved to project: filename prefix `"t_"` in `animations/t_<name>.json`
- In `AnimationExporter`: exported as `AnimationType.LAYER` with VALUE_LAYER semantics for runtime

---

### 11.2 Workstream P1: Multi-Texture Slot System — Editor Side

**Goal**: Users can add, remove, reorder, and name multiple texture slots in the editor. The active slot drives viewport rendering. This works for BOTH CPM-native projects and YSM imports.

#### Step P1.1: `TextureSlot` class
- **File**: NEW `src/main/java/com/tom/cpm/shared/editor/TextureSlot.java`
- Store: `name` (String), `image` (Image), `gridSize` (Vec2i), `customGridSize` (boolean)
- Serialization: `toJson()` / `fromJson(JsonMap)` for project save/load

#### Step P1.2: Editor state migration
- **File**: `Editor.java`
- Add `textureSlots: List<TextureSlot>` — initialized with one slot from default SKIN texture
- Add `activeTextureSlot: int` — default 0
- `getActiveTextureSlot()`: returns `textureSlots.get(activeTextureSlot)` or null
- `getTextureProvider()`: modified to return active slot's image, not hardcoded `TextureSheetType.SKIN`
- `switchToTextureSlot(int index)`: validates index, swaps active SKIN ETextures image, calls `restitchTextures()` + `markDirty()` + `updateGui()`
- Deprecation path for `importedTextures`: on next editor load, if `importedTextures != null && textureSlots.size() <= 1`, migrate entries into `textureSlots`

#### Step P1.3: Texture slot UI — always-visible panel
- **File**: `SkinSettingsPopup.java` (extend existing)
- Add a `ListPicker` or scrollable button list showing all texture slots
- Each row: slot name label + "Set Active" button
- "Add Slot" button: opens a file chooser for PNG, creates new slot
- "Remove Slot" button: removes non-active slot (minimum 1 slot)
- "Rename Slot" button: inline text edit for slot name
- Always visible — not gated on YSM import state

#### Step P1.4: Quick-switch keybinds
- **File**: `EditorGui.java`, `Keybinds.java`
- Add `nextTextureSlot` and `prevTextureSlot` keybinds
- Cycling wraps around; only enabled when `textureSlots.size() > 1`

#### Step P1.5: Viewport/texture editor integration
- **File**: `TextureEditorPanel.java` — already reads `editor.getTextureProvider()`, no change needed after P1.2
- **File**: `EditorGui.java` 3D viewport — already binds SKIN texture, no change needed

#### Step P1.6: Project save/load for texture slots
- **File**: `TexturesLoaderV1.java` — extend `save()` and `load()`
- In `save()`: for each texture slot beyond slot 0, write `skin_<index>.png` and metadata to `config.json` under `"textureSlots"` array
- In `load()`: read `"textureSlots"` from `config.json`, create additional `TextureSlot` entries
- Backward compat: if `"textureSlots"` key missing, single-slot behavior unchanged

---

### 11.3 Workstream P2: Multi-Texture Animation — Runtime Side

**Goal**: A `TEXTURE` animation can change the active texture slot during gameplay. This is the CPM animation system enhancement.

#### Step P2.1: `InterpolatorChannel.TEXTURE_ID` 
- **File**: `InterpolatorChannel.java`
- Add `TEXTURE_ID(12, 0)` enum constant
- No special interpolator needed; uses step/nearest-neighbor logic

#### Step P2.2: `FrameData` texture ID field
- **File**: `AnimFrame.java` (inner `FrameData` class)
- Add `textureId: int` field, default 0
- Add `getTextureId()`, `setTextureId(int)`, `hasTextureChange()` methods
- In constructor: initialize `textureId = 0`
- In `apply()`: if `hasTextureChange()`, call a new method on the render component to set texture slot

#### Step P2.3: Interpolation array extension
- **File**: `AnimFrame.java`
- In `toArray()`: include TEXTURE_ID channel data
- In `getValue()`: handle TEXTURE_ID with step interpolation (return value at nearest keyframe, no blending)

#### Step P2.4: `AnimationType.TEXTURE`
- **File**: `AnimationType.java`
- Add `TEXTURE` enum value
- `isCustom()` → true, `canLoop()` → true, `isLayer()` → false

#### Step P2.5: Editor animation UI for TEXTURE type
- **File**: `AnimationsLoaderV1.java`
- `getType()`: recognize `"t_"` prefix → `AnimationType.TEXTURE`
- `getFileName()`: for TEXTURE type, use `"t_"` prefix
- In animation properties editor: when type=TEXTURE, show texture slot dropdown for each frame instead of bone transform fields
- **File**: `EditorGui.java` animation panel — add TEXTURE type to the "New Animation" type dropdown

#### Step P2.6: Runtime texture swap during animation
- **File**: `AnimationHandler.java` — after `animate()` call, check if any TEXTURE animation triggered a slot change
- **File**: `ModelDefinition.java`:
  - Add `setActiveTextureSlot(int)` method
  - In `getTexture(TextureSheetType, boolean)`: for SKIN, return `textureSlotProviders.get(activeTextureSlot)` 
  - Add `textureSlotProviders` population in `resolveAll()` from `ModelPartTextureSlot` parts
- Create `ModelPartTextureSlot` (new class, similar to `ModelPartSkin` but for a specific slot index):
  ```java
  public class ModelPartTextureSlot implements IModelPart, IResolvedModelPart {
      private int slotIndex;
      private TextureProvider image;
      // write/read/apply — stores texture for slot N
  }
  ```
- **File**: `ModelPartType.java` — add `TEXTURE_SLOT` type
- **File**: `Exporter.java` — for each texture slot beyond 0, emit a `ModelPartTextureSlot`

#### Step P2.7: Animated texture slot in `AnimationRegistry`
- **File**: `AnimationRegistry.java`
- During `tickAnimated()`, after existing `AnimatedTexture` updates, also evaluate active TEXTURE animations:
  - Get current TEXTURE_ID channel value from playing TEXTURE animations
  - If value differs from `def.activeTextureSlot`, call `def.setActiveTextureSlot(value)`
  - This triggers texture rebind on next render frame

---

### 11.4 Workstream P3: Arm Model Skip Policy

**Goal**: Safe default that avoids first-person hand regressions.

#### Step P3.1: Skip flag
- **File**: `YsmToCpmConverter.java`
- Add `private static final boolean IMPORT_ARM_MODEL = false;` (constant for this phase)
- In `convert()`: wrap `BedrockModelParser.parse(ysmData.armModelJson)` and `processArmBones(...)` in `if (IMPORT_ARM_MODEL)` block
- Log: `Log.info("[YSM Import] arm.json skipped (IMPORT_ARM_MODEL=false)")`

#### Step P3.2: Future toggle preparation
- Keep `processArmBones()` method intact
- Add comment block documenting the intended re-enablement conditions

---

### 11.5 Workstream P4: Remap Fidelity Overhaul

#### Step P4.1: Parse cube-level pivot and rotation
- **File**: `BedrockModelParser.java`
- Extend `BedrockCube`:
  ```java
  public Vec3f pivot;     // null if not present
  public Vec3f rotation;  // null if not present
  ```
- In `parse()`: read `pivot` and `rotation` from cube JSON objects when present

#### Step P4.2: Transform composition for cubes with local pivot/rotation
- **File**: `YsmToCpmConverter.java` — `buildBoneHierarchy()` cube creation loop
- For cubes with local pivot: compute `cubeElem.offset = cube.origin.sub(cube.pivot)` and `cubeElem.pos = cube.pivot.sub(bone.pivot)` — splitting the translation so rotation happens around the cube pivot
- For cubes with local rotation: set `cubeElem.rotation`
- Apply transforms in order: bone pivot → bone rotation → cube pivot → cube rotation → cube offset

#### Step P4.3: Thin-cube inflate safety
- **File**: `YsmToCpmConverter.java` — inflate handling
- Clamp `meshScale` per-axis to [0.1, 10.0] to prevent extreme geometry
- If axis size < 0.01, skip meshScale for that axis (set to 1.0) and warn
- Log: `"[YSM Import] Thin cube meshScale clamped for bone 'X'"`

#### Step P4.4: Root mapping upgrade
- **File**: `BedrockModelParser.java`
- Keep `BONE_NAME_TO_PART` map
- Add `mapBoneByTopology(List<BedrockBone> allBones, BedrockBone bone)`:
  - Walk ancestor chain; if any ancestor maps to a known part, inherit that mapping
  - For unmatched top-level bones: check pivot position for body-region hints (e.g., high Y → HEAD, low Y with bilateral X → LEG)
- In `YsmToCpmConverter`: unmatched bones that cannot be mapped go to a new `YSM_UNMAPPED` container (see P5)

#### Step P4.5: Orphan bone handling
- **File**: `YsmToCpmConverter.java`
- Before hierarchy build: create a full `Map<String, BedrockBone> boneIndex`
- After all root-mapped bones are processed: iterate remaining unvisited bones
- Attach orphan trees to a dedicated `YSM_UNMAPPED` root attached to `CUSTOM_PART`
- This ensures no bones are silently dropped

#### Step P4.6: UV face hardening
- **File**: `BedrockModelParser.java` — `convertPerFaceUV()`
- Add validation: if `uv_size` is [0, 0], skip that face and warn
- Add face orientation test: verify direction mapping produces correct CPM face orientation by comparing expected UV winding
- For negative `uv_size` with abs value logic: add unit test cases against Blockbench reference

#### Step P4.7: `never_render` and mirror consistency
- **File**: `YsmToCpmConverter.java`
- If `bone.neverRender`: set `elem.hidden = true`
- Mirror composition: `cubeElem.mirror = cube.mirror ^ bone.mirror` (XOR — bone mirror flips, cube mirror flips again)

---

### 11.6 Workstream P5: Multiple Model Support Outside Body Roots

#### Step P5.1: Loader expansion
- **File**: `YsmProjectLoader.java`
- In `parseMetadata()`: read all keys under `files.player.model` (not just `main` and `arm`)
- Store additional models in `YsmModelData.extraModelJsons: Map<String, JsonObject>` (keyed by model key name)

#### Step P5.2: `YsmModelData` extension
- **File**: `YsmModelData.java`
- Add `public Map<String, JsonObject> extraModelJsons = new LinkedHashMap<>();`

#### Step P5.3: Converter grouping
- **File**: `YsmToCpmConverter.java`
- After main model import: iterate `ysmData.extraModelJsons`
- For each extra model: parse bones, create a new `ModelElement` container named `"YSM::" + modelKey`
- Attach containers as children of a new dedicated root or directly to `editor.elements`
- Container elements have `type = ElementType.NORMAL`, not `ROOT_PART` — they don't participate in vanilla body-part remap

#### Step P5.4: Animation targeting
- Extra model groups are addressable by their element names in animations
- The `allBoneElements` map includes bones from extra models, so existing animation conversion works automatically

---

### 11.7 Workstream P6: YSM Import Leverages Texture Slots

**Goal**: When importing a YSM project, all textures become texture slots with a generated TEXTURE animation that cycles through them (or a default static assignment).

#### Step P6.1: Importer integration
- **File**: `YsmToCpmConverter.java` — `loadTextures()`
- Replace: `editor.importedTextures = new HashMap<>(...)` with:
  - For each texture in `ysmData.textures`: create a `TextureSlot`, add to `editor.textureSlots`
  - Set `editor.activeTextureSlot` to index of `defaultTexture` (or 0)
  - Set the active SKIN texture from the selected slot
- Deprecation: `editor.importedTextures` set to null (migrated)

#### Step P6.2: Auto-generate TEXTURE animation (optional convenience)
- If YSM project has N > 1 textures: create an `EditorAnim` of type `TEXTURE` named "YSM Textures"
- One frame per texture, each setting `TEXTURE_ID` to the slot index
- Duration: 1 frame per texture, loop = false
- This gives the user an immediate way to browse textures via the animation timeline

---

### 11.8 Execution Sequence (Recommended Order)

| Step | Workstream | Description | Risk | Dependencies |
|------|-----------|-------------|------|-------------|
| 1 | P3.1 | Arm model skip policy | Low | None |
| 2 | P1.1-P1.2 | `TextureSlot` class + Editor state | Low | None |
| 3 | P1.3-P1.4 | Texture slot UI + keybinds | Low | P1.2 |
| 4 | P1.5-P1.6 | Viewport integration + save/load | Medium | P1.2 |
| 5 | P2.1-P2.3 | `InterpolatorChannel.TEXTURE_ID` + `FrameData` | Medium | None |
| 6 | P2.4-P2.5 | `AnimationType.TEXTURE` + editor UI | Medium | P2.3 |
| 7 | P2.6-P2.7 | Runtime texture swap (ModelDefinition + Exporter) | High | P2.5, P1.6 |
| 8 | P6 | YSM import → texture slots | Low | P1.6 |
| 9 | P4.1-P4.2 | Cube pivot/rotation parsing | Medium | None |
| 10 | P4.3-P4.5 | Inflate safety + root mapping + orphans | Medium | P4.2 |
| 11 | P4.6-P4.7 | UV hardening + mirror | Medium | None |
| 12 | P5 | Multiple model support | Medium | P4.5 |

---

### 11.9 Affected Files Summary

| File | Change Type | Workstream |
|------|------------|------------|
| `TextureSlot.java` | **NEW** | P1.1 |
| `ModelPartTextureSlot.java` | **NEW** | P2.6 |
| `Editor.java` | MODIFY — add `textureSlots`, `activeTextureSlot`, deprecate `importedTextures` | P1.2 |
| `TextureSheetType.java` | No change (SKIN stays single active sheet) | — |
| `InterpolatorChannel.java` | MODIFY — add `TEXTURE_ID(12, 0)` | P2.1 |
| `AnimFrame.java` | MODIFY — add `textureId` to `FrameData`, extend `toArray()` | P2.2-P2.3 |
| `AnimationType.java` | MODIFY — add `TEXTURE` | P2.4 |
| `AnimationRegistry.java` | MODIFY — evaluate TEXTURE animations in `tickAnimated()` | P2.7 |
| `AnimationHandler.java` | MODIFY — handle TEXTURE_ID channel in `animate()` | P2.7 |
| `ModelDefinition.java` | MODIFY — add `textureSlotProviders`, `setActiveTextureSlot()` | P2.6 |
| `Exporter.java` | MODIFY — emit `ModelPartTextureSlot` for slots > 0 | P2.6 |
| `ModelPartType.java` | MODIFY — add `TEXTURE_SLOT` | P2.6 |
| `AnimationsLoaderV1.java` | MODIFY — handle `t_` prefix, TEXTURE type | P2.5 |
| `TexturesLoaderV1.java` | MODIFY — save/load `textureSlots` array | P1.6 |
| `SkinSettingsPopup.java` | MODIFY — add slot list UI | P1.3 |
| `EditorGui.java` | MODIFY — texture slot menu, keybinds | P1.4, P2.5 |
| `Keybinds.java` | MODIFY — next/prev texture slot | P1.4 |
| `BedrockModelParser.java` | MODIFY — cube pivot/rotation, UV hardening | P4.1, P4.6 |
| `YsmToCpmConverter.java` | MODIFY — arm skip, inflate safety, root mapping, orphans, slot integration | P3, P4, P5, P6 |
| `YsmProjectLoader.java` | MODIFY — extra model loading | P5.1 |
| `YsmModelData.java` | MODIFY — extra model storage | P5.2 |

---

### 11.10 Validation Matrix

Per build cycle, verify:

| # | Check | Method |
|---|-------|--------|
| 1 | Import `Avali_零幻.ysmproject` — no exceptions | Console log |
| 2 | All textures appear as slots in UI | Manual check Skin Settings |
| 3 | Switching texture slot updates viewport + UV preview | Visual |
| 4 | Save as `.cpmproject`, close, reopen — slots preserved | Round-trip test |
| 5 | Existing single-texture `.cpmproject` opens correctly | Backward compat test |
| 6 | New TEXTURE animation can be created and plays in editor | Manual test |
| 7 | Arm model NOT imported (log message present) | Console log |
| 8 | No NaN/infinite positions in any element | Bounding box scan |
| 9 | No extreme (>1000%) meshScale values | Mesh scale scan |
| 10 | Bone count matches expected (no silent drops) | Element count vs bone count |

---

### 11.11 Non-Goals (Phase 6)

1. Full Bedrock animation controller state-machine parity.
2. Perfect first-person YSM arm emulation.
3. Per-element texture slot binding (each element using a different slot) — whole-model only.
4. Texture blending/crossfade between slots.
5. Stitching multiple textures into a single atlas automatically.

---

### 11.12 Deliverables

1. Multi-texture slot system functional in editor for CPM and YSM models.
2. `AnimationType.TEXTURE` + `InterpolatorChannel.TEXTURE_ID` working end-to-end.
3. `arm.json` skip policy active with clear logging.
4. Remap fidelity improvements (cube transforms, inflate safety, orphan handling, UV hardening).
5. Multiple model support for extra YSM model files.
6. Updated localization entries for new UI elements.
7. Build-success evidence with validation matrix pass.
