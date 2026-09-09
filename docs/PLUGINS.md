# GFBS: glTF plugin development

GFBS: glTF 1.5 provides a dependency-aware plugin host and typed extension registry. A plugin is a
logical component, not a JAR boundary: a normal Forge mod may publish one plugin or several plugins
from the same JAR.

## Packaging

A standalone plugin is an ordinary Forge 1.20.1 mod. Declare GFBS: glTF as a mandatory dependency:

```toml
[[dependencies.example_neon]]
modId = "gfbs_gltf"
mandatory = true
versionRange = "[1.5.0,2.0.0)"
ordering = "AFTER"
side = "CLIENT"
```

Plugin IDs are `ResourceLocation` values. Their namespace must equal the owning Forge mod ID. Use
different paths when one mod contains several plugins, for example `example_fx:neon` and
`example_fx:debug_overlay`.

## Direct registration

Register in the owning mod constructor or during common setup. Registration closes at Forge's
load-complete phase.

```java
@Mod(ExampleFxMod.ID)
public final class ExampleFxMod {
    public static final String ID = "example_fx";

    public ExampleFxMod() {
        GltfPlugins.register(ID, new NeonPlugin());
        GltfPlugins.register(ID, new DebugOverlayPlugin());
    }
}
```

## IMC registration

IMC avoids making registration order depend on mod construction order. Send either one
`GltfPlugin` or an `Iterable<GltfPlugin>` under `GltfPlugins.REGISTER_IMC_METHOD` during
`InterModEnqueueEvent`:

```java
InterModComms.sendTo(
    GFBSglTF.MODID,
    GltfPlugins.REGISTER_IMC_METHOD,
    () -> List.of(new NeonPlugin(), new DebugOverlayPlugin())
);
```

The sender mod ID becomes the owner. A payload cannot register IDs in another mod's namespace.

## Metadata and dependencies

```java
public final class NeonPlugin implements GltfPlugin {
    private static final GltfPluginMetadata METADATA = GltfPluginMetadata.builder(
            ResourceLocation.fromNamespaceAndPath("example_fx", "neon"), "1.2.0"
        )
        .displayName("Example Neon")
        .description("Adds a glow-mask pass and bloom compositor")
        .requires(ResourceLocation.fromNamespaceAndPath(
            "example_fx", "render_core"
        ), "[1.1.0,2.0.0)")
        .optional(ResourceLocation.fromNamespaceAndPath(
            "compat_mod", "oculus_bridge"
        ), "[1.0.0,)")
        .build();

    @Override
    public GltfPluginMetadata metadata() {
        return METADATA;
    }

    @Override
    public void onLoad(GltfPluginContext context) {
        // Register extensions here.
    }
}
```

Version ranges use Forge's Maven version-range syntax. Required dependencies must exist, match the
declared range, and load successfully. An optional dependency adds ordering when a compatible
plugin exists; absence or an incompatible version does not block the consumer. The host performs a
stable topological sort. Missing requirements, incompatible required versions, and dependency
cycles produce `BLOCKED` plugins instead of crashing unrelated plugins.

Lifecycle order is:

1. `onLoad`: dependencies are active; register extensions and initialize state.
2. `onReady`: all loadable plugins have run `onLoad`.
3. `onUnload`: rollback after failure, explicit disable, dependent cascade, or host shutdown.

Every registration made through `GltfPluginContext` is owned by that plugin and removed in reverse
order during rollback. `GltfPlugins.plugins()` exposes immutable diagnostic snapshots.

## Model importers and asset processors

Use the scoped importer helper so a failed plugin cannot leave a half-registered importer behind:

```java
context.registerImporter(new MyModelImporter());
```

Asset processors run after the selected importer and before the asset enters the client cache:

```java
context.register(GltfExtensionPoints.ASSET_PROCESSORS, (asset, processing) -> {
    validateProjectRules(asset);
    return asset; // A processor may return a new immutable GltfAsset.
}, 100);
```

Extension order is ascending, then dependency load order, then registration order. Model loading is
asynchronous, so processors must be thread-safe and must not call client rendering APIs.

## Custom extension points

Plugins may define cooperation contracts without asking GFBS: glTF to add a central registry:

```java
public static final GltfExtensionPoint<GlowProfileProvider> GLOW_PROFILES =
    GltfExtensionPoint.multiple(
        ResourceLocation.fromNamespaceAndPath("example_fx", "glow_profiles"),
        GlowProfileProvider.class
    );

context.register(GLOW_PROFILES, provider, 0);
List<GltfExtensionEntry<GlowProfileProvider>> providers = context.extensions(GLOW_PROFILES);
```

An extension-point ID is global. Reusing an ID with another Java type or multiplicity is rejected.
Use `GltfExtensionPoint.single` for exclusive services and `multiple` for pipelines or listeners.

## Non-invasive render passes

Register client render extensions only on the physical client. Keep the common plugin entry point
free of eager client-class references when supporting dedicated servers.

```java
context.register(ClientGltfExtensionPoints.RENDER_EXTENSIONS, (stage, frame) -> {
    if (stage != GltfRenderStage.AFTER_EMISSIVE_PASS || frame.shadowPass()) return;

    frame.draw(new GltfCustomRenderPass() {
        private final ResourceLocation id =
            ResourceLocation.fromNamespaceAndPath("example_fx", "neon_mask");

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public GltfRenderLayer layer(GltfPrimitiveRenderContext primitive) {
            if (primitive.material().emissiveStrength() <= 0.0f) return null;

            RenderType maskType = NeonRenderTypes.mask(
                primitive.emissiveTexture(), primitive.cull()
            );
            return GltfRenderLayer.builder(maskType)
                .vertices(GltfVertexSource.EMISSIVE)
                .fullBright()
                .residentGeometry(true)
                .build();
        }
    });
    frame.flush();
    compositeGlowMask();
});
```

The custom pass runs through GFBS: glTF's existing node visibility, material overrides, part filter,
frustum/occlusion culling, skinning, morphing, and GPU-resident rigid-geometry path. Its RenderType
must use `DefaultVertexFormat.NEW_ENTITY` and a compatible triangle/line mode. A glow plugin can
write this pass into its own mask target, call `frame.flush()`, and composite bloom in
the same hook or `AFTER_RENDER`; no modification of `EntityGltfRenderer` or
`GltfGeometryPipeline` is required. `flush()` returns `false` for a custom `MultiBufferSource`
which does not expose Forge's normal `BufferSource` flush operation.

During `BEFORE_BUILTIN_PASSES`, an extension may call `frame.suppress(BASE)`, `EMISSIVE`, or
`SHADOW` and draw a replacement. Render and reload extensions that throw are logged and quarantined
for the rest of the session so one faulty plugin does not repeatedly break every model render.

## Resource reload

Release and rebuild plugin-owned client resources with the client reload point:

```java
context.register(
    ClientGltfExtensionPoints.RESOURCE_RELOAD_LISTENERS,
    new GltfClientResourceReloadListener() {
        @Override
        public void beforeGltfResourcesReload(ResourceManager resources) {
            closeFramebuffersAndShaders();
        }

        @Override
        public void afterGltfResourcesReload(ResourceManager resources) {
            markResourcesForLazyRebuild();
        }
    }
);
```

The before hook runs before GFBS cached assets and GPU resources are invalidated; the after hook
runs once invalidation has been scheduled.

## Compatibility rules

- Common plugin classes must remain safe on a dedicated server.
- Render callbacks execute on the render thread and must not block.
- Asset processors execute on the model-loading worker and must be thread-safe.
- A plugin must not retain `GltfRenderFrame` beyond its callback.
- `baseTransform()` and `modelMatrix()` return defensive copies.
- Use scoped context registration so rollback and runtime disable remain complete.
