package org.lytharalab.gfbs.gltf.api.animation;

import java.util.Objects;

public record AnimationChannel(int node, AnimationPath path, AnimationSampler sampler) {
    public AnimationChannel {
        if (node < 0) throw new IllegalArgumentException("Animation channel node index must be non-negative");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sampler, "sampler");
        int expected = path.components();
        if (expected > 0 && sampler.components() != expected) {
            throw new IllegalArgumentException(path + " animation channels require " + expected + " components");
        }
    }
}
