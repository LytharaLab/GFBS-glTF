package org.lytharalab.gfbs.gltf.api.animation;

/**
 * Playback request for one animation track.
 *
 * <p>{@code transitionSeconds} and {@code initialTime} are {@code double}: synchronized playback
 * seeks the authoritative timeline directly, and a {@code float} start time quantized multi-day
 * logical timestamps into visible 62.5 ms staircases.</p>
 */
public record PlaybackOptions(float speed, LoopMode loopMode, double transitionSeconds, double initialTime) {
    public PlaybackOptions {
        if (!Float.isFinite(speed) || speed == 0.0f) throw new IllegalArgumentException("Speed must be finite and non-zero");
        if (!Double.isFinite(transitionSeconds) || transitionSeconds < 0.0d) throw new IllegalArgumentException("Invalid transition time");
        if (!Double.isFinite(initialTime)) throw new IllegalArgumentException("Invalid initial time");
        if (loopMode == null) loopMode = LoopMode.ONCE;
    }

    public static PlaybackOptions once() { return new PlaybackOptions(1, LoopMode.ONCE, 0.0d, 0.0d); }
    public static PlaybackOptions loop() { return new PlaybackOptions(1, LoopMode.LOOP, 0.0d, 0.0d); }
}
