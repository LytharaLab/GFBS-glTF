package org.lytharalab.gfbs.gltf.api.animation;

public record PlaybackOptions(float speed, LoopMode loopMode, float transitionSeconds, float initialTime) {
    public PlaybackOptions {
        if (!Float.isFinite(speed) || speed == 0.0f) throw new IllegalArgumentException("Speed must be finite and non-zero");
        if (!Float.isFinite(transitionSeconds) || transitionSeconds < 0) throw new IllegalArgumentException("Invalid transition time");
        if (!Float.isFinite(initialTime)) throw new IllegalArgumentException("Invalid initial time");
        if (loopMode == null) loopMode = LoopMode.ONCE;
    }

    public static PlaybackOptions once() { return new PlaybackOptions(1, LoopMode.ONCE, 0, 0); }
    public static PlaybackOptions loop() { return new PlaybackOptions(1, LoopMode.LOOP, 0, 0); }
}
