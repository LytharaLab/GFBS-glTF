package org.lytharalab.gfbs.gltf.api.model;

import java.util.ArrayDeque;
import java.util.BitSet;

/** Internal access to read-only data shared by instances of an immutable asset. */
public final class GltfAssetAccess {
    private GltfAssetAccess() {}

    public static float[] bindWorldMatrices(GltfAsset asset) { return asset.bindWorldMatrices(); }
    public static BitSet animatedTransforms(GltfAsset asset) { return asset.animatedTransforms(); }
    public static BitSet animatedSubtrees(GltfAsset asset) { return asset.animatedSubtrees(); }

    public static BitSet subtrees(GltfAsset asset, BitSet roots) {
        BitSet result = (BitSet) roots.clone();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int root = roots.nextSetBit(0); root >= 0; root = roots.nextSetBit(root + 1)) pending.push(root);
        while (!pending.isEmpty()) {
            GltfNode node = asset.nodes().get(pending.pop());
            for (int i = 0; i < node.childCount(); i++) {
                int child = node.child(i);
                if (!result.get(child)) {
                    result.set(child);
                    pending.push(child);
                }
            }
        }
        return result;
    }
}
