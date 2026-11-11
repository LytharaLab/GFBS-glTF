package org.lytharalab.gfbs.gltf.api.animation;

import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.util.Objects;
import java.util.BitSet;

public final class ModelPose {
    private final GltfAsset asset;
    private final NodePose[] nodes;
    private final BitSet initialized;

    public ModelPose(GltfAsset asset) {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.nodes = new NodePose[asset.nodes().size()];
        this.initialized = new BitSet();
    }

    private ModelPose(GltfAsset asset, NodePose[] nodes, BitSet initialized) {
        this.asset = asset;
        this.nodes = nodes;
        this.initialized = initialized;
    }

    public GltfAsset asset() { return asset; }
    public int nodeCount() { return nodes.length; }
    NodePose nodeOrNull(int index) { return nodes[index]; }
    public NodePose node(int index) {
        NodePose value = nodes[index];
        if (value == null) {
            nodes[index] = value = new NodePose(asset.nodes().get(index));
            initialized.set(index);
        }
        return value;
    }

    public void reset() {
        for (int i = initialized.nextSetBit(0); i >= 0; i = initialized.nextSetBit(i + 1)) {
            nodes[i].reset(asset.nodes().get(i));
        }
    }

    public ModelPose copy() {
        NodePose[] copied = new NodePose[nodes.length];
        for (int i = initialized.nextSetBit(0); i >= 0; i = initialized.nextSetBit(i + 1)) {
            copied[i] = nodes[i].copy();
        }
        return new ModelPose(asset, copied, (BitSet) initialized.clone());
    }
    public void copyFrom(ModelPose source) {
        if (source == null || source.asset != asset) {
            throw new IllegalArgumentException("Pose belongs to a different glTF asset");
        }
        for (int i = 0; i < nodes.length; i++) {
            NodePose from = source.nodes[i];
            if (from == null && nodes[i] == null) continue;
            NodePose to = node(i);
            if (from == null) from = source.node(i);
            System.arraycopy(from.translation(), 0, to.translation(), 0, 3);
            System.arraycopy(from.rotation(), 0, to.rotation(), 0, 4);
            System.arraycopy(from.scale(), 0, to.scale(), 0, 3);
            if (from.weights() != null) {
                to.ensureWeights(from.weights().length);
                System.arraycopy(from.weights(), 0, to.weights(), 0, from.weights().length);
            }
        }
    }
}
