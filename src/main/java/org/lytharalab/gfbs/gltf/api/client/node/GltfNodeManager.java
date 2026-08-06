package org.lytharalab.gfbs.gltf.api.client.node;

import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialVariant;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;
import org.lytharalab.gfbs.gltf.core.animation.PoseTransforms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.renderer.RenderType;

/**
 * Per-instance node graph and primitive state manager.
 *
 * <p>The imported {@link GltfAsset} remains immutable and shareable. This manager owns every
 * mutable runtime parameter, indexes duplicate node/material names safely, supports exact paths,
 * bulk subtree operations and named material variants, and resolves animation with transform and
 * morph overrides without mutating the asset or animation controller.</p>
 */
public final class GltfNodeManager {
    private final GltfAsset asset;
    private final GltfNodeState[] nodes;
    private final Map<GltfPrimitiveKey, GltfPrimitiveState> primitives = new LinkedHashMap<>();
    private final Map<String, List<GltfNodeState>> nodesByName = new LinkedHashMap<>();
    private final Map<String, List<GltfNodeState>> nodesByPath = new LinkedHashMap<>();
    private final Map<String, List<Integer>> materialsByName = new LinkedHashMap<>();
    private final Map<String, GltfMaterialVariant> variants = new LinkedHashMap<>();
    private final String[] paths;
    private long revision;
    private long collisionRevision;

    public GltfNodeManager(GltfAsset asset) {
        this.asset = Objects.requireNonNull(asset, "asset");
        nodes = new GltfNodeState[asset.nodes().size()];
        paths = new String[nodes.length];

        for (int index = 0; index < asset.materials().size(); index++) {
            materialsByName.computeIfAbsent(asset.materials().get(index).name(), ignored -> new ArrayList<>())
                .add(index);
        }
        for (int nodeIndex = 0; nodeIndex < nodes.length; nodeIndex++) {
            List<GltfPrimitiveState> nodePrimitives = new ArrayList<>();
            GltfNode node = asset.nodes().get(nodeIndex);
            for (int meshIndex : node.meshes()) {
                GltfMesh mesh = asset.meshes().get(meshIndex);
                for (int primitiveIndex = 0; primitiveIndex < mesh.primitives().size(); primitiveIndex++) {
                    GltfPrimitiveKey key = new GltfPrimitiveKey(nodeIndex, meshIndex, primitiveIndex);
                    GltfPrimitiveState state = new GltfPrimitiveState(
                        this, key, mesh.primitives().get(primitiveIndex)
                    );
                    primitives.put(key, state);
                    nodePrimitives.add(state);
                }
            }
            nodes[nodeIndex] = new GltfNodeState(this, nodeIndex, nodePrimitives);
        }
        for (GltfNodeState node : nodes) {
            nodesByName.computeIfAbsent(node.name(), ignored -> new ArrayList<>()).add(node);
            nodesByPath.computeIfAbsent(path(node.index()), ignored -> new ArrayList<>()).add(node);
        }
    }

    public GltfAsset asset() { return asset; }
    public long revision() { return revision; }
    public long collisionRevision() { return collisionRevision; }
    public int size() { return nodes.length; }
    public List<GltfNodeState> all() { return List.of(nodes); }
    public List<GltfPrimitiveState> allPrimitives() { return List.copyOf(primitives.values()); }

    public GltfNodeState node(int index) {
        if (index < 0 || index >= nodes.length) throw new IndexOutOfBoundsException("node " + index);
        return nodes[index];
    }

    /** Returns all nodes with the exact glTF name. Duplicate names are intentionally preserved. */
    public List<GltfNodeState> named(String name) {
        Objects.requireNonNull(name, "name");
        return List.copyOf(nodesByName.getOrDefault(name, List.of()));
    }

    public Optional<GltfNodeState> first(String name) {
        List<GltfNodeState> matches = named(name);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    /** Requires an exact unique node name; use {@link #named(String)} when duplicates are valid. */
    public GltfNodeState require(String name) {
        return requireUnique(named(name), "node name", name);
    }

    public List<GltfNodeState> atPath(String path) {
        Objects.requireNonNull(path, "path");
        return List.copyOf(nodesByPath.getOrDefault(normalizePath(path), List.of()));
    }

    public GltfNodeState requirePath(String path) {
        return requireUnique(atPath(path), "node path", path);
    }

    public String path(int nodeIndex) {
        node(nodeIndex);
        String cached = paths[nodeIndex];
        if (cached != null) return cached;
        ArrayDeque<Integer> chain = new ArrayDeque<>();
        int current = nodeIndex;
        while (current >= 0 && paths[current] == null) {
            chain.push(current);
            current = asset.nodes().get(current).parent();
        }
        String prefix = current < 0 ? "" : paths[current];
        while (!chain.isEmpty()) {
            int next = chain.pop();
            String name = asset.nodes().get(next).name();
            String segment = name.isBlank() ? "#" + next : name;
            prefix = prefix + "/" + segment;
            paths[next] = prefix;
        }
        return paths[nodeIndex];
    }

    public GltfPrimitiveState primitive(GltfPrimitiveKey key) {
        GltfPrimitiveState state = primitives.get(Objects.requireNonNull(key, "key"));
        if (state == null) throw new IllegalArgumentException("Primitive is not part of this asset: " + key);
        return state;
    }

    public GltfPrimitiveState primitive(int node, int mesh, int primitive) {
        return primitive(new GltfPrimitiveKey(node, mesh, primitive));
    }

    public List<GltfNodeState> subtree(int root, boolean includeRoot) {
        node(root);
        List<GltfNodeState> result = new ArrayList<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        if (includeRoot) pending.push(root);
        else pushChildren(root, pending);
        while (!pending.isEmpty()) {
            int index = pending.pop();
            result.add(nodes[index]);
            pushChildren(index, pending);
        }
        return List.copyOf(result);
    }

    public int forEachNode(String name, Consumer<GltfNodeState> operation) {
        Objects.requireNonNull(operation, "operation");
        List<GltfNodeState> matches = named(name);
        matches.forEach(operation);
        return matches.size();
    }

    public int forEachPrimitive(int nodeIndex, boolean descendants,
                                Consumer<GltfPrimitiveState> operation) {
        Objects.requireNonNull(operation, "operation");
        List<GltfNodeState> targets = descendants ? subtree(nodeIndex, true) : List.of(node(nodeIndex));
        int count = 0;
        for (GltfNodeState target : targets) {
            for (GltfPrimitiveState primitive : target.primitives()) {
                operation.accept(primitive);
                count++;
            }
        }
        return count;
    }

    public GltfNodeManager defineVariant(String name, GltfMaterialVariant variant) {
        requireName(name, "Variant name");
        validateVariant(Objects.requireNonNull(variant, "variant"));
        variants.put(name, variant);
        touch();
        return this;
    }

    public GltfNodeManager removeVariant(String name) {
        requireName(name, "Variant name");
        variants.remove(name);
        touch();
        return this;
    }

    public Map<String, GltfMaterialVariant> variants() { return Map.copyOf(variants); }

    public GltfMaterialVariant requireVariant(String name) {
        requireName(name, "Variant name");
        GltfMaterialVariant variant = variants.get(name);
        if (variant == null) throw new IllegalArgumentException("Unknown material variant: " + name);
        return variant;
    }

    public int applyVariant(int nodeIndex, boolean descendants, String variant) {
        GltfMaterialVariant value = requireVariant(variant);
        return forEachPrimitive(nodeIndex, descendants, primitive -> primitive.materialVariant(value));
    }

    public List<Integer> materialsNamed(String name) {
        Objects.requireNonNull(name, "name");
        return List.copyOf(materialsByName.getOrDefault(name, List.of()));
    }

    public int requireMaterial(String name) {
        List<Integer> matches = materialsNamed(name);
        if (matches.isEmpty()) throw new IllegalArgumentException("Unknown material: " + name);
        if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous material name: " + name);
        return matches.get(0);
    }

    /** Computes hierarchy-correct world transforms including all runtime node overrides. */
    public float[] computeWorldMatrices(ModelPose pose) {
        Objects.requireNonNull(pose, "pose");
        if (pose.asset() != asset) throw new IllegalArgumentException("Pose belongs to a different asset");
        float[] result = new float[Math.multiplyExact(nodes.length, 16)];
        byte[] state = new byte[nodes.length];
        int[] chain = new int[nodes.length];
        for (int start = 0; start < nodes.length; start++) {
            if (state[start] == 2) continue;
            int length = 0;
            int current = start;
            while (current >= 0 && state[current] != 2) {
                if (state[current] == 1) throw new IllegalArgumentException("Cycle in glTF node hierarchy");
                state[current] = 1;
                chain[length++] = current;
                current = asset.nodes().get(current).parent();
            }
            for (int position = length - 1; position >= 0; position--) {
                int nodeIndex = chain[position];
                float[] world = nodes[nodeIndex].resolveLocalMatrix(pose.node(nodeIndex));
                int parent = asset.nodes().get(nodeIndex).parent();
                if (parent >= 0) world = PoseTransforms.multiply(slice(result, parent), world);
                System.arraycopy(world, 0, result, nodeIndex * 16, 16);
                state[nodeIndex] = 2;
            }
        }
        return result;
    }

    /** Returns the effective morph weights for rendering/collision, or null when absent. */
    public float[] resolveMorphWeights(int nodeIndex, GltfMesh mesh, ModelPose pose) {
        node(nodeIndex);
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(pose, "pose");
        float[] weights = nodes[nodeIndex].resolveMorphWeights(
            pose.node(nodeIndex), mesh.defaultMorphWeights()
        );
        return weights == null ? null : weights.clone();
    }

    public GltfNodeManager resetStates() {
        for (GltfNodeState node : nodes) node.reset();
        touch();
        return this;
    }

    public GltfNodeManager clear() {
        resetStates();
        variants.clear();
        touch();
        return this;
    }

    /** Captures every mutable node, primitive and named-variant parameter for later restoration. */
    public Snapshot snapshot() {
        List<NodeSnapshot> nodeSnapshots = new ArrayList<>(nodes.length);
        for (GltfNodeState node : nodes) nodeSnapshots.add(node.snapshot());
        Map<GltfPrimitiveKey, PrimitiveSnapshot> primitiveSnapshots = new LinkedHashMap<>();
        for (Map.Entry<GltfPrimitiveKey, GltfPrimitiveState> entry : primitives.entrySet()) {
            primitiveSnapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new Snapshot(asset, nodeSnapshots, primitiveSnapshots, variants);
    }

    /** Restores a snapshot captured from this exact immutable asset. */
    public GltfNodeManager restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.asset() != asset) {
            throw new IllegalArgumentException("Node snapshot belongs to a different glTF asset");
        }
        if (snapshot.nodes().size() != nodes.length
            || !snapshot.primitives().keySet().equals(primitives.keySet())) {
            throw new IllegalArgumentException("Node snapshot topology does not match the asset");
        }
        for (GltfMaterialVariant variant : snapshot.variants().values()) validateVariant(variant);
        variants.clear();
        variants.putAll(snapshot.variants());
        for (int index = 0; index < nodes.length; index++) {
            nodes[index].restore(snapshot.nodes().get(index));
        }
        for (Map.Entry<GltfPrimitiveKey, PrimitiveSnapshot> entry : snapshot.primitives().entrySet()) {
            primitives.get(entry.getKey()).restore(entry.getValue());
        }
        touchCollision();
        return this;
    }

    int morphTargetCount(int nodeIndex) {
        GltfNode node = asset.nodes().get(nodeIndex);
        if (node.meshes().length == 0) return 0;
        GltfMesh mesh = asset.meshes().get(node.meshes()[0]);
        return mesh.primitives().isEmpty() ? 0 : mesh.primitives().get(0).morphTargets().size();
    }

    void resolveMaterial(GltfPrimitiveState state, GltfMaterialVariant variant) {
        Objects.requireNonNull(variant, "variant");
        validateVariant(variant);
        GltfPrimitiveState.ResolvedMaterial cached = state.cachedMaterial(variant);
        if (cached != null) {
            state.resolvedMaterial(variant, cached.index(), cached.material());
            return;
        }
        int materialIndex = variant.materialIndex() == GltfMaterialVariant.PRIMITIVE_MATERIAL
            ? state.definition().material() : variant.materialIndex();
        GltfMaterial material = variant.override().resolve(asset.materials().get(materialIndex));
        validateMaterial(state.definition(), material, state.key());
        state.resolvedMaterial(variant, materialIndex, material);
    }

    void touch() { revision++; }
    void touchCollision() { revision++; collisionRevision++; }

    private void validateVariant(GltfMaterialVariant variant) {
        if (variant.materialIndex() >= asset.materials().size()) {
            throw new IndexOutOfBoundsException("material " + variant.materialIndex());
        }
    }

    private void validateMaterial(GltfPrimitive primitive, GltfMaterial material,
                                  GltfPrimitiveKey key) {
        validateTexture(primitive, material.baseColorTextureInfo(), "base color", key);
        validateTexture(primitive, material.metallicRoughnessTextureInfo(), "metallic-roughness", key);
        validateTexture(primitive, material.normalTextureInfo(), "normal", key);
        validateTexture(primitive, material.occlusionTextureInfo(), "occlusion", key);
        validateTexture(primitive, material.emissiveTextureInfo(), "emissive", key);
    }

    private void validateTexture(GltfPrimitive primitive, GltfTextureInfo texture,
                                 String label, GltfPrimitiveKey key) {
        if (texture.texture() < 0) return;
        if (texture.texture() >= asset.textures().size()) {
            throw new IndexOutOfBoundsException(label + " texture " + texture.texture());
        }
        boolean missing = texture.texCoord() == 0
            ? primitive.texCoords0() == null : primitive.texCoords1() == null;
        if (missing) {
            throw new IllegalArgumentException(key + " lacks TEXCOORD_" + texture.texCoord()
                + " required by the " + label + " texture");
        }
    }

    private void pushChildren(int nodeIndex, ArrayDeque<Integer> pending) {
        int[] children = asset.nodes().get(nodeIndex).children();
        for (int index = children.length - 1; index >= 0; index--) pending.push(children[index]);
    }

    private static float[] slice(float[] matrices, int index) {
        return Arrays.copyOfRange(matrices, index * 16, index * 16 + 16);
    }

    private static String normalizePath(String path) {
        if (path.isBlank()) throw new IllegalArgumentException("Node path is blank");
        return path.startsWith("/") ? path : "/" + path;
    }

    private static void requireName(String name, String label) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException(label + " is blank");
    }

    private static <T> T requireUnique(List<T> values, String label, String value) {
        if (values.isEmpty()) throw new IllegalArgumentException("Unknown " + label + ": " + value);
        if (values.size() != 1) throw new IllegalArgumentException("Ambiguous " + label + ": " + value);
        return values.get(0);
    }

    public record Snapshot(
        GltfAsset asset,
        List<NodeSnapshot> nodes,
        Map<GltfPrimitiveKey, PrimitiveSnapshot> primitives,
        Map<String, GltfMaterialVariant> variants
    ) {
        public Snapshot {
            Objects.requireNonNull(asset, "asset");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            primitives = Map.copyOf(Objects.requireNonNull(primitives, "primitives"));
            variants = Map.copyOf(Objects.requireNonNull(variants, "variants"));
        }
    }

    public record NodeSnapshot(
        boolean subtreeVisible,
        boolean selfVisible,
        boolean collisionEnabled,
        boolean castShadows,
        float alpha,
        float[] colorMultiplier,
        GltfRenderOptions.LightMode lightMode,
        GltfRenderOptions.CullMode cullMode,
        float[] localMatrix,
        float[] postTransform,
        float[] translation,
        float[] rotation,
        float[] scale,
        float[] morphWeights,
        Map<String, Object> parameters
    ) {
        public NodeSnapshot {
            colorMultiplier = copy(colorMultiplier);
            localMatrix = copy(localMatrix);
            postTransform = copy(postTransform);
            translation = copy(translation);
            rotation = copy(rotation);
            scale = copy(scale);
            morphWeights = copy(morphWeights);
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        }
        @Override public float[] colorMultiplier() { return copy(colorMultiplier); }
        @Override public float[] localMatrix() { return copy(localMatrix); }
        @Override public float[] postTransform() { return copy(postTransform); }
        @Override public float[] translation() { return copy(translation); }
        @Override public float[] rotation() { return copy(rotation); }
        @Override public float[] scale() { return copy(scale); }
        @Override public float[] morphWeights() { return copy(morphWeights); }
    }

    public record PrimitiveSnapshot(
        boolean visible,
        boolean collisionEnabled,
        boolean castShadows,
        float alpha,
        float[] colorMultiplier,
        GltfRenderOptions.LightMode lightMode,
        GltfRenderOptions.CullMode cullMode,
        RenderType renderType,
        GltfMaterialVariant materialVariant,
        Map<String, Object> parameters
    ) {
        public PrimitiveSnapshot {
            colorMultiplier = copy(colorMultiplier);
            materialVariant = Objects.requireNonNull(materialVariant, "materialVariant");
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        }
        @Override public float[] colorMultiplier() { return copy(colorMultiplier); }
    }

    private static float[] copy(float[] value) { return value == null ? null : value.clone(); }
}
