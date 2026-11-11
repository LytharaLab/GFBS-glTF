# Performance in 1.6.0

The renderer selects scene work before creating per-instance state or uploading images.
This addresses both large collections of mesh occurrences and oversized individual rigid meshes.

## Resolved bottlenecks

| Previous cost or defect | 1.6 behavior |
| --- | --- |
| Visit every node in each built-in pass | Shared bind-scene BVH selects candidates |
| Move one node and rebuild all world matrices | Recompute affected managed transform subtrees |
| Moved node leaves its original spatial cell | Add moved/animated subtrees back to the candidate set |
| Allocate all runtime state and skin slots eagerly | Allocate state, poses, and palettes on use |
| Store full hierarchy paths for every node | Resolve requested paths without quadratic string storage |
| Validate each child by rescanning a wide parent | Validate parent links in linear time |
| Upload a repeated mesh for each occurrence | Share resident geometry by primitive and baked layout |
| Issue a resident draw for each tiny part | Buffer tiny parts in large scenes |
| One huge primitive passes aggregate bounds | Partition eligible rigid triangles into spatial sections |
| Upload every image/material on first model draw | Upload only requested base images and companions |
| Recreate build buffers for resident uploads | Reuse the resident compile buffer |
| Keep every GPU variant indefinitely | Bound geometry residency and evict cold frame resources |
| Bypass Iris's extended entity data | Submit through the caller's active entity buffers |
| Reuse camera frustum in rotated view coordinates | Explicit view-space frame context |
| Occlusion queries apply projection twice | Use the same active projection path as model draws |

## Runtime paths

The vanilla resident path handles larger rigid triangles with built-in opaque/masked render types.
Lit resident geometry requires a conformal transform so uniform scaling preserves normalized
normal lighting. Nonuniform scale and shear use the normal-correct buffered path.
Scene plans and compatible geometry are shared by immutable asset identity. Light, overlay, and
instance tint remain draw state.

Tiny primitives with at most 96 indices in scenes of at least 64 nodes stream through Minecraft's
buffers. This avoids one state setup and draw per tiny part. Active shader packs, deformation,
transparency, mirror transforms, and custom render types use buffered compatibility paths.
Built-in RenderTypes request 64 KiB starting buffers that grow with actual use.

Iris/Oculus extends native triangle vertices with IDs, center UV, and smooth tangents. GFBS
preserves the supplied buffer source and the shader loader's phase selection.

Large rigid TRIANGLES primitives with at least 16,384 triangles are eligible for spatial sections.
Sections retain original vertex arrays and store triangle-offset lists. Material state and node
controls still address the original primitive. Deformed and blended geometry retains conservative
submission.

## Loading and residency

The default client model manager prepares CPU scene plans, bind matrices, spatial indexes, and
eligible sections on its loading worker. Programmatically created assets and alternate scenes can
call `GltfRenderer.prepareScene(asset, scene)` before drawing.

Resident geometry has a 128 MiB per-asset budget, a 512 MiB combined budget, and a 4,096-entry
per-asset limit. Excess geometry streams. Resources unused for over 180 rendered frames are
eligible for frame-end eviction, checked every 60 frames. Active texture memory depends on the
visible materials and their image sizes. Images are limited by hardware and an 8,192-pixel
dimension limit. Decoded CPU image storage is released after upload.

Load budgets are separate from residency. `GltfLoadLimits` controls combined source bytes, decoded
attribute/animation arrays, and expanded nodes. See the [migration guide](MIGRATING-1.6.md).

## Measured workloads

The 1.6.0 measurements used Java 17, Forge 47.4.21, and desktop OpenGL. Each warm timing
contains 180 synchronous CPU samples after warmup, with identical geometry and distance selection
between 1.5.1 and 1.6. The timed function submits GFBS rendering work to the client buffers.

| Workload | 1.5.1 median / p95 | 1.6 median / p95 |
| --- | --- | --- |
| Vanilla, 50,000 nodes, 32 near | 10.4262 / 12.2793 ms | 0.0593 / 0.1108 ms |
| Oculus 1.8.0, 50,000 nodes, 32 near | 11.4017 / 12.6408 ms | 0.1236 / 0.1891 ms |
| Vanilla, 50,000 nodes, 1,024 near | 18.9316 / 25.6831 ms | 0.6438 / 1.2629 ms |

These measurements describe the tested scene's CPU submission cost. Whole-frame FPS also depends
on Minecraft, visible triangle count, texture bandwidth, shader effects, and the GPU. An unprepared
50,000-node synthetic asset initially spent about 142–150 ms constructing its plan/index. The
preparation API and default worker loader move that construction out of its first render call.
In the final worker-prepared runs, preparation took 293.79 ms in vanilla and 175.69 ms with Oculus.
First render submission then took 2.39 ms and 3.16 ms respectively, including the probe's initial
GPU count verification.

A second workload stored 131,072 indexed triangles in one rigid primitive. GPU
`GL_PRIMITIVES_GENERATED` queries measured exactly two triangles after section culling, matching the
visible region, with both vanilla and Oculus. Rotated-camera screenshots confirmed the visible
tiles remained present.

## Verification coverage

Real client checks covered vanilla Forge and both Oculus 1.8.0/Embeddium 0.3.31/Complementary
Reimagined r5.6.1 and Oculus 1.7.0/Embeddium 0.3.28/Complementary Reimagined r5.2.2.

- Color and shadow dispatch callbacks, rendered screenshots, and shadow-toggle receiver changes.
- Exact native LabPBR normal/specular GPU pixels before and after resource reload.
- A non-square 4×2 packed specular image, categorical last mip, and sampler state across reload.
- Generated static block/item atlas sprites, upright and `flip_v` variants, companion channel
  pixels, and Oculus block-atlas normal/specular pixels.
- A resolved Oculus entity-shadow frustum adapter with its opt-in enabled.
- Generated glTF companion pixels, including metal ID 255.
- View-space camera-frustum selection after camera rotation.
- A node moved from an offscreen cell into view.
- Required `EXT_mesh_gpu_instancing` import and the expected GPU triangle count.
- Regression tests for matrix ownership, writable legacy poses, configurable import limits, and
  LabPBR channel/mipmap categories.

Normal project verification uses `./gradlew build`. The 1.6.0 release JAR was also exercised in
controlled Forge client runs; re-run equivalent client acceptance after changing atlas, material,
or scene-preparation code.

## Workload limits

Dense visible scenes still consume vertex, image, and shader bandwidth. Skinning and active morphs
use CPU deformation with conservative bounds. Calling the historical mutable pose getter enables
array-change detection and conservative scene traversal. Geometric LOD and GPU skinning require
additional work. Shader-specific optional LabPBR effects need acceptance with the intended pack
and settings.
