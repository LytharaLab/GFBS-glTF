package org.lytharalab.gfbs.gltf.api.model;

/**
 * Internal zero-copy bridge for GFBS:glTF's hot render paths.
 *
 * <p>The returned arrays are the immutable asset's backing storage. They are exposed only so the
 * renderer can avoid cloning complete vertex streams every frame. Treat every returned array as
 * strictly read-only. Normal integrations should keep using {@link GltfPrimitive}'s defensive-copy
 * accessors.</p>
 */
public final class GltfPrimitiveAccess {
    private GltfPrimitiveAccess() {}

    public static float[] positions(GltfPrimitive primitive) { return primitive.positionsView(); }
    public static float[] normals(GltfPrimitive primitive) { return primitive.normalsView(); }
    public static float[] tangents(GltfPrimitive primitive) { return primitive.tangentsView(); }
    public static float[] texCoords0(GltfPrimitive primitive) { return primitive.texCoords0View(); }
    public static float[] texCoords1(GltfPrimitive primitive) { return primitive.texCoords1View(); }
    public static float[] colors(GltfPrimitive primitive) { return primitive.colorsView(); }
    public static int[] joints(GltfPrimitive primitive) { return primitive.jointsView(); }
    public static float[] weights(GltfPrimitive primitive) { return primitive.weightsView(); }
    public static int[] indices(GltfPrimitive primitive) { return primitive.indicesView(); }
}
