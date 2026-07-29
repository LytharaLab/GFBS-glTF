package org.lytharalab.gfbs.gltf.api.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;

import java.util.Objects;

public record SyncedAnimationState(AnimationTargetKey target, String animation,
                                   long serverStartTick, float initialSeconds,
                                   float speed, LoopMode loopMode, float transitionSeconds,
                                   boolean playing, boolean stopped, long sequence) {
    public SyncedAnimationState {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(loopMode, "loopMode");
        if (animation == null || animation.length() > 256 || (!stopped && animation.isBlank())) {
            throw new IllegalArgumentException("Invalid animation name");
        }
        if (!Float.isFinite(initialSeconds) || !Float.isFinite(speed) || speed == 0
            || !Float.isFinite(transitionSeconds) || transitionSeconds < 0) {
            throw new IllegalArgumentException("Invalid playback values");
        }
        if (sequence < 0) throw new IllegalArgumentException("Sequence must be non-negative");
        if (stopped && playing) throw new IllegalArgumentException("A stopped animation cannot be playing");
    }

    public float timeAt(long serverTick) {
        if (!playing || stopped) return initialSeconds;
        double elapsedSeconds = ((double) serverTick - (double) serverStartTick) / 20.0d;
        double value = (double) initialSeconds + elapsedSeconds * speed;
        if (!Double.isFinite(value) || value > Float.MAX_VALUE || value < -Float.MAX_VALUE) {
            throw new IllegalStateException("Synchronized animation time overflow");
        }
        return (float) value;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", target.dimension().toString());
        tag.putString("kind", target.kind().name());
        tag.putString("target", target.id());
        tag.putString("animation", animation);
        tag.putLong("startTick", serverStartTick);
        tag.putFloat("initialSeconds", initialSeconds);
        tag.putFloat("speed", speed);
        tag.putString("loopMode", loopMode.name());
        tag.putFloat("transitionSeconds", transitionSeconds);
        tag.putBoolean("playing", playing);
        tag.putBoolean("stopped", stopped);
        tag.putLong("sequence", sequence);
        return tag;
    }

    public static SyncedAnimationState load(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        try {
            AnimationTargetKey target = new AnimationTargetKey(ResourceLocation.parse(tag.getString("dimension")),
                AnimationTargetKey.Kind.valueOf(tag.getString("kind")), tag.getString("target"));
            return new SyncedAnimationState(target, tag.getString("animation"), tag.getLong("startTick"),
                tag.getFloat("initialSeconds"), tag.getFloat("speed"), LoopMode.valueOf(tag.getString("loopMode")),
                tag.getFloat("transitionSeconds"), tag.getBoolean("playing"), tag.getBoolean("stopped"),
                tag.getLong("sequence"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid synchronized animation state NBT", exception);
        }
    }
}
