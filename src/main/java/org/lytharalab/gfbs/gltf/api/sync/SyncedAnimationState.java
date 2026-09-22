package org.lytharalab.gfbs.gltf.api.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;

import java.util.Objects;

/**
 * Authoritative, tick-free state of one synchronized animation target.
 *
 * <p>Every time value lives on the server's monotonic timeline in seconds and is stored as
 * {@code double}. Earlier versions expressed this record in game ticks and returned {@code float},
 * which quantized multi-day logical timestamps into a 62.5 ms staircase and made the client phase
 * controller fight a staircase instead of the network.</p>
 */
public record SyncedAnimationState(AnimationTargetKey target, String animation,
                                   double startSeconds, double initialSeconds,
                                   float speed, LoopMode loopMode, double transitionSeconds,
                                   boolean playing, boolean stopped, long sequence) {
    /**
     * Upper bound of a meaningful logical timestamp. Reaching it means the state itself is corrupt:
     * even a million years of runtime stays far below it while double keeps sub-microsecond detail.
     */
    public static final double MAX_LOGICAL_SECONDS = 1.0e15d;

    public SyncedAnimationState {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(loopMode, "loopMode");
        if (animation == null || animation.length() > 256 || (!stopped && animation.isBlank())) {
            throw new IllegalArgumentException("Invalid animation name");
        }
        if (!Double.isFinite(startSeconds) || Math.abs(startSeconds) > MAX_LOGICAL_SECONDS) {
            throw new IllegalArgumentException("Invalid start time");
        }
        if (!Double.isFinite(initialSeconds) || Math.abs(initialSeconds) > MAX_LOGICAL_SECONDS
            || !Float.isFinite(speed) || speed == 0.0f
            || !Double.isFinite(transitionSeconds) || transitionSeconds < 0.0d) {
            throw new IllegalArgumentException("Invalid playback values");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("Sequence must be non-negative");
        }
        if (stopped && playing) {
            throw new IllegalArgumentException("A stopped animation cannot be playing");
        }
    }

    /**
     * Evaluates the authoritative timeline at a server time expressed in monotonic seconds.
     *
     * <p>The result stays {@code double} end to end, so the resolution never degrades with the age of
     * the world and world-time resets cannot shift the timeline.</p>
     */
    public double timeAt(double serverSeconds) {
        if (!Double.isFinite(serverSeconds)) {
            throw new IllegalArgumentException("Server time must be finite");
        }
        if (!playing || stopped) {
            return initialSeconds;
        }
        double value = initialSeconds + (serverSeconds - startSeconds) * (double) speed;
        if (!Double.isFinite(value) || Math.abs(value) > MAX_LOGICAL_SECONDS) {
            throw new IllegalStateException("Synchronized animation time overflow");
        }
        return value;
    }

    /** Remaining blend duration at a server time expressed in monotonic seconds. */
    public double remainingTransitionAt(double serverSeconds) {
        if (!Double.isFinite(serverSeconds)) {
            throw new IllegalArgumentException("Server time must be finite");
        }
        double elapsedSeconds = Math.max(0.0d, serverSeconds - startSeconds);
        return Math.max(0.0d, transitionSeconds - elapsedSeconds);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", target.dimension().toString());
        tag.putString("kind", target.kind().name());
        tag.putString("target", target.id());
        tag.putString("animation", animation);
        tag.putDouble("startSeconds", startSeconds);
        tag.putDouble("initialSeconds", initialSeconds);
        tag.putFloat("speed", speed);
        tag.putString("loopMode", loopMode.name());
        tag.putDouble("transitionSeconds", transitionSeconds);
        tag.putBoolean("playing", playing);
        tag.putBoolean("stopped", stopped);
        tag.putLong("sequence", sequence);
        return tag;
    }

    public static SyncedAnimationState load(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        try {
            AnimationTargetKey target = new AnimationTargetKey(
                ResourceLocation.parse(tag.getString("dimension")),
                AnimationTargetKey.Kind.valueOf(tag.getString("kind")),
                tag.getString("target")
            );
            return new SyncedAnimationState(
                target,
                tag.getString("animation"),
                readSeconds(tag, "startSeconds", "startTick", 20.0d),
                readSeconds(tag, "initialSeconds", null, 1.0d),
                tag.getFloat("speed"),
                LoopMode.valueOf(tag.getString("loopMode")),
                readSeconds(tag, "transitionSeconds", null, 1.0d),
                tag.getBoolean("playing"),
                tag.getBoolean("stopped"),
                tag.getLong("sequence")
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid synchronized animation state NBT", exception);
        }
    }

    /**
     * Reads a seconds value, falling back to a legacy key. Legacy tick values are divided by
     * {@code legacyScale}; legacy 1.5.0 floats under the same key are widened by {@code getDouble}.
     */
    private static double readSeconds(CompoundTag tag, String key, String legacyKey, double legacyScale) {
        if (tag.contains(key)) {
            return tag.getDouble(key);
        }
        if (legacyKey != null && tag.contains(legacyKey)) {
            return tag.getDouble(legacyKey) / legacyScale;
        }
        return 0.0d;
    }
}
