# YSM �?CPM Import �?Reworked Implementation Plan (v2)

## 0. Executive Summary

**Rework Goal**: The current converter breaks 3D positions because it tries to "smart re-parent" individual bones mid-hierarchy. This plan rewrites the converter from scratch using a fundamentally different approach: **preserve YSM bone subtree integrity and use world-space-aware positioning relative to CPM root parts.**

**Core Principle**: The YSM bone hierarchy is preserved intact within each subtree. Each YSM root-level subtree maps to exactly ONE CPM root part. Internal bone positions (parent-relative) are NEVER changed. Only the top-level bone's position is adjusted to account for the CPM root part's vanilla Minecraft position. **This is the same approach BlockBench uses.**

**Three Conversion Areas**:
1. **Model Assets**: Textures, non-player models, sounds, metadata
2. **Model**: Bone �?ModelElement conversion with correct positioning
3. **Animation**: Keyframe conversion with world-space delta computation

**Scope**: Phase 1 handles **unencrypted** `.ysmproject` ZIP files (the older YSM format). Encrypted newer `.ysm` files are out of scope.

---

## 1. Root Cause Analysis: Why Current Converter Breaks Positions

### 1.1 The Current Approach (BROKEN)

```
YSM Bone Tree:               CPM Output (current, WRONG):
  Mroot [1.9,15,0]             HEAD root (hidden)
  └─ root [1.9,15,0]             └─ Mroot [pos=computed wrong]
  └─ MAllBody [-1.9,15,0]        BODY root (hidden)  
  └─ Allbody [1.9,15,0]           └─ Allbody [pos=computed wrong]
  └─ Upbody [0,20.06,-0.71]         └─ Upbody [pos shifted]
  └─ MUpperBody [...]               └─ ...
  └─ LeftArm [...]              LEFT_ARM root (hidden)
  └─ RightArm [...]               └─ LeftArm [pos=broken re-parent]
```

The current `buildBoneHierarchySmart` detects when a YSM child bone maps to a different CPM part than its YSM parent, and **re-parents** it to a different CPM root. The re-parented bone's position is computed as `worldPos - ysmRootWorldPos`, which is wrong because:

1. **CPM root parts have vanilla Minecraft positions** (e.g., HEAD renders at [0,24,0], BODY at [0,12,0]). The current code ignores these.
2. **Breaking the YSM hierarchy invalidates child positions** �?children are parent-relative, and when you change the parent, everything shifts.
3. **Double-accounting** �?`worldPos` sums ancestor pivots, but CPM also applies root part position during rendering.

### 1.2 Why BlockBench Works (CORRECT)

BlockBench groups map to CPM root parts with the **entire subtree intact**:
- BB group "HEAD" �?CPM HEAD root
- All elements within the BB group keep their positions relative to the group
- The group itself is at the correct world position relative to the CPM root part

**BlockBench does NO re-parenting. It just labels subtrees.**

### 1.3 The Fix: Subtree-Preserving Placement

```
YSM Bone Tree:               CPM Output (new, CORRECT):
  [ROOT: Mroot �?BODY]         BODY root (hidden)
                                 └─ Mroot [pos=pivot, subtree intact]
                                   └─ root [pos=parentRel]
                                     ├─ MAllBody [parentRel]
                                     �?└─ Allbody [parentRel]
                                     �?  └─ Upbody [parentRel]
                                     �?    └─ MUpperBody [parentRel]
                                     �?      ├─ Chest [parentRel]
                                     �?      ├─ LeftArm [parentRel]
                                     �?      └─ RightArm [parentRel]
                                     └─ molang [parentRel]
```

**Rule**: Each YSM root-level bone tree stays together under ONE CPM root part. The mapping is determined by analyzing the ENTIRE subtree (root bone name + children names + spatial positions). No bone is ever moved from one CPM root to another.

---

## 2. Coordinate System Compatibility

### 2.1 YSM/Bedrock �?CPM Coordinate Mapping

Both systems use **pixel-unit coordinates** with the same axis conventions:

| Axis | Bedrock | CPM/Minecraft | Conversion |
|------|---------|---------------|------------|
| X | Right (+) | Right (+) | **1:1** |
| Y | Up (+) | Up (+) | **1:1** |
| Z | Forward/out (+) | South (+) | **1:1** |
| Rotation | Euler [pitch, yaw, roll] degrees | Euler [x, y, z] degrees | **1:1** |
| Scale | 1 unit = 1 pixel | 1 unit = 1 pixel | **1:1** |

**No scale conversion needed.** Both are pixel-coordinate systems.

### 2.2 Key Position Semantics

| Concept | Bedrock | CPM |
|---------|---------|-----|
| Bone position | `pivot` = world-space rotation center | `pos` = position relative to parent |
| Cube corner | `origin` = world-space min corner | `offset` = mesh corner relative to element pos |
| Cube size | `size` = [dx, dy, dz] dimensions | `size` = [dx, dy, dz] dimensions |
| Rotation anchor | Bone `pivot` | Element `pos` (the element origin) |
| Face UV | Per-face `uv` [u,v] + `uv_size` [w,h] | PerFaceUV or single UV [u,v] |

### 2.3 Conversion Formulas (1:1 mapping within a subtree)

```
// Bone �?ModelElement (parent-relative within same subtree)
elem.pos       = bone.pivot - parentBone.pivot    // parent-relative
elem.rotation  = bone.rotation                     // 1:1 degrees
elem.hidden    = bone.neverRender || no cubes

// Cube within a bone �?cube ModelElement
cubeElem.size    = cube.size                        // 1:1
cubeElem.offset  = cube.origin - bone.pivot         // relative to bone pivot
cubeElem.pos     = [0,0,0]                          // at bone origin
cubeElem.rotation = cube.rotation                   // if cube has own rotation

// UV �?CPM UV
// Simple case (all faces same UV):
cubeElem.u = firstFace.u
cubeElem.v = firstFace.v
// Complex case (per-face UV):
cubeElem.faceUV = BedrockModelParser.convertPerFaceUV(cube)

// Inflate �?meshScale
// meshScale = 1 + 2*inflate/size (per-axis)
cubeElem.meshScale.x = 1 + 2*cube.inflate / cube.size.x  (clamped to [0.1, 10])
```

### 2.4 Top-Level Bone Position (the ONLY position adjustment)

When a YSM root-level bone is placed under a CPM root part:

```
ysmBonePos = bone.pivot                        // Absolute world position of bone
cpmRootVanillaPos = getVanillaPartPos(part)     // Vanilla Minecraft part position

// The bone's position relative to the CPM root:
elem.pos = ysmBonePos - cpmRootVanillaPos       // Adjust for CPM root position
```

**CPM Root Part Vanilla Positions** (from `PlayerPartValues`):

| CPM Part | Vanilla pos (px, py, pz) |
|----------|--------------------------|
| HEAD | (0, 0, 0) |
| BODY | (0, 0, 0) |
| LEFT_ARM | (5, 2, 0) |
| RIGHT_ARM | (-5, 2, 0) |
| LEFT_LEG | (1.9, 12, 0) |
| RIGHT_LEG | (-1.9, 12, 0) |

Since HEAD and BODY have vanilla pos (0,0,0), bones placed under them use their absolute world position directly. For arms and legs, the vanilla offset is subtracted.

**IMPORTANT**: This only applies to the top-level bone of each YSM subtree. All child bones keep their parent-relative positions unchanged.

---

## 3. Smart Bone-to-Part Mapping (The "Smart Parser")

### 3.1 Multi-Strategy Classification

Each YSM root-level bone tree is classified into a CPM root part using a cascade of strategies:

```
┌─────────────────────────────────────────────────────────────────�?
�?            Smart Bone Tree �?CPM Part Classifier               �?
├─────────────────────────────────────────────────────────────────�?
�?                                                                 �?
�? Input: YSM root bone + its entire subtree                       �?
�?                                                                 �?
�? ┌─ Strategy 1: Exact Name Match ────────────────────────────�? �?
�? �? Match bone name against known patterns:                   �? �?
�? �?   "head", "mhead", "allhead" �?HEAD                      �? �?
�? �?   "body", "mallbody", "upbody" �?BODY                    �? �?
�? �?   "leftarm", "larm" �?LEFT_ARM                           �? �?
�? �?   "rightarm", "rarm" �?RIGHT_ARM                         �? �?
�? �?   "leftleg", "lleg" �?LEFT_LEG                           �? �?
�? �?   "rightleg", "rleg" �?RIGHT_LEG                         �? �?
�? �? If match found �?RETURN part                              �? �?
�? └────────────────────────────────────────────────────────────�? �?
�?                             �?(no match)                        �?
�? ┌─ Strategy 2: Child Name Consensus ────────────────────────�? �?
�? �? Check immediate children's name matches.                  �? �?
�? �? If >50% of children match one part �?RETURN that part     �? �?
�? �? Example: "UpperBody" has children "Chest", "LeftArm"     �? �?
�? �?   �?"Chest" maps to BODY �?UpperBody �?BODY              �? �?
�? └────────────────────────────────────────────────────────────�? �?
�?                             �?(no consensus)                    �?
�? ┌─ Strategy 3: Ancestor Chain Inheritance ──────────────────�? �?
�? �? Walk up the YSM parent chain.                             �? �?
�? �? If any ancestor maps to a known part �?inherit it.        �? �?
�? └────────────────────────────────────────────────────────────�? �?
�?                             �?(no ancestor match)               �?
�? ┌─ Strategy 4: Spatial Position Analysis ───────────────────�? �?
�? �? Use the bone's world-space pivot + subtree bounding box   �? �?
�? �? to determine body region:                                 �? �?
�? �?                                                           �? �?
�? �? Compute world-space bounding box of entire subtree:       �? �?
�? �?   bbox = sum of all cube origins + sizes                  �? �?
�? �?   centerY = bbox.center.y                                 �? �?
�? �?   centerX = bbox.center.x                                 �? �?
�? �?   extentZ = bbox.size.z / 2                               �? �?
�? �?                                                           �? �?
�? �? Region classification:                                    �? �?
�? �?   centerY > 22         �?HEAD                             �? �?
�? �?   centerY 10-22, |x|<4 �?BODY                             �? �?
�? �?   centerY 8-22, x<-4  �?RIGHT_ARM                         �? �?
�? �?   centerY 8-22, x>4   �?LEFT_ARM                          �? �?
�? �?   centerY < 8, x<0    �?RIGHT_LEG                         �? �?
�? �?   centerY < 8, x>0    �?LEFT_LEG                          �? �?
�? �?   extentZ > 6         �?probably arms (forward reach)     �? �?
�? └────────────────────────────────────────────────────────────�? �?
�?                             �?(no spatial match)                �?
�? ┌─ Strategy 5: Hierarchical Similarity ─────────────────────�? �?
�? �? Compare the tree's topology (depth, child count,          �? �?
�? �? branching pattern) against known YSM model archetypes.    �? �?
�? �? Use min-edit-distance to find closest archetype.          �? �?
�? └────────────────────────────────────────────────────────────�? �?
�?                             �?(fallback)                        �?
�? ┌─ Strategy 6: Default ─────────────────────────────────────�? �?
�? �? RETURN PlayerModelParts.BODY                              �? �?
�? └────────────────────────────────────────────────────────────�? �?
�?                                                                 �?
└─────────────────────────────────────────────────────────────────�?
```

### 3.2 Known YSM Bone Name Patterns

From analysis of real YSM models, here are common bone names and their CPM mappings:

```
Head Group:
  "MHead", "AllHead", "Head", "hat", "helmet"
  �?HEAD

Body Group (core):
  "MAllBody", "Allbody", "MUpperBody", "UpperBody", "Upbody",
  "Chest", "MUpbody", "Body", "Waist", "Belly"
  �?BODY

Body Group (accessories on body):
  "PistolLocator", "RightWaistLocator", "LeftWaistLocator",
  "Backpack", "Tail", "WingL", "WingR", "Skirt"
  �?BODY (stay with body subtree)

Left Arm Group:
  "LeftArm", "LArm", "LeftArmLocator", "LeftHand",
  "LeftGlove", "LeftSleeve"
  �?LEFT_ARM

Right Arm Group:
  "RightArm", "RArm", "RightArmLocator", "RightHand",
  "RightGlove", "RightSleeve"
  �?RIGHT_ARM

Left Leg Group:
  "LeftLeg", "LLeg", "LeftLegLocator", "LeftFoot",
  "LeftBoot", "LeftShoe"
  �?LEFT_LEG

Right Leg Group:
  "RightLeg", "RLeg", "RightLegLocator", "RightFoot",
  "RightBoot", "RightShoe"
  �?RIGHT_LEG

Utility/Non-Standard:
  "Mroot", "root", "molang", "controller", "locator"
  �?Keep with parent tree (don't create new root)
```

### 3.3 Subtree Root Detection

Not every YSM bone without a `parent` field is a "root" for CPM purposes. Some are grouping containers:

```
YSM hierarchy:
  Mroot (parent=null)        �?container root �?maps based on children
  └─ root (parent=Mroot)     �?NOT a subtree root (stays under Mroot)
  └─ MAllBody (parent=root)  �?body subtree root �?if children map to BODY
  └─ MHead (parent=root)     �?head subtree root �?if children map to HEAD
  └─ LeftArm (parent=...)    �?arm subtree root �?maps to LEFT_ARM
```

**Detection rule**: A bone is a "subtree root for CPM" if:
1. It has `parent == null` (YSM top-level), OR
2. Its name maps to a DIFFERENT CPM part than its YSM parent

**This is the ONLY re-parenting allowed**: when a bone and its parent map to different CPM parts, the bone starts a new subtree under the new CPM root. But ALL its children stay under it.

### 3.4 YSM Utility Bones

Some YSM bones are pure "utility" �?they have no cubes and exist only for animation control:

- `Mroot`, `root`: Model root containers
- `molang`: Molang expression holder
- `controller`: Animation controller reference
- `*Locator`: Attachment point for other models

**Handling**: Utility bones become hidden ModelElements (no rendering). Their children still get placed correctly.

---

## 4. Model Assets Conversion

### 4.1 Textures (Already Working �?Minor Refinements)

**Current**: Multi-texture slot system via `TextureSlot`. Works correctly.

**Refinements**:
- Detect texture atlas dimensions from `ysm.json` or model JSON `texture_width`/`texture_height`
- Warn if modeled UVs reference outside texture bounds
- Preserve original texture filenames as slot names

### 4.2 Non-Player Models (Extra Models)

YSM projects can have additional models beyond the body:
- `arrow.json` �?Arrow projectile model
- `parcool.json` �?Parkour animation model
- Custom locator models

**Current**: Imported as `ModelElement` containers under `editor.elements`.

**Refinements**:
- Create named containers `YSM::arrow`, `YSM::parcool`
- Store model type metadata for future use
- These models are visible in the editor tree but not animated with body animations

### 4.3 Sounds (New)

YSM projects can include sound files in `sounds/` directory.

**Implementation**:
- Detect `.ogg` files in ZIP archive
- Store in `YsmModelData.sounds: Map<String, byte[]>`
- Create `EditorSound` entries if CPM supports sound triggering
- Phase 1: Store raw bytes, log availability

### 4.4 Metadata

Transfer from `ysm.json`:
- `metadata.name` �?`editor.description.name`
- `metadata.authors` �?`editor.description.desc`
- `metadata.license` �?stored in description text
- `metadata.tips` �?stored in description text

---

## 5. Model Conversion �?Detailed Algorithm

### 5.1 Algorithm: `convertModel(ysmData, editor)`

```java
void convertModel(YsmModelData ysmData, Editor editor) {
    // 1. Parse all bones from main model JSON
    List<BedrockBone> allBones = BedrockModelParser.parse(ysmData.mainModelJson);
    
    // 2. Build lookup maps
    Map<String, BedrockBone> boneIndex = indexByName(allBones);
    Map<String, List<BedrockBone>> childrenMap = groupByParent(allBones);
    
    // 3. Hide all CPM vanilla root part cubes
    for (ModelElement root : editor.elements) {
        root.hidden = true;
    }
    
    // 4. Build CPM root part lookup
    Map<PlayerModelParts, ModelElement> cpmRoots = new EnumMap<>(PlayerModelParts.class);
    for (PlayerModelParts part : PlayerModelParts.VALUES) {
        if (part == CUSTOM_PART) continue;
        cpmRoots.put(part, findRootElement(editor, part));
    }
    
    // 5. Pre-compute YSM world positions (for animation deltas later)
    Map<String, Vec3f> ysmWorldPositions = new HashMap<>();
    for (BedrockBone bone : allBones) {
        ysmWorldPositions.put(bone.name, computeYsmWorldPos(bone, boneIndex));
    }
    
    // 6. Identify YSM subtree roots (bones that start a new CPM part group)
    List<SubtreeInfo> subtrees = identifySubtrees(allBones, boneIndex);
    
    // 7. For each subtree, classify and place under CPM root
    Map<String, ModelElement> allElements = new HashMap<>();
    for (SubtreeInfo subtree : subtrees) {
        // Classify the entire subtree to a CPM part
        PlayerModelParts part = classifySubtree(subtree, allBones, boneIndex);
        ModelElement cpmRoot = cpmRoots.getOrDefault(part, getOrCreateOrphanRoot(editor));
        
        // Place the subtree root under the CPM root
        Vec3f vanillaPos = YsmCoordUtil.getVanillaPartPosition(part);
        Vec3f adjustedPos = subtree.rootBone.pivot.sub(vanillaPos);
        
        buildSubtree(subtree.rootBone, allBones, cpmRoot, adjustedPos,
                     boneIndex, childrenMap, allElements, editor);
    }
    
    // 8. Handle any missed bones (orphans) �?attach to YSM_UNMAPPED
    // ...
    
    // 9. Import extra models
    for (var extraEntry : ysmData.extraModelJsons.entrySet()) {
        importExtraModel(extraEntry.getKey(), extraEntry.getValue(), editor, allElements);
    }
    
    // Store world positions for animation converter
    return allElements, ysmWorldPositions;
}
```

### 5.2 Algorithm: `buildSubtree`

```java
/**
 * Build CPM ModelElement tree for a YSM bone subtree.
 * Preserves ALL parent-relative positions within the subtree.
 */
void buildSubtree(BedrockBone bone, List<BedrockBone> allBones,
                  ModelElement cpmParent, Vec3f topPos,
                  Map<String, BedrockBone> boneIndex,
                  Map<String, List<BedrockBone>> childrenMap,
                  Map<String, ModelElement> allElements, Editor editor) {
    
    // Create element for this bone
    ModelElement elem = new ModelElement(editor);
    elem.name = bone.name;
    elem.parent = cpmParent;
    cpmParent.children.add(elem);
    allElements.put(bone.name, elem);
    
    // Set position (adjusted for subtree root, parent-relative for children)
    if (topPos != null) {
        elem.pos = new Vec3f(topPos);  // Adjusted world position
    } else {
        // Parent-relative: bone.pivot - parentBone.pivot
        BedrockBone parentBone = boneIndex.get(bone.parent);
        elem.pos = bone.pivot.sub(parentBone.pivot);
    }
    
    // Rotation (1:1 degrees)
    if (bone.rotation.isNonZero()) {
        elem.rotation = new Vec3f(bone.rotation);
    }
    
    // Visibility
    if (bone.neverRender || bone.cubes.isEmpty()) {
        elem.hidden = true;
    }
    
    // Mirror
    if (bone.mirror) {
        elem.mirror = true;
    }
    
    // Create cube elements for each Bedrock cube in this bone
    for (BedrockCube cube : bone.cubes) {
        ModelElement cubeElem = createCubeElement(cube, bone, elem, editor);
        // cubeElem.offset = cube.origin - bone.pivot
        // cubeElem.size = cube.size
        // ... UV mapping, inflate, mirror ...
    }
    
    // Recursively build child bones (keep parent-relative, no topPos)
    List<BedrockBone> children = childrenMap.getOrDefault(bone.name, List.of());
    for (BedrockBone child : children) {
        buildSubtree(child, allBones, elem, null,  // null = use parent-relative
                     boneIndex, childrenMap, allElements, editor);
    }
}
```

### 5.3 Subtree Identification

```java
/**
 * Identify YSM bones that start a new CPM part subtree.
 * A bone starts a new subtree if:
 * 1. It has no parent (true YSM root), OR
 * 2. Its computed CPM part differs from its YSM parent's CPM part
 */
List<SubtreeInfo> identifySubtrees(List<BedrockBone> allBones,
                                    Map<String, BedrockBone> boneIndex) {
    List<SubtreeInfo> subtrees = new ArrayList<>();
    Map<String, PlayerModelParts> partCache = new HashMap<>();
    
    // First pass: compute CPM part for all bones (quick name-based)
    for (BedrockBone bone : allBones) {
        partCache.put(bone.name, classifyBoneQuick(bone, allBones, boneIndex));
    }
    
    // Second pass: find subtree roots
    for (BedrockBone bone : allBones) {
        if (bone.parent == null) {
            // True YSM root �?always a subtree root
            subtrees.add(new SubtreeInfo(bone, partCache.get(bone.name)));
        } else {
            PlayerModelParts myPart = partCache.get(bone.name);
            PlayerModelParts parentPart = partCache.get(bone.parent);
            if (myPart != parentPart && parentPart != null && myPart != null) {
                // Part boundary �?new subtree root
                subtrees.add(new SubtreeInfo(bone, myPart));
            }
        }
    }
    
    return subtrees;
}
```

---

## 6. Animation Conversion

### 6.1 Key Principle

YSM animations store bone transforms in **absolute world space**. CPM animations store transforms as **deltas from the element's default position**.

When a bone's subtree is placed under a CPM root part, its default position is different from its YSM default. Animation positions must be converted to deltas:

```
// For each position keyframe:
cpmDelta = ysmAnimPosition - ysmDefaultWorldPosition

// For rotation keyframes (1:1):
cpmRotation = ysmAnimRotation

// For scale keyframes (1:1):
cpmScale = ysmAnimScale
```

### 6.2 Animation Type Mapping

| YSM Animation | CPM AnimationType | Notes |
|---------------|-------------------|-------|
| `idle`, `walk`, `run`, `sneak`, `swim`, `fly`, etc. | `POSE` with `VanillaPose` | Name-based matching |
| Gesture animations (from `extra_animation`) | `GESTURE` | Mapped via ysm.json |
| Arm animations (`hold_mainhand:*`, `use_mainhand:*`) | `POSE` | Hand/item poses |
| Unknown animations | `CUSTOM_POSE` | Fallback |

### 6.3 Frame Conversion

```
Bedrock keyframe time (seconds) �?CPM frame index:
  Each unique keyframe time becomes a separate CPM frame,
  preserving exact timing.
```

### 6.4 Position Delta Computation (Critical Fix)

The current code computes `delta = ysmAnimValue - ysmDefaultWorldPos` which is correct in concept but the `ysmDefaultWorldPos` is computed by `computeWorldPos` which sums ancestor pivots. After re-parenting, this is wrong.

**Fixed approach**: Store each bone's **YSM default world position** at parse time (before any CPM re-parenting). This is calculated once from the original YSM hierarchy:

```java
// Pre-compute once, before any CPM re-parenting
Map<String, Vec3f> ysmDefaultWorldPos = new HashMap<>();
for (BedrockBone bone : allBones) {
    Vec3f worldPos = new Vec3f(bone.pivot);
    String parent = bone.parent;
    while (parent != null) {
        BedrockBone p = boneIndex.get(parent);
        if (p == null) break;
        worldPos.add(p.pivot);
        parent = p.parent;
    }
    ysmDefaultWorldPos.put(bone.name, worldPos);
}

// During animation conversion:
// For each position keyframe at time t for bone B:
Vec3f cpmDelta = keyframePos.sub(ysmDefaultWorldPos.get(boneName));
// Apply this delta as animation frame data
```

---

## 7. Implementation Architecture �?Rewritten Files

### 7.1 New File Structure

```
src/main/java/com/tom/cpm/shared/editor/ysm/
├── YsmProjectLoader.java       # (KEEP, minor updates) ZIP reading
├── YsmModelData.java           # (KEEP, minor updates) DTO
├── BedrockModelParser.java     # (KEEP, minor updates) JSON parsing + UV helpers
├── BedrockAnimationParser.java # (REWRITE) Animation conversion with pre-computed world pos
├── BedrockControllerParser.java# (KEEP) Controller parsing
├── YsmToCpmConverter.java      # (REWRITE) Main converter, subtree-preserving
├── YsmBoneClassifier.java      # (NEW) Smart bone-to-part mapping
├── YsmSubtreeInfo.java         # (NEW) Subtree metadata
└── YsmCoordUtil.java           # (NEW) Coordinate conversion utilities
```

### 7.2 `YsmBoneClassifier` (NEW) �?The Smart Parser

```java
/**
 * Multi-strategy classifier that maps YSM bone trees to CPM root parts.
 * Uses name matching, spatial analysis, and hierarchical similarity.
 */
public class YsmBoneClassifier {
    
    /** Known YSM bone name �?CPM part mappings */
    private static final Map<String, PlayerModelParts> NAME_MAP = initNameMap();
    
    /** YSM utility bone names (stay with parent tree) */
    private static final Set<String> UTILITY_BONES = Set.of(
        "mroot", "root", "molang", "controller"
    );
    
    /**
     * Full multi-strategy classification of a subtree.
     */
    public static PlayerModelParts classifySubtree(
            BedrockBone rootBone,
            List<BedrockBone> allBones,
            Map<String, BedrockBone> boneIndex);
    
    /**
     * Quick classification (strategy 1 + 3 only, for subtree boundary detection).
     */
    public static PlayerModelParts classifyBoneQuick(
            BedrockBone bone,
            List<BedrockBone> allBones,
            Map<String, BedrockBone> boneIndex);
    
    /**
     * Strategy 4: Spatial position analysis.
     */
    public static PlayerModelParts classifyBySpatialPosition(
            BedrockBone bone,
            Map<String, BedrockBone> boneIndex,
            Map<String, List<BedrockBone>> childrenMap);
    
    /**
     * Compute world-space bounding box of a bone and all its descendants.
     */
    public static Box computeSubtreeBoundingBox(
            BedrockBone rootBone,
            Map<String, BedrockBone> boneIndex,
            Map<String, List<BedrockBone>> childrenMap);
    
    /**
     * Strategy 2: Classify by child name consensus.
     */
    public static PlayerModelParts classifyByChildren(
            BedrockBone bone,
            Map<String, List<BedrockBone>> childrenMap);
}
```

### 7.3 `YsmCoordUtil` (NEW) �?Coordinate Utilities

```java
public class YsmCoordUtil {
    
    /** Vanilla Minecraft part positions from PlayerPartValues */
    private static final Map<PlayerModelParts, Vec3f> VANILLA_PART_POS = Map.of(
        PlayerModelParts.HEAD,       new Vec3f(0, 0, 0),
        PlayerModelParts.BODY,       new Vec3f(0, 0, 0),
        PlayerModelParts.LEFT_ARM,   new Vec3f(5, 2, 0),
        PlayerModelParts.RIGHT_ARM,  new Vec3f(-5, 2, 0),
        PlayerModelParts.LEFT_LEG,   new Vec3f(1.9f, 12, 0),
        PlayerModelParts.RIGHT_LEG,  new Vec3f(-1.9f, 12, 0)
    );
    
    /** Get vanilla render position for a CPM root part */
    public static Vec3f getVanillaPartPosition(PlayerModelParts part);
    
    /** Compute world-space position of a bone in the YSM hierarchy */
    public static Vec3f computeYsmWorldPosition(
            String boneName,
            Map<String, BedrockBone> boneIndex);
    
    /** Safe mesh scale from inflate */
    public static float safeMeshScale(float size, float inflate);
}
```

---

## 8. Implementation Phases (Reworked)

### Phase 1: Foundation �?Correct Model Geometry (3-4 days)
**Goal**: Model imports with correct 3D positions, subtree integrity preserved.

1. Create `YsmCoordUtil` �?vanilla part positions, world position computation
2. Create `YsmBoneClassifier` �?smart bone-to-part mapping (strategies 1-4,6)
3. Create `YsmSubtreeInfo` �?subtree boundary detection
4. Rewrite `YsmToCpmConverter.convert()` �?subtree-preserving placement
5. Remove `buildBoneHierarchySmart` and `determineBonePart` (old approach)
6. Keep `buildBoneHierarchy` for recursion within subtrees
7. Keep existing `BedrockModelParser` (solid, just minor cube parse updates)
8. Test with `Avali_零幻.ysmproject` �?verify all bones at correct 3D positions
9. Validate: compare against BlockBench→CPM round-trip visually

### Phase 2: Texture & Assets (1-2 days)
**Goal**: All non-model assets imported correctly.

1. Multi-texture slot system (keep existing, minor refinements)
2. Sound file detection and storage in `YsmModelData`
3. Extra model import refinement (better container naming)
4. Metadata transfer validation

### Phase 3: Animation Rework (2-3 days)
**Goal**: Animations play correctly with subtree-preserving model.

1. Rewrite `BedrockAnimationParser`:
   - Pre-compute YSM default world positions from original hierarchy
   - Convert position keyframes using correct delta-from-default
   - Handle rotation/scale/visibility keyframes
2. Map animation types (POSE, GESTURE, CUSTOM_POSE)
3. Handle timeline events (molang logging, not execution)
4. Gesture extraction from `extra_animation` + controllers

### Phase 4: Advanced Features (2-3 days)
**Goal**: Full feature parity with BlockBench export.

1. Per-face UV full support (all face directions, UV flipping, negative uv_size)
2. Inflate �?meshScale correctness with safety clamping
3. Cube-level pivot/rotation
4. Mirror propagation (bone XOR cube)
5. Extra animation file support (tac, carryon, slashblade, etc.)
6. Animation controller state extraction (basic gesture mappings)

### Phase 5: Polish (1-2 days)
**Goal**: Production quality.

1. Spatial analysis refinement with real model testing
2. Error reporting for malformed YSM files
3. Progress bar during import
4. Warning system for non-convertible features (molang, controllers)
5. Localization strings for new UI elements

**Total**: 9-14 days

---

## 9. Validation Matrix

| # | Check | Method |
|---|-------|--------|
| 1 | Import YSM �?no exceptions | Log output |
| 2 | All bones present (count matches) | Element count vs bone count |
| 3 | No bones at wrong 3D positions | Visual comparison with BlockBench |
| 4 | Bone hierarchy preserved (children under correct parents) | Editor tree view |
| 5 | Subtree boundaries correct (no mid-tree re-parenting) | Editor tree view |
| 6 | Textures load as slots | Skin Settings panel |
| 7 | Animations play with correct bone targets | Animation preview |
| 8 | Position deltas correct (no drift from default pose) | Reset to default, apply animation |
| 9 | Save as .cpmproject, reopen �?model intact | Round-trip test |
| 10 | Vanilla CPM parts remain hidden | Visual check |
| 11 | Extra models imported as named containers | Editor tree view |
| 12 | Sounds detected and logged | Log output |

---

## 10. Key Changes from v1 Plan

| Aspect | v1 (Current) | v2 (Reworked) |
|--------|-------------|---------------|
| Bone placement | Smart re-parenting mid-hierarchy | Subtrees kept intact |
| Position computation | `worldPos - ysmRootWorldPos` | `bone.pivot - vanillaPartPos` (top-level only) |
| Part mapping | Per-bone name match | Multi-strategy subtree classification |
| Animation deltas | Computed after re-parenting | Pre-computed from original YSM hierarchy |
| Coordinate philosophy | Tries to "fix" positions | Preserves original positions, adjusts for CPM root pos |
| Arm models | Skipped entirely | Properly imported under arm root parts |
| New files needed | 6 files | 9 files (3 new: `YsmBoneClassifier`, `YsmSubtreeInfo`, `YsmCoordUtil`) |

---

## 11. Key Technical Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Bone hierarchy mismatch (YSM arbitrary bones, CPM 6 fixed roots) | High | Multi-strategy classifier; untouched bones go under BODY; entire subtrees stay intact |
| Position shift from CPM vanilla part offset | High | `YsmCoordUtil.getVanillaPartPosition()` provides correct offsets; only top-level adjusted |
| YSM animation positions are absolute world-space | Medium | Pre-compute YSM world positions before CPM conversion; deltas computed correctly |
| Per-face UV cannot be losslessly converted | Medium | Use CPM's PerFaceUV feature; skip degenerates; warn |
| Molang expressions in animations | High | Skip molang-driven animations; flag them for manual review |
| Animation controllers state machines | High | Extract gesture mappings only; no state machine conversion |
| Multiple textures (many YSM textures) | Medium | Multi-slot system; default to first; user switches |
| Large textures (1024×1024+) | Low | CPM supports up to 8192×8192 |

---

## 12. Dependencies

- **JSON parsing**: Gson (`com.google.gson`) �?already used in CPM
- **ZIP reading**: `java.util.zip.ZipFile` �?standard Java
- **Image loading**: CPM's `Image` class
- **No new external dependencies needed**
