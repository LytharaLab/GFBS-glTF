package org.lytharalab.gfbs.gltf.api.model;

/** Internal read-only zero-copy access for realtime morph evaluation. */
public final class MorphTargetAccess {
    private MorphTargetAccess() {}

    public static float[] positions(MorphTarget target) { return target.positionsView(); }
    public static float[] normals(MorphTarget target) { return target.normalsView(); }
    public static float[] tangents(MorphTarget target) { return target.tangentsView(); }
}
