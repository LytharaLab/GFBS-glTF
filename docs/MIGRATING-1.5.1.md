# Migrating to GFBS: glTF 1.5.1

Version 1.5.1 is a compatibility release for the animation path. It fixes the synchronized-animation
stutter at its source and removes the Minecraft tick loop from the synchronization design. Most
integrations compile unchanged; the items below are the complete list of source-visible differences.

## Why 1.5.1 exists

1.5.0 returned the authoritative clip time as a `float`. A `float` step is relative to magnitude, so
after a few days of logical animation time the timeline was quantized into 62.5 ms staircases. The
client controller has a 15 ms dead zone, so it could never settle and kept fighting the staircase
with speed corrections — visible as stuttering while a long-running clip played. Resetting the world
time or recreating the animation state made it disappear, which is why the symptom looked random.

The timeline is now monotonic seconds in `double`, and the control loop is driven by a mod-owned
heartbeat instead of `TickEvent`.

## Required changes

| 1.5.0 | 1.5.1 |
| --- | --- |
| `SyncedAnimationState(..., long serverStartTick, float initialSeconds, ...)` | `SyncedAnimationState(..., double startSeconds, double initialSeconds, ...)` — the record component `serverStartTick()` is renamed `startSeconds()` and measured in monotonic seconds |
| `state.timeAt(long serverTick)` | removed; use `state.timeAt(double serverSeconds)` |
| `state.remainingTransitionAt(...)` returning `float` | returns `double` |
| `PlaybackOptions(float speed, LoopMode, float transition, float initialTime)` | `PlaybackOptions(float speed, LoopMode, double transition, double initialTime)` — existing float call sites still compile |
| `animations.seek(float)` / `seekLayer(String, float)` | `seek(double)` / `seekLayer(String, double)` |
| `animations.time()` returning `float` | returns `double` (exact playhead) |
| `instance.update(float deltaSeconds)` | `update(double deltaSeconds)`; float call sites still compile |
| `SyncedGltfAnimations.estimatedServerTick()` | `estimatedServerSeconds()` |
| `SyncedGltfAnimations.estimatedServerTicksPerSecond()` | removed — the timeline is real time, not TPS-scaled |
| `ClientAnimationSync.tick()` | `ClientAnimationSync.step(long logicalNanos)`, driven by `ClientHeartbeat` |
| `ServerTickClock` | `ServerTimeEstimator` (offset-only, seconds) |
| network protocol version `2` | `3` — client and server must both run 1.5.1 |

## Host-mod checklist

- Keep calling `instance.update(deltaSeconds)` from your renderer. Pass real frame seconds; the value
  is now a `double`, and casting to `float` before the call throws the precision away again.
- Do not restart or seek the synchronized base layer yourself. The synchronized target still owns it.
- If you displayed TPS-based diagnostics, switch to `estimatedServerSeconds()` and
  `estimatedRoundTripMillis()`.
- Server commands are unchanged: `ServerAnimations.play/pause/resume/stop`. The `transitionSeconds`
  parameter widened to `double`, so existing float call sites keep compiling.
- Persisted state written by 1.5.0 still loads: legacy `startTick` NBT keys are converted to seconds
  (`ticks / 20`), and legacy float values under the same keys are widened.

## Behavioral notes

- **World time no longer affects animations.** `/time set`, world-time resets, TPS drops and tick
  freezes cannot move, slow down or reset a running clip, because no code on the synchronization path
  reads `Level#getGameTime()`.
- **TPS no longer scales playback.** 1.5.0 estimated the server's tick rate and stretched client
  playback to match it; 1.5.1 runs the timeline at real time on both ends.
- **Single-player pause freezes the timeline.** `ClientHeartbeat` uses a pause-aware `SessionClock`,
  so a paused world resumes exactly where it stopped. Opening the menu on a dedicated server
  (`isPaused() == false`) does not freeze anything, which matches the previous behavior.
- **A stalled frame drops its catch-up debt.** The fixed-step scheduler never replays a long stall;
  the controller re-aligns with a blended rebase instead, protected by the existing cooldown.
- **The ASM stopgap is gone.** `GltfPrecisionPatcher.java` was a bytecode patch that rewrote
  `timeAt` from `(D)F` to `(D)D` after the build. The fix now lives in the sources, so the patcher is
  retired and deleted. Do not apply it to a 1.5.1 jar.
