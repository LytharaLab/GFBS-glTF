# Migrating to 1.6.0

GFBS glTF 1.6.0 targets Minecraft Forge 1.20.1 and Java 17. Existing constructors, animation
timelines, node selectors, material overrides, and plugin entry points remain available.
The animation network protocol remains version 3.

## Frame coordinates

Use the new factory when a Forge entity or world-stage pose stack already includes camera rotation.

```java
GltfRenderContext frame = GltfRenderContext.viewSpace(projection, frustum, camera);
GltfRenderer.render(instance, poseStack, buffers, packedLight, packedOverlay, frame);
```

The existing constructors describe camera-relative positions with world-aligned axes. A caller
with a custom orientation can supply the inverse orientation as the sixth constructor argument.
Keep world placement camera-relative before storing it in a float matrix.

## Preparing large scenes

The default asynchronous client model manager prepares the asset's default scene before completing
its load future. Custom loaders and alternate scenes can prepare on their own loading worker.

```java
GltfRenderer.prepareScene(asset, sceneIndex);
```

Preparation builds CPU draw plans, bind transforms, the node spatial index, and eligible rigid
mesh sections. GPU images and resident geometry upload when first used.
Provide a frame context to enable spatial selection and set a useful maximum draw distance.

```java
instance.renderOptions().maxRenderDistance(256.0);
```

For streamed worlds, partition content into resource assets and load/invalidate them using the
existing model manager. Load limits can be increased for a deliberately provisioned scene.

```java
GltfLoadLimits limits = new GltfLoadLimits(
    1024L * 1024 * 1024,
    1024L * 1024 * 1024,
    1_000_000
);
GltfAsset asset = GltfLoader.load(modelId, resolver, limits);
```

Defaults remain 256 MiB combined input, 256 MiB decoded attribute/animation arrays, and 100,000
nodes. Expanded instance nodes count toward the node budget. Encoded textures, runtime object
overhead, derived spatial data, and GPU images consume additional memory.

## Node placement and overrides

Set a node's current model-space transform directly.

```java
float[] placement = new Matrix4f().translation(8, 2, -4).get(new float[16]);
instance.setNodeModelMatrix(nodeIndex, placement);
float[] resolved = instance.nodeModelMatrix(nodeIndex);
```

The setter derives the corresponding parent-local override. Future parent motion carries the
node with it. A singular parent transform fails before changing the target.

Clear individual overrides to return that component to animation/authored control.

```java
node.clearTranslationOverride();
node.clearRotationOverride();
node.clearScaleOverride();
```

Node/primitive state is lazy. Name lookup creates state for its matching nodes. Calling
`allPrimitives()` or taking a complete snapshot intentionally materializes the requested state.

The historical `animations().pose()` still exposes writable arrays. Once that pose escapes,
the renderer checks those arrays and uses conservative scene traversal. Use node override APIs
for frequent control of large scenes.

## Mesh instancing

Rigid `EXT_mesh_gpu_instancing` imports preserve the source node's transform and existing children.
Each extension instance becomes an appended child named `@instance_0`, `@instance_1`, and so on,
with a shared immutable mesh. Original node indices remain stable. Select generated nodes through
the source node's children or hierarchy path.

Translation, rotation, scale, accessor counts, normalized signed rotation formats, and expanded
node limits are validated. Application-specific attributes beginning with an underscore are
accepted. Their application meaning is owned by the exporter/integrator.

This release implements the extension through shared mesh occurrences. Draw submission follows
the normal resident or buffered rendering path. Skinned and morph-target meshes in this extension
currently fail validation.

## Native LabPBR 1.3

Supply packed normal/specular texture indices through a material or a runtime override.

```java
GltfMaterial lab = source.withLabPbr(GltfLabPbr.packed(normalTextureIndex, specularTextureIndex));

GltfMaterialOverride override = GltfMaterialOverride.builder()
    .labPbr(new GltfLabPbr(normalTextureIndex, specularTextureIndex, 231, 112, 190, 172))
    .build();
instance.nodes().primitive(nodeIndex, meshIndex, primitiveIndex).materialOverride(override);
```

The first six values are packed normal texture, packed specular texture, reflectance/metal byte,
porosity-or-SSS byte, height byte, and emission byte. A value of -1 inherits the packed or generated
channel. The ten-argument constructor additionally accepts smoothness, normal X, normal Y, and AO.
`labPbrChannels(patch)` overlays only specified fields; `labPbr(value)` replaces the whole descriptor.
Packed maps share the material's base-color texture coordinates and transform.

Export the same values in a glTF material's custom extras.

```json
{
  "extras": {
    "gfbsLabPbr": {
      "normalTexture": 1,
      "specularTexture": 2,
      "reflectance": 231,
      "porosityOrSubsurface": 112,
      "height": 190,
      "emission": 172
    }
  }
}
```

Native map pixels preserve the LabPBR channel layout.

These channels integrate with entity rendering and static Forge block/item models. Embedded static
materials automatically generate atlas sprites and `_n`/`_s` companions. Explicit Forge texture
slots retain priority. Static `pbr_bake` accepts `page_size`, `chart_size`, and `max_pixels`.

| Texture | R | G | B | A |
| --- | --- | --- | --- | --- |
| Normal | Tangent normal X | Tangent normal Y in DirectX convention | Material AO | Height |
| Specular | Perceptual smoothness | Linear dielectric F0 or metal ID | Porosity or SSS | Emission |

Specular sampling uses nearest filtering. Its mip generation preserves dielectric/metal,
porosity/SSS, and emission-sentinel categories. Non-square mip chains keep each dimension at least
one pixel. Iris owns the companions returned to each PBR holder, and creates fresh holders during
resource or shader reload.

GFBS supplies channel data. Enable the shader pack's LabPBR profile or an appropriate resource-pack
`optifine/texture.properties` format. Porosity, SSS, POM, AO, hardcoded metals, and emission
depend on the active shader pack's implementations. [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard)
and [implementation requirements](https://shaderlabs.org/wiki/LabPBR_Implementation_Requirements).

## Converting standard glTF materials

Standard metallic-roughness materials retain their glTF representation and numerical overrides.
The Iris bridge generates LabPBR companions when native packed maps are absent.

- glTF perceptual roughness maps to perceptual smoothness through `1 - roughness`.
- Dielectric F0 defaults to 0.04. The metallic endpoint uses LabPBR metal ID 255 and albedo F0.
  Intermediate metalness receives an approximation at the dielectric/metal boundary.
- Normal XYZ is scaled and normalized, then converted to the LabPBR XY convention. AO occupies
  normal B. Default height is 255.
- Explicit LabPBR emission occupies specular A. Standard glTF emissive colors retain their separate
  emissive pass, which supports independent emissive color and texture coordinates.

Independent UV sets, transforms and repeating domains are resampled into shared triangle charts,
preserving the normal UV orientation. Runtime `pbrBakeOptions(GltfPbrBakeOptions)` controls page,
chart and pixel budgets; finite chart resolution imposes a texture-quality limit. Native maps
authored in a shared domain avoid that resampling. PBR, UNLIT and NEON all retain companions.
`labPbrEmission(true)` moves representable glTF emission into specular A; independent hue or HDR
emission retains its separate pass. Shader packs still determine which effects are implemented.
glTF roughness is already perceptual. [glTF material model](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html#appendix-b-brdf-implementation).

## Shadows and render hooks

Pass the entity/block-entity dispatcher's buffer source to `GltfRenderer.render`. This lets
Iris/Oculus provide its phase, IDs, tangent data, and shadow shader.

Entity or block-entity integrations using the dispatcher's shadow pose stack can opt into the
pack's own entity-shadow frustum.

```java
instance.renderOptions().shadowFrustumCulling(true);
```

The optional adapter falls back to retaining candidates when its runtime contract is unavailable.
Camera draw distance stays independent of shadow visibility. Pack frustum subclasses keep their
public visibility overrides, including voxelization policies.

An integration registered only at a camera world-render event must also arrange a shadow-stage
callback if it needs model shadows. The shader pack controls its entity/BER shadow switches,
distance, and optional effects. [Iris shadow program](https://shaders.properties/current/reference/programs/shadow/).

## Compatibility limits

Required unsupported glTF extensions fail with diagnostics. Optional material extensions beyond
the supported core and listed extensions retain their core-material fallback. Draco/meshopt
decoding, KTX2/Basis transcoding, GPU skinning, and automatic geometric LOD remain future work.

Blended parts sort by primitive distance. Intersecting transparent surfaces and triangles inside a
single blended primitive still depend on authored topology and the shader pipeline. MASK coverage
is baked from texture alpha and material factor. Its interaction with interpolated vertex alpha
and filtering is constrained by Minecraft's entity shader cutoff.

CPU skinning/morph deformation uses conservative bounds. Rigid scene sections apply to large
opaque/masked triangle primitives. MobileGlues's existing integer attribute entry point is retained,
while the 1.6 runtime verification described here used desktop OpenGL.
