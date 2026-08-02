# GFBS: glTF

A production-oriented glTF, GLB, and OBJ runtime for Minecraft Forge 1.20.1.

GFBS: glTF loads animated models from Minecraft resources and exposes a reusable Java API for rendering, animation, synchronization, visibility control, custom importers, RenderType selection, culling, and optional voxel collision. It is the model runtime used by GFBS: Main, but it is designed to be integrated by other mods without depending on GFBS-specific content.

## Repository

- [Source code](https://github.com/LytharaLab/GFBS-glTF)
- [Releases](https://github.com/LytharaLab/GFBS-glTF/releases)
- [Issue tracker](https://github.com/LytharaLab/GFBS-glTF/issues)
- [Pull requests](https://github.com/LytharaLab/GFBS-glTF/pulls)
- [1.x API guide](docs/1.0-API.md)

## Status and compatibility

| Component | Version |
| --- | --- |
| GFBS: glTF | `1.2.0` |
| Public API | `1.2` |
| Minecraft | `1.20.1` |
| Minecraft Forge | `47.4.21` |
| Java | `17` |
| glTF | `2.0` |

GFBS: glTF does not require Embeddium, Oculus, or Iris. Oculus and Iris are detected at runtime when present.

## Features

- glTF 2.0, GLB, and OBJ/MTL loading through Minecraft's resource system.
- Asynchronous client-side loading with request deduplication, caching, invalidation, and resource-pack reload support.
- External files, data URIs, sparse accessors, multiple scenes, indexed geometry, and all seven glTF primitive modes.
- Immutable runtime assets with validated node, mesh, material, skin, animation, texture, and scene references.
- Per-instance scenes, visibility, node-subtree visibility, render settings, animation state, and collision state.
- Translation, rotation, scale, skinning, morph targets, generated normals, tangents, vertex colors, and two UV sets.
- Complete glTF 2.0 metallic-roughness material ingestion: base color, metallic-roughness,
  normal, occlusion, emissive, alpha modes/cutoff, double-sided state, and sampler state.
- Independent UV selection for every material texture, `KHR_texture_transform`,
  `KHR_materials_unlit`, and `KHR_materials_emissive_strength`.
- A dedicated full-bright emissive pass that samples the actual emissive texture instead of
  making the entire base material full-bright.
- Animation playback, seeking, pausing, transitions, layers, masks, additive blending, fades, and user-defined events.
- Latency-tolerant server-authoritative animation synchronization for entities, block entities, and custom targets, with RTT clock probes, actual-TPS estimation, fractional-tick prediction, smooth speed correction, and sequence ordering.
- Primitive frustum culling, maximum render distance, optional occlusion queries, and per-part filtering.
- Per-instance, per-node, and per-part RenderType selection with a validated custom RenderType builder.
- Native triangle submission for glTF triangle, strip, and fan primitives—no degenerate quad padding.
- Forge static block/item model loading through `gfbs_gltf:gltf`.
- Oculus/Iris shadow-map rendering with a dedicated depth-writing caster path.
- Optional bounds, cached voxel, and current-pose precise collision.
- Extensible model importer registry for third-party formats.
- Defensive resource limits and namespace-local resource resolution.

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

## Animation synchronization in 1.2.0

GFBS: glTF 1.2.0 keeps animation commands server-authoritative without streaming bones or
quantizing rendering to the server tick rate. The server sends clip state and time anchors; each
client reconstructs the same logical timeline and advances its instance at render-frame frequency.

The client periodically measures network RTT, estimates the server's actual logical TPS, scales
real-time playback to that TPS, and uses a fractional server-tick clock. Normal drift is corrected by
temporarily changing the live playback speed by a small amount instead of seeking every client tick. A late packet is applied at the
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

Every atlas texture used by the glTF material must be declared in the model JSON. Map textures by
material index or glTF material name through `material_textures`; `material_0`, material-name,
`texture`, and `particle` slots are used as fallbacks. The API guide documents every option.

See the [GFBS: glTF 1.x API guide](docs/1.0-API.md) for loading, animation, rendering, synchronization, importers, culling, RenderTypes, collision, and migration details.

## Rendering policy

GFBS: glTF 1.2 does not ship a separate no-shader PBR pipeline. Normal rendering uses Minecraft's `DefaultVertexFormat.NEW_ENTITY`, native triangle draw mode, and the original entity shaders.

- Without a shader pack, base textures retain Minecraft entity lighting while emissive factors
  and textures are rendered in an independent full-bright additive pass.
- `MASK` materials honor their declared `alphaCutoff`; `BLEND`, `doubleSided`, texture sampler,
  `TEXCOORD_0`/`TEXCOORD_1`, and texture-transform state are retained.
- Unlit materials use full-bright base rendering without changing ordinary lit materials.
- With an active Oculus or Iris shader pack, the same entity rendering path remains in use,
  LabPBR normal/specular companions are created lazily when supported, and emissive textures
  remain an explicit color pass.
- During an Oculus/Iris shadow pass, GFBS switches to a depth-writing caster path and bypasses color-pass culling and custom RenderType overrides.
- The active shader pack still controls shadow resolution, distance, filtering, and whether block entities participate in its shadow pass.

This keeps the no-shader path compatible with Minecraft's renderer and avoids bundling a second PBR shader implementation.

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
