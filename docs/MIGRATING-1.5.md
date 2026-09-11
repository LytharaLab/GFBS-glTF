# Migrating to GFBS: glTF 1.5

Version 1.5.0 is source-compatible with the public 1.4 rendering, animation, node, synchronization,
importer, and collision APIs. Existing mods do not need to become plugins and direct
`ModelImporters.register` calls continue to work.

## Recommended changes for extension mods

- Move related registrations into a `GltfPlugin` implementation.
- Replace direct importer registration with `GltfPluginContext.registerImporter` to gain rollback.
- Declare inter-plugin requirements in `GltfPluginMetadata` instead of probing classes or mod load
  order manually.
- Replace mixins or renderer patches used for extra material passes with a
  `GltfRenderExtension` and `GltfCustomRenderPass`.
- Release shader, texture, and framebuffer state through the client resource-reload extension.

## Behavioral notes

- Plugin registration closes during Forge load-complete processing.
- Plugin IDs must use the owning mod ID as their namespace.
- Required dependency failure unloads active required dependents in reverse order.
- Optional dependencies affect order only when present and version-compatible.
- Extension order is deterministic: explicit order, plugin dependency order, then registration
  order.
- Custom render passes retain the streamed fallback for skinning, active morphs, translucent
  material geometry, or passes that disable resident geometry.
