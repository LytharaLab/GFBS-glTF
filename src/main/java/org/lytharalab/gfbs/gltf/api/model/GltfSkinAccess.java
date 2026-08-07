package org.lytharalab.gfbs.gltf.api.model;

/** Internal read-only zero-copy access for realtime skinning data. */
public final class GltfSkinAccess {
    private GltfSkinAccess() {}

    public static int[] joints(GltfSkin skin) { return skin.jointsView(); }
    public static float[] inverseBindMatrices(GltfSkin skin) {
        return skin.inverseBindMatricesView();
    }
}
