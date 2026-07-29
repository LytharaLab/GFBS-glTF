package org.lytharalab.gfbs.gltf.api.animation;

import java.util.Objects;

/** User-defined marker fired when playback crosses its time. */
public record AnimationEvent(String name, float time) {
    public AnimationEvent {
        name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Animation event name is blank");
        if (!Float.isFinite(time) || time < 0.0f) throw new IllegalArgumentException("Invalid animation event time");
    }
}
