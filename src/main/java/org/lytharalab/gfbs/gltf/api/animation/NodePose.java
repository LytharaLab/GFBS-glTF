package org.lytharalab.gfbs.gltf.api.animation;

import org.lytharalab.gfbs.gltf.api.model.GltfNode;

import java.util.Arrays;
import java.util.Objects;

public final class NodePose {
    private final float[] translation = new float[3];
    private final float[] rotation = new float[4];
    private final float[] scale = new float[3];
    private float[] weights;

    public NodePose(GltfNode node) { reset(node); }

    public void reset(GltfNode node) {
        Objects.requireNonNull(node, "node");
        node.copyTranslationTo(translation);
        node.copyRotationTo(rotation);
        node.copyScaleTo(scale);
        int count = node.morphWeightCount();
        if (count == 0) weights = null;
        else {
            ensureWeights(count);
            node.copyMorphWeightsTo(weights);
        }
    }

    public float[] translation() { return translation; }
    public float[] rotation() { return rotation; }
    public float[] scale() { return scale; }
    public float[] weights() { return weights; }
    public void ensureWeights(int count) {
        if (count < 0) throw new IllegalArgumentException("Weight count must be non-negative");
        if (weights == null || weights.length != count) weights = new float[count];
    }

    public NodePose copy() { return new NodePose(translation, rotation, scale, weights); }

    private NodePose(float[] translation, float[] rotation, float[] scale, float[] weights) {
        set(this.translation, translation); set(this.rotation, rotation); set(this.scale, scale);
        this.weights = weights == null ? null : weights.clone();
    }

    private static void set(float[] target, float[] source) { System.arraycopy(source, 0, target, 0, target.length); }
    @Override public String toString() { return "NodePose{" + Arrays.toString(translation) + '}'; }
}
