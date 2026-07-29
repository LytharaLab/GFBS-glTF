package org.lytharalab.gfbs.gltf.api.animation;

import java.util.List;
import java.util.Objects;

public final class AnimationClip {
    private final String name;
    private final List<AnimationChannel> channels;
    private final float duration;

    public AnimationClip(String name, List<AnimationChannel> channels) {
        this.name = name == null ? "" : name;
        Objects.requireNonNull(channels, "channels");
        this.channels = List.copyOf(channels);
        if (this.channels.isEmpty()) throw new IllegalArgumentException("A glTF animation must contain at least one channel");
        this.duration = channels.stream().map(AnimationChannel::sampler)
            .map(AnimationSampler::endTime).max(Float::compare).orElse(0.0f);
    }

    public String name() { return name; }
    public List<AnimationChannel> channels() { return channels; }
    public float duration() { return duration; }
}
