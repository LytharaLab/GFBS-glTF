package org.lytharalab.gfbs.gltf.api.animation;

import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.util.Objects;

public final class ModelPose {
    private final GltfAsset asset;
    private final NodePose[] nodes;

    public ModelPose(GltfAsset asset) {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.nodes = new NodePose[asset.nodes().size()];
        for (int i = 0; i < nodes.length; i++) nodes[i] = new NodePose(asset.nodes().get(i));
    }

    private ModelPose(GltfAsset asset, NodePose[] nodes) {
        this.asset = asset;
        this.nodes = nodes;
    }

    public GltfAsset asset() { return asset; }
    public int nodeCount() { return nodes.length; }
    public NodePose node(int index) { return nodes[index]; }
    public void reset() { for (int i = 0; i < nodes.length; i++) nodes[i].reset(asset.nodes().get(i)); }

    public ModelPose copy() {
        NodePose[] copied = new NodePose[nodes.length];
        for (int i = 0; i < nodes.length; i++) copied[i] = nodes[i].copy();
        return new ModelPose(asset, copied);
    }
    public void copyFrom(ModelPose source){if(source==null||source.asset!=asset||source.nodes.length!=nodes.length)throw new IllegalArgumentException("Pose belongs to a different glTF asset");for(int i=0;i<nodes.length;i++){NodePose f=source.nodes[i],t=nodes[i];System.arraycopy(f.translation(),0,t.translation(),0,3);System.arraycopy(f.rotation(),0,t.rotation(),0,4);System.arraycopy(f.scale(),0,t.scale(),0,3);if(f.weights()!=null){t.ensureWeights(f.weights().length);System.arraycopy(f.weights(),0,t.weights(),0,f.weights().length);}}}
}
