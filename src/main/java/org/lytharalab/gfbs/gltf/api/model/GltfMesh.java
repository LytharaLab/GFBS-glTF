package org.lytharalab.gfbs.gltf.api.model;

import java.util.List;
import java.util.Objects;

public record GltfMesh(String name, List<GltfPrimitive> primitives, float[] defaultMorphWeights) {
    public GltfMesh {
        name = name == null ? "" : name;
        primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
        if (primitives.isEmpty()) throw new IllegalArgumentException("A glTF mesh must contain at least one primitive");
        defaultMorphWeights = defaultMorphWeights == null ? null : defaultMorphWeights.clone();
        if (defaultMorphWeights != null) {
            for (float value : defaultMorphWeights) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Morph weight is not finite");
            }
        }
    }

    @Override
    public float[] defaultMorphWeights() {
        return defaultMorphWeights == null ? null : defaultMorphWeights.clone();
    }

    /** Package-private zero-copy view used by the renderer hot path. */
    float[] defaultMorphWeightsView() { return defaultMorphWeights; }
}
