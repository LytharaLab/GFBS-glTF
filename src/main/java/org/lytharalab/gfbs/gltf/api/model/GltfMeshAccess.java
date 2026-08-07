package org.lytharalab.gfbs.gltf.api.model;

/** Internal read-only zero-copy access for realtime mesh data. */
public final class GltfMeshAccess {
    private GltfMeshAccess() {}

    public static float[] defaultMorphWeights(GltfMesh mesh) {
        return mesh.defaultMorphWeightsView();
    }
}
