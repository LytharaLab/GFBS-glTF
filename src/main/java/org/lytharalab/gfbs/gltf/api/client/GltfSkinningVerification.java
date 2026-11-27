package org.lytharalab.gfbs.gltf.api.client;

/** CPU/GPU comparison of sampled, mesh-local positions and normals before draw transforms. */
public record GltfSkinningVerification(int sampledVertices, int mismatchedVertices,
                                       float maxPositionError, float maxNormalError,
                                       int firstMismatchedVertex) {
    public static final int MAX_SAMPLES = 4_096;

    /** Absolute part of the per-component position tolerance. */
    public static final float POSITION_ABSOLUTE_TOLERANCE = 2.0e-5f;
    /** Additional allowance scaled by the absolute value of the CPU position component. */
    public static final float POSITION_RELATIVE_TOLERANCE = 2.0e-5f;
    public static final float NORMAL_ABSOLUTE_TOLERANCE = 2.0e-5f;

    /** Returns true when all sampled components agree within the declared tolerances. */
    public boolean matches() { return mismatchedVertices == 0; }
}
