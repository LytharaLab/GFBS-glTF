# Changelog

## 1.5.0

- Added the GFBS: glTF plugin host with multiple logical plugins per Forge mod.
- Added direct and Forge IMC registration with owner-namespace validation.
- Added Maven version-range dependencies, stable topological ordering, cycle detection, failure
  isolation, rollback, diagnostics, runtime disable, and required-dependent cascading.
- Added typed single-provider and multi-provider extension points which plugins may define.
- Added plugin-scoped model importer registration and ordered post-import asset processors.
- Added client resource-reload hooks with automatic failure quarantine.
- Added staged render extensions, built-in pass suppression, and arbitrary custom render passes.
- Custom passes reuse node/material state, skinning, morph targets, part filtering, culling, lighting,
  and resident GPU geometry.
- Preserved the 1.4 public API and non-plugin integration paths.
