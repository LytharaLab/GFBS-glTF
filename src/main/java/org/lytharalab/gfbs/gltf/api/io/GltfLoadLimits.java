package org.lytharalab.gfbs.gltf.api.io;

/** Resource and allocation limits for one imported scene asset, including instanced nodes. */
public record GltfLoadLimits(long resourceBytes, long decodedArrayBytes, int nodes) {
    public static final GltfLoadLimits DEFAULT =
        new GltfLoadLimits(256L * 1024 * 1024, 256L * 1024 * 1024, 100_000);

    public GltfLoadLimits {
        if (resourceBytes < 1024 || decodedArrayBytes < 1024 || nodes < 1) {
            throw new IllegalArgumentException("glTF resource and array budgets must be at least 1024 bytes, with room for one node");
        }
        if (nodes > Integer.MAX_VALUE / 16) {
            throw new IllegalArgumentException("Node limit exceeds Java matrix array capacity");
        }
    }
}
