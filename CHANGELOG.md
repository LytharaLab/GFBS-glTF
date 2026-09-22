# Changelog

## 1.5.1

### Fixed

- **Synchronized animations no longer stutter.** The whole time axis moved from `float` to `double`:
  `SyncedAnimationState.timeAt`/`remainingTransitionAt`, `PlaybackOptions`, the `AnimationController`
  playhead and seek API, and `GltfInstance.update`. At `1e6` seconds of logical time a `float`
  timestamp lands on a 62.5 ms grid, which the client phase controller fought every 62.5 ms; the
  staircase is now ~8 orders of magnitude smaller than the controller's 15 ms dead zone.
- The client seek path (`applyState`, `recoverSmoothly`) seeks with the exact `double` target instead
  of a `float` truncation.
- Legacy 1.5.0 saves and states still load; `startTick` NBT keys are converted to seconds.

### Changed

- **The animation timeline left the tick domain.** States, packets and the client estimator now carry
  monotonic **seconds** instead of game ticks, so `Level#getGameTime()` never influences an animation
  again: world-time resets, `/time set`, TPS drops and tick freezes cannot move, slow down or reset a
  running animation.
- **New mod-owned lifecycle replaces the tick-event hooks.** `RenderLevelStageEvent.Stage.AFTER_ENTITIES`
  drives `ClientHeartbeat`, which owns a pause-aware `SessionClock` (`core.time.SessionClock`) and a
  frame-rate independent `FixedStepScheduler` (`core.time.FixedStepScheduler`). The controller runs on
  fixed 20 Hz logical steps derived from real frame time; a stalled or minimized frame drops its debt
  instead of replaying it. No `TickEvent` is registered any more.
- `ServerClock` (`network.ServerClock`) is the authoritative server timeline: one monotonic clock per
  server, created lazily and dropped on `ServerStoppedEvent`.
- `ServerTimeEstimator` replaces `ServerTickClock`: with both sides on `nanoTime` the clock only needs
  an offset and a filtered RTT, so the periodic tick-rate re-learning (and the speed nudges it caused)
  is gone.
- The client no longer scales playback speed by estimated TPS; the timeline is real time on both ends.
- Network protocol version `2` → `3`; clients and servers must both run 1.5.1.
- `ClientAnimationSync.tick()` is gone: the heartbeat calls `ClientAnimationSync.step(...)`.
- The temporary ASM stopgap `GltfPrecisionPatcher.java` is retired and deleted; the fix now lives in
  the sources, so no post-build bytecode surgery is needed.

### Migration

- `SyncedAnimationState` is now `(target, animation, startSeconds, initialSeconds, speed, loopMode,
  transitionSeconds, playing, stopped, sequence)` with `double` timestamps and no tick overload.
- `SyncedGltfAnimations.estimatedServerTick()`/`estimatedServerTicksPerSecond()` are replaced by
  `estimatedServerSeconds()`; `logicalSeconds()` and `heartbeatSteps()` were added.
- `PlaybackOptions` takes `double transitionSeconds` and `double initialTime`; pass floats as before,
  they widen.
- See [1.5.1 migration guide](docs/MIGRATING-1.5.1.md).

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
