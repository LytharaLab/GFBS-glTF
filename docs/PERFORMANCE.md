# Performance in 1.6.2

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
| Transform every skinned vertex for every pass | Cache CPU deformation and capture eligible multipass GPU deformation |
| Apply identical morph weights in each pass | Reuse a local-space position and normal cache by instance and primitive |
| Rebuild every joint matrix after any transform changes | Track changes to each joint's world matrix and recompute affected entries |
| Stream deformed vertices from the CPU for every draw | Share the GPU mesh and stream compact joint palettes on eligible draws |

## Runtime paths

The vanilla resident path handles larger rigid triangles with built-in opaque/masked render types.
Lit resident geometry requires a conformal transform so uniform scaling preserves normalized
normal lighting. Nonuniform scale and shear use the normal-correct buffered path.
Scene plans and compatible geometry are shared by immutable asset identity. Light, overlay, and
instance tint remain draw state.

Tiny rigid primitives with at most 96 indices in scenes of at least 64 nodes stream through
Minecraft's buffers. Active shader packs, active morph targets, blended draws, and custom
RenderTypes use buffered compatibility paths. Built-in RenderTypes request 64 KiB starting
buffers that grow with actual use.

### Skinned geometry

On desktop OpenGL 3.2, a vertex shader skins the resident bind-pose vertices of eligible
opaque and cutout triangles. The vertex and optional index buffers are per asset primitive, so
animation, material changes, and additional instances reuse the same upload. Indices use 16 or
32 bits according to vertex count. Authored normals allow indexed triangles, strips, and fans to
stay indexed. Without authored normals, GFBS expands the affected triangles to keep flat shading.

Each primitive packs positively weighted joint indices into a dense layout. Four RGBA32F texels
hold one column-major matrix. Device capacity is `GL_MAX_TEXTURE_BUFFER_SIZE / 4`, capped at
65,536 joints. A skin with a larger source index can still render on the GPU when its compact
layout fits. Skinning uses up to four influences per vertex.

The CPU resolves world-space transforms and updates joint matrices only for changed joints.
Changing the mesh node matrix can invalidate all entries because the palette is in mesh-local
space. The compact GPU palette is uploaded on a change or when switching to another palette.
For built-in base and emissive passes with compatible state and no render extensions, a primitive
with at least 2,048 resident vertices and at least two positive influences per source vertex on
average can capture mesh-local positions and normals once into a GPU buffer. The passes then
sample that same buffer. Direct vertex skinning handles single passes and cheaper primitives.

The CPU renderer caches morph results by effective weight values, then caches the skinned local
positions and normals by morph and palette revision. Base, emissive, shadow, and custom passes
can consume the same local arrays with their own placement, light, UVs, and material. Cached
positions are never used as static culling bounds.

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

The CPU deformation cache reserves up to 128 MiB across 4,096 entries. The LRU policy releases
older results as needed, and oversized results use the streamed path. Joint palettes have a
16 MiB per-instance budget. Multipass GPU capture uses a shared 32 MiB pool with a 64-buffer cap.
Cache diagnostics report nominal GPU buffer sizes and CPU deformation reservations.

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

## 1.6.2 verification

Run `./gradlew build` for unit tests and the distributable JAR. On a desktop OpenGL client, run
`./gradlew runGpuSkinningTest` to exercise the production renderer in an isolated Forge run. The
client stops after checking the device's maximum joint palette, CPU/GPU deformed vertices, two
independent instance poses, base and emissive pass reuse, incremental updates, morph cache
invalidation, lit and cutout materials, and resource reload. The test validates framebuffer output
for both backends.

On a GeForce RTX 4060 with Forge 47.4.21 and Java 17, 4,096 samples from a dense 65,536-joint
palette matched exactly. The four-joint compact palette with a source index of 65,535 had a
maximum position component error of about `1.2e-7` and zero mismatched vertices. Both backend
outputs passed framebuffer comparison before and after a resource reload.

A single-pass, 32,258-triangle indexed mesh with one moving joint measured about **6.21 ms CPU**
and **0.067 ms GPU** median Java submission time on that client. The test sampled 40 draws after
16 warmups per mode and drained previous GPU work before timing. These are submission timings
for this workload, with a warmed CPU deformation cache and a resident GPU mesh.

Use `GltfRenderer.diagnostics()` with opt-in collection to measure submission time and cache
behavior on your own models. `GltfRenderer.verifySkinning(...)` can read back a bounded sample of
mesh-local positions and normals when investigating a specific pose. See
[Render diagnostics](1.x-API.md#render-diagnostics).

## Earlier verification coverage

Real client checks covered vanilla Forge and both Oculus 1.8.0/Embeddium 0.3.31/Complementary
Reimagined r5.6.1 and Oculus 1.7.0/Embeddium 0.3.28/Complementary Reimagined r5.2.2.

- Color and shadow dispatch callbacks, rendered screenshots, and shadow-toggle receiver changes.
- Exact native LabPBR normal/specular GPU pixels before and after resource reload.
- A non-square 4×2 packed specular image, categorical last mip, and sampler state across reload.
- Generated static block/item atlas sprites, upright and `flip_v` variants, companion channel
  pixels, and Oculus block-atlas normal/specular pixels.
- Independent emissive-to-specular-alpha conversion, repeat-tile UV canonicalization, and chart
  fallback when wrapping cannot be represented by one atlas domain.
- A resolved Oculus entity-shadow frustum adapter with its opt-in enabled.
- Generated glTF companion pixels, including metal ID 255.
- View-space camera-frustum selection after camera rotation.
- A node moved from an offscreen cell into view.
- Required `EXT_mesh_gpu_instancing` import and the expected GPU triangle count.
- Regression tests for matrix ownership, writable legacy poses, configurable import limits, and
  LabPBR channel/mipmap categories.

The 1.6.1 static-material acceptance pass used Forge 47.4.21, Oculus 1.8.0, Embeddium 0.3.31 and
Rethinking Voxels r0.1-beta9. The runtime selected `gfbs_gltf 1.6.1`, reduced the test resource set
from 14 generated material sprites to one through repeat-domain canonicalization, removed the
non-power-of-two mip-level warning, and showed the independent emissive details and bloom in-world.

Normal project verification uses `./gradlew build`. The 1.6.0 baseline JAR was also exercised in
controlled Forge client runs; re-run equivalent client acceptance after changing atlas, material,
or scene-preparation code.

## Workload limits

Dense visible scenes still consume vertex, image, and shader bandwidth. Animated geometry uses
conservative bounds, and active morphs use the cached CPU path. Calling the historical mutable pose
getter enables array-change detection and conservative scene traversal. Shader-specific optional
LabPBR effects need acceptance with the intended pack and settings.
