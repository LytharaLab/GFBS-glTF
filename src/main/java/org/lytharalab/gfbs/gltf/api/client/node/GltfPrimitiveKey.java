package org.lytharalab.gfbs.gltf.api.client.node;

/** Identifies one primitive occurrence in one model instance. */
public record GltfPrimitiveKey(int nodeIndex, int meshIndex, int primitiveIndex) {
    public GltfPrimitiveKey {
        if (nodeIndex < 0 || meshIndex < 0 || primitiveIndex < 0) {
            throw new IllegalArgumentException("Primitive key indices must be non-negative");
        }
    }
}
