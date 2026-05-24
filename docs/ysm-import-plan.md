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
