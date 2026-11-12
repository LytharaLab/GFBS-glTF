# GFBS: glTF

A production-oriented glTF, GLB, and OBJ runtime for Minecraft Forge 1.20.1.

GFBS: glTF loads animated models from Minecraft resources and exposes a reusable Java API for rendering, animation, synchronization, visibility control, custom importers, RenderType selection, culling, optional voxel collision, and dependency-aware plugins. It is the model runtime used by GFBS: Main, but it is designed to be integrated by other mods without depending on GFBS-specific content.

---
#### Team maintaining this project: [GFBS Mod Series Maintainers](https://github.com/orgs/LytharaLab/teams/gfbs-mod-series-maintainers)
#### 维护此项目的团队: [GFBS Mod Series Maintainers](https://github.com/orgs/LytharaLab/teams/gfbs-mod-series-maintainers)
---

## Repository

- [Source code](https://github.com/LytharaLab/GFBS-glTF)
- [Releases](https://github.com/LytharaLab/GFBS-glTF/releases)
- [Issue tracker](https://github.com/LytharaLab/GFBS-glTF/issues)
- [Pull requests](https://github.com/LytharaLab/GFBS-glTF/pulls)
- [1.6 API guide](docs/1.x-API.md)
- [1.6 migration and LabPBR guide](docs/MIGRATING-1.6.md)
- [Performance architecture and measurements](docs/PERFORMANCE.md)
- [Plugin development guide](docs/PLUGINS.md)
- [1.5 migration guide](docs/MIGRATING-1.5.md)
- [1.5.1 migration guide](docs/MIGRATING-1.5.1.md)

## Status and compatibility

| Component | Version |
| --- | --- |
| GFBS: glTF | `1.6.1` |
| Public API | `1.6.1` |
| Minecraft | `1.20.1` |
| Minecraft Forge | `47.4.21` |
| Java | `17` |
| glTF | `2.0` |

GFBS: glTF does not require Embeddium, Oculus, or Iris. Oculus and Iris are detected at runtime when present.

## Features

- glTF 2.0, GLB, and OBJ/MTL loading through Minecraft's resource system.
- Asynchronous client-side loading with request deduplication, caching, invalidation, and resource-pack reload support.
- External files, data URIs, sparse accessors, multiple scenes, indexed geometry, and all seven glTF primitive modes.
- Rigid `EXT_mesh_gpu_instancing` imports with shared meshes and independently controllable instance nodes.
- Immutable runtime assets with validated node, mesh, material, skin, animation, texture, and scene references.
- Complete per-instance node graph with indexed/name/path lookup, duplicate-name handling, subtree
  operations, self/subtree visibility, TRS/matrix/post-transform overrides, morph weights, render,
  shadow, collision, custom parameters, and per-primitive state.
- Translation, rotation, scale, skinning, morph targets, generated normals, tangents, vertex colors, and two UV sets.
- Complete glTF 2.0 metallic-roughness material ingestion: base color, metallic-roughness,
  normal, occlusion, emissive, alpha modes/cutoff, double-sided state, and sampler state.
- Independent UV selection for every material texture, `KHR_texture_transform`,
  `KHR_materials_unlit`, and `KHR_materials_emissive_strength`.
- A dedicated full-bright emissive pass that samples the actual emissive texture instead of
  making the entire base material full-bright.
- General runtime material switching per primitive: source-material replacement, named reusable
  variants, complete sparse overrides for every glTF material property, and `PBR`, `UNLIT`, or
  game-style `NEON` shading without mutating shared assets.
- Animation playback, seeking, pausing, transitions, layers, masks, additive blending, fades, and user-defined events.
- Latency-tolerant server-authoritative animation synchronization for entities, block entities, and custom targets, driven by a tick-free monotonic seconds timeline: RTT clock probes, exact `double` timeline math, a frame-driven heartbeat with fixed logical steps, smooth speed correction, and sequence ordering.
- Primitive frustum culling, maximum render distance, optional occlusion queries, and per-part filtering.
- Shared scene BVH, spatial sections for large rigid triangle meshes, incremental node transforms,
  asynchronous preparation, and lazy texture residency with cold-resource eviction.
- Per-instance, per-node, and per-part RenderType selection with a validated custom RenderType builder.
- Native triangle submission for glTF triangle, strip, and fan primitives—no degenerate quad padding.
- Forge static block/item model loading through `gfbs_gltf:gltf`, including independent emissive
  masks and one-tile repeat-domain UV canonicalization without a block entity.
- Oculus/Iris shadow-map rendering with a dedicated depth-writing caster path.
- Optional bounds, cached voxel, and current-pose precise collision.
- Extensible model importer registry for third-party formats.
- Dependency-aware plugin host with multiple plugins per Forge mod, direct and IMC registration,
  deterministic ordering, lifecycle rollback, diagnostics, and runtime disable support.
- Type-safe custom extension points, ordered asset processors, client resource-reload hooks, and
  quarantined render hooks with custom passes that reuse GFBS skinning, morphing, culling, and
  resident GPU geometry.
- Defensive resource limits and namespace-local resource resolution.

## Plugin system in 1.5.0

Version 1.5 turns extension support into a first-class subsystem. A plugin has an ID, version,
owning Forge mod, dependency list, lifecycle, and any number of typed extensions. One Forge mod can
own multiple plugins, while a one-purpose plugin can still ship as its own normal mod JAR.

Plugins can currently integrate at the model-import, post-import asset-processing, client resource
reload, and staged rendering boundaries. They may also define new typed extension points for other
plugins. Render extensions can add scene-wide passes before, between, or after the built-in passes,
or suppress and replace a built-in pass. Custom passes receive resolved model/material/part state
and use the existing skin, morph, visibility, culling, lighting, and resident-geometry machinery.

See [Plugin development](docs/PLUGINS.md) for registration, dependencies, lifecycle rules, custom
extension points, and a non-invasive glow-pass example.

## Scene rendering in 1.6.1

Large scenes use a shared spatial index to select candidate nodes before resolving mutable state.
Moved and animated subtrees remain candidates even after leaving their authored bounds. Large
rigid triangle primitives receive spatial sections that share the original vertex arrays.

Without an active shader pack, larger rigid parts reuse resident GPU geometry shared across mesh
occurrences and material layouts. Tiny parts in large scenes stream into Minecraft's buffers to
reduce draw calls. With Iris/Oculus, native triangles pass through the caller's entity buffers so
the pipeline can supply its entity IDs, tangents, render phases, and shadow shaders.

The default client loader prepares scene plans and spatial data on its loading worker. Textures
upload on first use, GPU caches evict cold entries at frame boundaries, and runtime node/primitive
state is created on demand. See [Performance architecture](docs/PERFORMANCE.md) for budgets,
measured cases, integration details, and the remaining workload limits.

## Installation

1. Install Minecraft Forge for Minecraft `1.20.1`.
2. Download a GFBS: glTF JAR from [GitHub Releases](https://github.com/LytharaLab/GFBS-glTF/releases), or build the project from source.
3. Place the JAR in the `mods` directory of each required client and server.

Mods that use GFBS: glTF as a library should declare `gfbs_gltf` as a dependency and package models and related textures under their own asset namespace.

## Building from source

Clone the official repository and run:

```bash
git clone https://github.com/LytharaLab/GFBS-glTF.git
cd GFBS-glTF
./gradlew build
```

On Windows PowerShell:

```powershell
git clone https://github.com/LytharaLab/GFBS-glTF.git
Set-Location GFBS-glTF
.\gradlew.bat build
```

The built JAR is written to `build/libs/`.

Run the test suite with:

```bash
./gradlew test
```

Run the complete Gradle verification lifecycle with:

```bash
./gradlew check
```

## Quick start

Place a model in a namespaced client resource location such as:

```text
src/main/resources/assets/example/models/reactor.glb
```

Load it through the client model manager:

```java
ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(
    "example",
    "models/reactor.glb"
);

ClientGltfApi.models().load(modelId).thenAccept(asset -> {
    GltfInstance instance = new GltfInstance(asset);
    instance.animations().play("idle", PlaybackOptions.loop());

    // Store the instance in the owning entity, block entity, renderer, or system.
});
```

Advance the instance with elapsed seconds:

```java
instance.update(deltaSeconds);
```

Render it from an entity, block-entity, or other client renderer:

```java
GltfRenderer.render(
    instance,
    poseStack,
    buffers,
    packedLight,
    packedOverlay
);
```

The caller owns model placement. Apply translation, rotation, and scale to the supplied `PoseStack` before calling the renderer.

Close an instance when it is no longer used, especially when collision has been enabled:

```java
instance.close();
```

## Runtime node and material state in 1.3.0

Every `GltfInstance` owns a complete mutable state graph through `instance.nodes()`. Imported
`GltfAsset`, `GltfNode`, and `GltfMaterial` objects remain immutable and safe to share; all live
changes are isolated to that instance.

Nodes can be selected by index, exact name, or hierarchy path. A path is the unambiguous choice
when an asset legitimately contains duplicate names:

```java
GltfNodeState panel = instance.nodes().requirePath("/reactor/console/service_panel");

panel.selfVisible(false);          // Hide only this node's meshes.
panel.subtreeVisible(true);        // Keep traversal of children enabled.
panel.translation(0.0f, 0.25f, 0.0f);
panel.alpha(0.8f);
panel.castShadows(false);
panel.parameter("owner", blockEntityId);
```

`instance.nodes().snapshot()` captures the complete node/primitive/variant state for transactional
changes, presets, rollback, or temporary effects; restore it with `instance.nodes().restore(...)`.
Custom parameter values are copied shallowly because their application-defined object types are
unknown to GFBS: glTF.

Matrix-authored nodes use `localMatrix(...)` for complete replacement or
`postTransform(...)` for an additional transform. TRS-authored nodes support independent
translation, quaternion rotation, scale, and morph-weight overrides. The same resolved transforms
and morph weights are used by rendering and enabled collision.

Material switching is not tied to indicator lights. A `GltfMaterialVariant` can replace the
source material by index or name, override any combination of base color/texture,
metallic-roughness, normal, occlusion, emissive, alpha, double-sided, and shading properties, or
combine replacement and overrides. Variants can be applied to any primitive or an entire node
subtree:

```java
GltfNodeManager nodes = instance.nodes();

nodes.defineVariant("plastic", GltfMaterialVariant.override(
    GltfMaterialOverride.builder()
        .shadingMode(GltfShadingMode.PBR)
        .build()
));
nodes.defineVariant("neon", GltfMaterialVariant.override(
    GltfMaterialOverride.builder()
        .shadingMode(GltfShadingMode.NEON)
        .neonStrength(4.0f)
        .build()
));

GltfPrimitiveState surface = nodes.primitive(nodeIndex, meshIndex, primitiveIndex);
surface.variant(powered ? "neon" : "plastic");
```

`NEON` makes the effective base surface full-bright and feeds the same base color/texture into the
dedicated emissive pass, allowing shader-pack bloom. It deliberately does not create block light
or a dynamic point light. Resolved variants are cached, so repeatedly switching between reusable
states does not rebuild runtime materials every frame.

## Tick-free synchronization in 1.5.1

Version 1.5.1 removes the two properties that made long-running synchronized animations stutter, and
it removes the tick loop from the synchronization path entirely.

**The timeline is exact.** Every timestamp on the animation path is monotonic seconds in `double`:
`SyncedAnimationState`, the packet payloads, `PlaybackOptions`, the `AnimationController` playhead,
its seek API, and `GltfInstance.update`. 1.5.0 returned the authoritative time as a `float`, whose
resolution is relative: after roughly six days of logical animation time a float step is already
62.5 ms. The client controller has a 15 ms dead zone, so it could never settle — it kept chasing a
staircase with speed corrections, which the player sees as stuttering. The same timestamp in `double`
still carries ~0.1 ns of resolution, so the controller sits inside its dead zone and the pose simply
advances.

**The timeline left the tick domain.** States, packets and the client estimator carry seconds, not
game ticks, so `Level#getGameTime()` can no longer influence a running animation: world-time resets,
`/time set`, TPS drops and tick freezes neither move nor slow down a clip.

**The lifecycle is ours.** `RenderLevelStageEvent.Stage.AFTER_ENTITIES` drives
`ClientHeartbeat`; the heartbeat owns a pause-aware `SessionClock` and a frame-rate independent
`FixedStepScheduler`, and it calls `ClientAnimationSync.step(...)` at a fixed 20 Hz logical cadence
derived from real frame time. There is no `TickEvent` registration, a stalled or minimized frame
drops its catch-up debt instead of replaying it, and single-player pause freezes the logical clock so
a paused world resumes where it stopped. On the server, `ServerClock` is the authority and
`ServerTimeEstimator` solves for a single offset because both endpoints already share the same
real-time rate.

The controller still never seeks every frame: normal drift is absorbed by small live speed changes,
a late packet is applied at the position the clip should have reached, and only a catastrophic error
triggers one blended rebase, protected by a cooldown.

## Animation synchronization in 1.2.0

GFBS: glTF 1.2.0 keeps animation commands server-authoritative without streaming bones or
quantizing rendering to the server tick rate. The server sends clip state and time anchors; each
client reconstructs the same logical timeline and advances its instance at render-frame frequency.

The 1.2.0 clock inferred the server's logical TPS and expressed the timeline in fractional server
ticks; 1.5.1 replaced both with the monotonic seconds timeline described above. Normal drift is still
corrected by temporarily changing the live playback speed by a small amount instead of seeking every frame. A late packet is applied at the
position the animation should have reached on the server, with a short pose blend to hide the
unavoidable first visible jump. Only catastrophic desynchronization can trigger a one-time blended
rebase, protected by a cooldown.

This design remains synchronized under high latency—including roughly 500 ms RTT—while avoiding
the repetitive 20 Hz snapping present in earlier versions. Network latency can still delay the
first moment at which a brand-new server command becomes knowable to the client; no genuine
server-authoritative system can display an unseen command before its packet arrives.

After binding with `SyncedGltfAnimations.bind(...)`, continue calling `instance.update(deltaSeconds)`
from the render-side owner. Do not independently restart or seek the same base animation from block
state or renderer code, because the synchronized target owns that base layer.

## Static Forge block and item models

Use the `gfbs_gltf:gltf` geometry loader for glTF or GLB models that should be baked once at
resource-load time and do not need animation playback:

```json
{
  "loader": "gfbs_gltf:gltf",
  "model": "example:models/block/console.glb",
  "textures": {
    "particle": "example:block/console",
    "material_0": "example:block/console"
  },
  "material_textures": {
    "0": "#material_0"
  },
  "render_type": "minecraft:cutout",
  "scale": 1.0,
  "translation": [0.0, 0.0, 0.0],
  "flip_v": false,
  "shade": true,
  "automatic_culling": false
}
```

Place this JSON at `assets/example/models/block/console.json`, the GLB at
`assets/example/models/block/console.glb`, and reference `example:block/console` from the
blockstate or item model. The loader bakes the selected scene's default pose, including node
transforms, default morph weights, and rest-pose skinning. Animation clips are intentionally not
executed on this path.

Embedded glTF materials automatically generate atlas sprites with LabPBR companions. Explicit
`material_textures`, `material_0`, material-name and `texture` slots override generated materials.
Set `flip_v` when the exporter's V axis needs mirroring; the generated base, `_n`, and `_s` sprites
follow that orientation and upright/flipped variants remain distinct. Independent emissive maps are
sampled per texel into the generated `_s` emission channel, so shader-pack emission does not require
the emissive texture to equal the base-color texture. UVs covering one complete repeated texture
tile are canonicalized into the atlas domain; ranges that genuinely cross tile boundaries remain
triangle-charted. The API guide documents every option.

See the [GFBS: glTF 1.x API guide](docs/1.x-API.md) for loading, animation, rendering, synchronization, node state, material variants, importers, culling, RenderTypes, collision, and migration details.

## Rendering policy

Rendering uses Minecraft's entity shaders and `DefaultVertexFormat.NEW_ENTITY`. Iris/Oculus can
extend this format through its normal entity pipeline.

- Without a shader pack, base textures retain Minecraft entity lighting while emissive factors
  and textures are rendered in an independent full-bright additive pass.
- Base alpha coverage follows the declared material mode. Blended parts are ordered by distance.
- Unlit materials use Minecraft's unlit entity shader.
- With an active Oculus or Iris shader pack, the same entity rendering path remains in use,
  LabPBR normal/specular companions are created lazily when supported, and emissive textures
  remain an explicit color pass.
- Static baked materials encode independent emissive textures into LabPBR specular alpha for
  shader-pack emission and bloom. They retain chunk batching and do not require a block entity.
- Native LabPBR 1.3 maps support normal XY, AO, height, smoothness, dielectric F0, metal IDs,
  porosity/SSS, and emission. Material extras and Java overrides can provide exact channel values.
- During an Oculus/Iris shadow pass, GFBS uses a depth-writing caster path. An optional adapter
  follows the shader pack's own entity-shadow frustum.
- The active shader pack still controls shadow resolution, distance, filtering, and whether block entities participate in its shadow pass.

The [1.6 migration guide](docs/MIGRATING-1.6.md) explains UV mapping requirements, glTF-to-LabPBR
conversion, optional shader-pack channels, and shadow integration.

## Project structure

```text
src/main/java/org/lytharalab/gfbs/gltf/api/   Public integration API
src/main/java/org/lytharalab/gfbs/gltf/core/  Model decoding and animation core
src/main/java/org/lytharalab/gfbs/gltf/client/ Client loading and rendering
src/main/java/org/lytharalab/gfbs/gltf/network/ Animation synchronization
src/main/java/org/lytharalab/gfbs/gltf/collision/ Collision implementation
src/main/resources/                            Forge metadata and mixin configuration
src/test/java/                                 Unit tests
```

## Acknowledgements

GFBS: glTF draws on and incorporates portions of code from the **ModelLoader** mod by Bilibili creator [洛谔谔](https://space.bilibili.com/3546888156481679), formerly known as `_二千`.

## Contributing

Contributions are welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening an issue or pull request.

## License

GFBS: glTF is available under the [MIT License](LICENSE).

Copyright © 2026 LytharaLab.

Minecraft is a trademark of Microsoft Corporation. This project is not affiliated with or endorsed by Microsoft or Mojang Studios.
