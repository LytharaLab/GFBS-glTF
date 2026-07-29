# Contributing to GFBS: glTF

Thank you for considering a contribution to GFBS: glTF. This guide applies to the [official repository](https://github.com/LytharaLab/GFBS-glTF) and covers bug reports, feature proposals, code changes, documentation, and tests.

By participating in this project, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Before you begin

- Search the [existing issues](https://github.com/LytharaLab/GFBS-glTF/issues) and [pull requests](https://github.com/LytharaLab/GFBS-glTF/pulls) before opening a duplicate.
- Keep each issue and pull request focused on one problem or feature.
- Discuss breaking API changes, new model formats, rendering architecture changes, protocol changes, or large collision changes before implementing them.
- Do not include credentials, access tokens, private server addresses, personal file paths, private models, or unrelated internal project material in issues, logs, commits, or pull requests.
- Preserve source attribution and third-party license notices when adapting external code.

## Development environment

The project currently targets:

- Minecraft `1.20.1`
- Minecraft Forge `47.4.21`
- Java `17`
- Gradle through the included wrapper

Clone the official repository:

```bash
git clone https://github.com/LytharaLab/GFBS-glTF.git
cd GFBS-glTF
```

Import the repository as a Gradle project in your IDE. Use the included wrapper instead of a separately installed Gradle version.

Build the project:

```bash
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

Run the unit tests:

```bash
./gradlew test
```

Run the complete verification lifecycle:

```bash
./gradlew check
```

Rendering, resource reload, shader-pack compatibility, networking, and collision integration must also be tested in a development client or dedicated server when relevant.

## Reporting bugs

Open bug reports in the [issue tracker](https://github.com/LytharaLab/GFBS-glTF/issues). A useful report should include:

- The GFBS: glTF version or commit.
- The Minecraft and Forge versions.
- Whether the problem occurs on the client, dedicated server, or both.
- The renderer and shader configuration, including whether Oculus, Iris, Embeddium, or a shader pack is installed.
- A minimal list of other mods required to reproduce the issue.
- The affected model format and a minimal model when redistribution is permitted.
- Clear reproduction steps.
- Expected and actual behavior.
- Relevant logs or stack traces with private information removed.
- Screenshots or a short capture for visual defects when useful.

Do not attach assets that you do not have permission to redistribute.

Do not report security vulnerabilities in a public issue when disclosure would put users at risk. Use a private repository security-reporting channel when one is available.

## Proposing features

Feature proposals should explain:

- The concrete integration or content-authoring use case.
- Why the current API cannot solve it cleanly.
- The expected Java-facing behavior.
- File-format, memory, rendering, threading, networking, and compatibility implications.
- A small API example when possible.
- Whether the feature belongs in the general runtime or in a dependent mod.

GFBS: glTF is a general model runtime. Project-specific entity behavior, gameplay logic, and content conventions normally belong in the consuming mod.

## Code standards

### Java and public API

- Use Java 17 language features only.
- Follow the existing four-space indentation and brace style.
- Keep public names explicit and stable.
- Avoid exposing implementation classes from `client`, `core`, `network`, or `collision` packages when a type belongs in the public `api` package.
- Validate arguments at public API, file-decoding, rendering, and network boundaries.
- Prefer immutable model data and defensive copies for arrays, buffers, collections, and matrices.
- Keep common code side-neutral. Client-only Minecraft classes must not load on a dedicated server.
- Use `ResourceLocation` for externally visible resource and target identifiers.
- Do not silently replace registered importers or swallow failures that developers need to diagnose.

### Model loading and importers

- Resolve model dependencies through `GltfResolver`; do not access arbitrary filesystem paths from model files.
- Reject absolute paths, namespace escapes, parent-directory traversal, and unsafe URI schemes.
- Apply explicit limits before allocating model, texture, animation, voxel, or index data.
- Convert imported formats into a validated immutable `GltfAsset`.
- Keep built-in glTF, GLB, and OBJ behavior compatible unless a documented breaking change is approved.
- Add tests for malformed files, boundary values, unsupported required extensions, and unsafe resource references.

### Rendering

- Preserve the vanilla entity-shader path when no shader pack is active.
- Do not introduce a mandatory Embeddium, Oculus, Iris, or shader-pack dependency.
- Keep `DefaultVertexFormat.NEW_ENTITY` compatibility for public RenderType integration unless the public contract is deliberately revised.
- Consider opaque, cutout, translucent, line, double-sided, emissive, skinned, and morphed geometry.
- Do not use static primitive bounds for animated geometry when they can produce incorrect culling.
- Release GPU resources on resource reload and model invalidation.
- Describe manual test scenes and shader configurations in the pull request.

### Animation and synchronization

- Keep animation state per `GltfInstance`.
- Preserve deterministic layer ordering and transition behavior.
- Validate clip names, layer names, masks, event times, playback values, and synchronized target identifiers.
- Treat the server as authoritative for synchronized animation commands.
- Consider reconnects, dimension changes, stale packets, late binding, unloaded targets, and dedicated-server class loading.
- Keep protocol changes backward-compatible within the declared API line whenever practical.

### Collision

- Collision must remain opt-in.
- Bound memory and box generation before voxelizing geometry.
- Keep expensive precise rebuilds away from latency-sensitive threads when the existing asynchronous path applies.
- Treat model-to-world transforms, node selection, morph targets, skinning, and animation pose as separate correctness concerns.
- Add focused tests for voxelization and manual tests for entity movement and ray clipping.

### Documentation

- Write repository-facing documentation in clear English.
- Document behavior that exists in the submitted code, not planned or private functionality.
- Keep examples compilable whenever practical.
- Use relative links for repository files and verify that every link resolves.
- Update `README.md` and `docs/1.0-API.md` when changing public behavior.
- Preserve the ModelLoader acknowledgement in repository-facing attribution sections.

## Tests

Bug fixes and behavior changes should include tests whenever the affected code can run outside Minecraft.

At minimum, a pull request should pass:

```bash
./gradlew test
./gradlew check
```

Relevant areas include:

- glTF, GLB, OBJ, accessor, and material validation.
- Animation sampling, mixing, masks, events, transitions, and pose copying.
- Immutable model data and defensive copying.
- Bounds calculations and transform behavior.
- Voxelization and collision limits.
- Importer registration and unsafe path rejection.

Manual in-game testing is expected for rendering, GPU lifecycle, shader-pack behavior, resource reloads, networking, entity collision injection, and ray clipping. Include the test setup and result in the pull request.

## Pull request process

1. Create a branch from the current default branch.
2. Make a focused change with clear commits.
3. Update tests and public documentation with the implementation.
4. Run the relevant Gradle tasks and manual tests.
5. Open a [pull request](https://github.com/LytharaLab/GFBS-glTF/pulls) with a concise title and complete description.
6. Explain the problem, chosen solution, compatibility impact, tests performed, and remaining limitations.
7. Address review comments with follow-up commits unless maintainers request a different history.

A pull request should not contain generated build output, IDE metadata, local run directories, logs, credentials, private assets, unrelated formatting changes, or bundled third-party binaries unless the repository explicitly requires them.

Maintainers may request changes, split an oversized pull request, or decline a contribution that conflicts with project scope, compatibility, security, attribution requirements, or maintenance capacity.

## Commit messages

Use short, descriptive commit messages in the imperative mood. Examples:

```text
Fix stale GPU models after resource reload
Add node masks to synchronized playback
Reject parent traversal in OBJ material paths
Document custom RenderType validation
```

## Licensing

By submitting a contribution, you agree that your contribution will be licensed under the repository's [MIT License](LICENSE).
