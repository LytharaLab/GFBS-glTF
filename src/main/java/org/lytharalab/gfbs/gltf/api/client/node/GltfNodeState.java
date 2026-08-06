package org.lytharalab.gfbs.gltf.api.client.node;

import org.lytharalab.gfbs.gltf.api.animation.NodePose;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.core.animation.AnimationEvaluator;
import org.lytharalab.gfbs.gltf.core.animation.PoseTransforms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete mutable per-instance state for one glTF node. Asset topology remains immutable, while
 * visibility, transform, morph, render, collision and integration parameters are instance-local.
 */
public final class GltfNodeState {
    private final GltfNodeManager owner;
    private final int index;
    private final List<GltfPrimitiveState> primitives;
    private boolean subtreeVisible = true;
    private boolean selfVisible = true;
    private boolean collisionEnabled = true;
    private boolean castShadows = true;
    private float alpha = 1.0f;
    private final float[] colorMultiplier = {1, 1, 1};
    private GltfRenderOptions.LightMode lightMode;
    private GltfRenderOptions.CullMode cullMode;
    private float[] localMatrix;
    private float[] postTransform;
    private float[] translation;
    private float[] rotation;
    private float[] scale;
    private float[] morphWeights;
    private final Map<String, Object> parameters = new LinkedHashMap<>();

    GltfNodeState(GltfNodeManager owner, int index, List<GltfPrimitiveState> primitives) {
        this.owner = owner;
        this.index = index;
        this.primitives = primitives;
    }

    public int index() { return index; }
    public GltfNode definition() { return owner.asset().nodes().get(index); }
    public String name() { return definition().name(); }
    public String path() { return owner.path(index); }
    public int parentIndex() { return definition().parent(); }
    public int[] childIndices() { return definition().children(); }
    public List<GltfPrimitiveState> primitives() { return List.copyOf(primitives); }

    /** A false value suppresses this node and its complete child subtree. */
    public boolean subtreeVisible() { return subtreeVisible; }
    public GltfNodeState subtreeVisible(boolean visible) {
        subtreeVisible = visible; changed(); return this;
    }

    /** Controls only this node's meshes; children remain independently traversable. */
    public boolean selfVisible() { return selfVisible; }
    public GltfNodeState selfVisible(boolean visible) {
        selfVisible = visible; changed(); return this;
    }

    public boolean collisionEnabled() { return collisionEnabled; }
    public GltfNodeState collisionEnabled(boolean enabled) {
        collisionEnabled = enabled; collisionChanged(); return this;
    }

    public boolean castShadows() { return castShadows; }
    public GltfNodeState castShadows(boolean enabled) {
        castShadows = enabled; changed(); return this;
    }

    public float alpha() { return alpha; }
    public GltfNodeState alpha(float value) {
        alpha = unit(value, "Node alpha"); changed(); return this;
    }

    public float[] colorMultiplier() { return colorMultiplier.clone(); }
    public GltfNodeState colorMultiplier(float red, float green, float blue) {
        colorMultiplier[0] = nonNegative(red, "Red multiplier");
        colorMultiplier[1] = nonNegative(green, "Green multiplier");
        colorMultiplier[2] = nonNegative(blue, "Blue multiplier");
        changed(); return this;
    }

    public Optional<GltfRenderOptions.LightMode> lightMode() { return Optional.ofNullable(lightMode); }
    public GltfNodeState lightMode(GltfRenderOptions.LightMode mode) {
        lightMode = mode; changed(); return this;
    }

    public Optional<GltfRenderOptions.CullMode> cullMode() { return Optional.ofNullable(cullMode); }
    public GltfNodeState cullMode(GltfRenderOptions.CullMode mode) {
        cullMode = mode; changed(); return this;
    }

    /** Replaces the complete animated/authored local matrix. Passing null clears the override. */
    public GltfNodeState localMatrix(float[] matrix) {
        localMatrix = matrix == null ? null : matrix(matrix, "Local matrix");
        if (matrix != null) {
            translation = rotation = scale = null;
        }
        collisionChanged(); return this;
    }

    public float[] localMatrix() { return copy(localMatrix); }

    /** Multiplies an additional local transform after the resolved authored/animated transform. */
    public GltfNodeState postTransform(float[] matrix) {
        postTransform = matrix == null ? null : matrix(matrix, "Post transform");
        collisionChanged(); return this;
    }

    public float[] postTransform() { return copy(postTransform); }

    public GltfNodeState translation(float x, float y, float z) {
        requireTrsNode();
        translation = finite(new float[]{x, y, z}, "Translation");
        localMatrix = null;
        collisionChanged(); return this;
    }

    public float[] translation() { return copy(translation); }

    public GltfNodeState rotation(float x, float y, float z, float w) {
        requireTrsNode();
        rotation = finite(new float[]{x, y, z, w}, "Rotation");
        AnimationEvaluator.normalizeQuaternion(rotation);
        localMatrix = null;
        collisionChanged(); return this;
    }

    public float[] rotation() { return copy(rotation); }

    public GltfNodeState scale(float x, float y, float z) {
        requireTrsNode();
        scale = finite(new float[]{x, y, z}, "Scale");
        localMatrix = null;
        collisionChanged(); return this;
    }

    public float[] scale() { return copy(scale); }

    public GltfNodeState transform(float[] translation, float[] rotation, float[] scale) {
        Objects.requireNonNull(translation, "translation");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(scale, "scale");
        if (translation.length != 3 || rotation.length != 4 || scale.length != 3) {
            throw new IllegalArgumentException("TRS transform requires 3 translation, 4 rotation and 3 scale values");
        }
        translation(translation[0], translation[1], translation[2]);
        rotation(rotation[0], rotation[1], rotation[2], rotation[3]);
        scale(scale[0], scale[1], scale[2]);
        return this;
    }

    public GltfNodeState clearTransformOverrides() {
        localMatrix = postTransform = translation = rotation = scale = null;
        collisionChanged(); return this;
    }

    public GltfNodeState morphWeights(float... weights) {
        Objects.requireNonNull(weights, "weights");
        int expected = owner.morphTargetCount(index);
        if (weights.length != expected) {
            throw new IllegalArgumentException("Node " + index + " requires " + expected + " morph weights");
        }
        morphWeights = finite(weights.clone(), "Morph weights");
        collisionChanged(); return this;
    }

    public GltfNodeState clearMorphWeights() {
        morphWeights = null; collisionChanged(); return this;
    }

    public float[] morphWeights() { return copy(morphWeights); }

    public GltfNodeState parameter(String name, Object value) {
        requireParameterName(name);
        if (value == null) parameters.remove(name); else parameters.put(name, value);
        changed(); return this;
    }

    public Optional<Object> parameter(String name) {
        requireParameterName(name);
        return Optional.ofNullable(parameters.get(name));
    }

    public <T> Optional<T> parameter(String name, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return parameter(name).filter(type::isInstance).map(type::cast);
    }

    public Map<String, Object> parameters() { return Map.copyOf(parameters); }

    public GltfNodeState reset() {
        subtreeVisible = selfVisible = collisionEnabled = castShadows = true;
        alpha = 1.0f;
        colorMultiplier[0] = colorMultiplier[1] = colorMultiplier[2] = 1.0f;
        lightMode = null;
        cullMode = null;
        localMatrix = postTransform = translation = rotation = scale = morphWeights = null;
        parameters.clear();
        for (GltfPrimitiveState primitive : primitives) primitive.reset();
        collisionChanged();
        return this;
    }

    float[] resolveLocalMatrix(NodePose pose) {
        float[] resolved;
        if (localMatrix != null) {
            resolved = localMatrix.clone();
        } else if (translation != null || rotation != null || scale != null) {
            float[] t = translation == null ? pose.translation() : translation;
            float[] r = rotation == null ? pose.rotation() : rotation;
            float[] s = scale == null ? pose.scale() : scale;
            resolved = PoseTransforms.trsMatrix(t, r, s);
        } else {
            resolved = PoseTransforms.localMatrix(definition(), pose);
        }
        return postTransform == null ? resolved : PoseTransforms.multiply(resolved, postTransform);
    }

    float[] resolveMorphWeights(NodePose pose, float[] meshDefaults) {
        if (morphWeights != null) return morphWeights;
        if (pose.weights() != null) return pose.weights();
        return meshDefaults;
    }

    float[] colorMultiplierInternal() { return colorMultiplier; }

    GltfNodeManager.NodeSnapshot snapshot() {
        return new GltfNodeManager.NodeSnapshot(
            subtreeVisible, selfVisible, collisionEnabled, castShadows, alpha,
            colorMultiplier, lightMode, cullMode, localMatrix, postTransform,
            translation, rotation, scale, morphWeights, parameters
        );
    }

    void restore(GltfNodeManager.NodeSnapshot snapshot) {
        subtreeVisible = snapshot.subtreeVisible();
        selfVisible = snapshot.selfVisible();
        collisionEnabled = snapshot.collisionEnabled();
        castShadows = snapshot.castShadows();
        alpha = snapshot.alpha();
        System.arraycopy(snapshot.colorMultiplier(), 0, colorMultiplier, 0, 3);
        lightMode = snapshot.lightMode();
        cullMode = snapshot.cullMode();
        localMatrix = snapshot.localMatrix();
        postTransform = snapshot.postTransform();
        translation = snapshot.translation();
        rotation = snapshot.rotation();
        scale = snapshot.scale();
        morphWeights = snapshot.morphWeights();
        parameters.clear();
        parameters.putAll(snapshot.parameters());
    }

    private void requireTrsNode() {
        if (definition().matrix() != null) {
            throw new IllegalStateException(
                "Node " + index + " is matrix-authored; use localMatrix() or postTransform()"
            );
        }
    }

    private void changed() { owner.touch(); }
    private void collisionChanged() { owner.touchCollision(); }
    private static float[] copy(float[] value) { return value == null ? null : value.clone(); }

    private static float[] matrix(float[] values, String label) {
        if (values.length != 16) throw new IllegalArgumentException(label + " requires 16 values");
        return finite(values.clone(), label);
    }

    private static float[] finite(float[] values, String label) {
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException(label + " must be finite");
        }
        return values;
    }

    private static float unit(float value, String label) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(label + " must be between 0 and 1");
        }
        return value;
    }

    private static float nonNegative(float value, String label) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(label + " must be finite and non-negative");
        }
        return value;
    }

    private static void requireParameterName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Parameter name is blank");
    }
}
