package org.lytharalab.gfbs.gltf.api.model;

import java.util.Objects;

public record GltfScene(String name, int[] roots) {
    public GltfScene {
        name = name == null ? "" : name;
        roots = Objects.requireNonNull(roots, "roots").clone();
    }

    @Override
    public int[] roots() { return roots.clone(); }
}
