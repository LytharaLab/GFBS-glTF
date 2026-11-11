package org.lytharalab.gfbs.gltf.api.client.node;

import org.lytharalab.gfbs.gltf.api.animation.AnimationController;
import org.lytharalab.gfbs.gltf.api.animation.AnimationPoseAccess;
import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialVariant;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfAssetAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfMeshAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.GltfSkinAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;
import org.lytharalab.gfbs.gltf.core.animation.PoseTransforms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
    private final Map<String, List<GltfNodeState>> namedSelections = new LinkedHashMap<>();
    private Map<String, List<Integer>> materialsByName;
    private final Map<String, GltfMaterialVariant> variants = new LinkedHashMap<>();
    private final List<GltfNodeState> allNodes = new NodeList();
    private final BitSet transformOverrides = new BitSet();
    private final BitSet dirtyTransforms = new BitSet();
    private BitSet overrideSubtrees;
    private int hiddenSubtrees;
    private float[] worldMatrices;
    private float[] resolvedWorld;
    private byte[] worldState;
    private int[] worldChain;
    private final float[] worldLocalScratch = new float[16];
    private final float[] worldMultiplyScratch = new float[16];
    private final float[] bindTranslation = new float[3];
    private final float[] bindRotation = new float[4];
    private final float[] bindScale = new float[3];
    private final Map<Integer, SkinPalette> skinPalettes = new LinkedHashMap<>();
    private ModelPose cachedWorldPose;
    private long cachedWorldPoseRevision = Long.MIN_VALUE;
    private long cachedWorldPoseSignature = Long.MIN_VALUE;
    private long cachedWorldNodeRevision = Long.MIN_VALUE;
    private boolean cachedWorldWasWritable;
    private long worldGeneration;
    private long revision;
    private long collisionRevision;
    private long transformRevision;
    private boolean hasMaterialOverrides;

    public GltfNodeManager(GltfAsset asset) {
        this.asset = Objects.requireNonNull(asset, "asset");
        nodes = new GltfNodeState[asset.nodes().size()];
    }

    public GltfAsset asset() { return asset; }
    public long revision() { return revision; }
    public long collisionRevision() { return collisionRevision; }
    public boolean hasMaterialOverrides() { return hasMaterialOverrides; }
    public int size() { return nodes.length; }
    public List<GltfNodeState> all() { return allNodes; }
    public List<GltfPrimitiveState> allPrimitives() {
        List<GltfPrimitiveState> result = new ArrayList<>();
        for (GltfNodeState state : all()) result.addAll(state.primitives());
        return List.copyOf(result);
    }

    public GltfNodeState node(int index) {
        if (index < 0 || index >= nodes.length) throw new IndexOutOfBoundsException("node " + index);
        GltfNodeState state = nodes[index];
        if (state == null) nodes[index] = state = new GltfNodeState(this, index);
        return state;
    }

    /** Returns all nodes with the exact glTF name. Duplicate names are intentionally preserved. */
    public List<GltfNodeState> named(String name) {
        Objects.requireNonNull(name, "name");
        List<GltfNodeState> cached = namedSelections.get(name);
        if (cached != null) return cached;
        List<GltfNodeState> matches = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) {
            if (asset.nodes().get(i).name().equals(name)) matches.add(node(i));
        }
        List<GltfNodeState> result = List.copyOf(matches);
        if (namedSelections.size() < 128) namedSelections.put(name, result);
        return result;
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
        String normalized = normalizePath(path);
        List<GltfNodeState> result = new ArrayList<>();
        for (int index = 0; index < nodes.length; index++) {
            if (matchesPath(index, normalized)) result.add(node(index));
        }
        return List.copyOf(result);
    }

    public GltfNodeState requirePath(String path) {
        return requireUnique(atPath(path), "node path", path);
    }

    public String path(int nodeIndex) {
        Objects.checkIndex(nodeIndex, nodes.length);
        ArrayDeque<String> chain = new ArrayDeque<>();
        for (int current = nodeIndex; current >= 0; current = asset.nodes().get(current).parent()) {
            chain.push(segment(current));
        }
        return "/" + String.join("/", chain);
    }

    private boolean matchesPath(int nodeIndex, String path) {
        int end = path.length();
        for (int current = nodeIndex; current >= 0; current = asset.nodes().get(current).parent()) {
            String segment = segment(current);
            int start = end - segment.length();
            if (start <= 0 || path.charAt(start - 1) != '/'
                || !path.regionMatches(start, segment, 0, segment.length())) return false;
            end = start - 1;
        }
        return end == 0;
    }

    private String segment(int index) {
        String name = asset.nodes().get(index).name();
        return name.isBlank() ? "#" + index : name;
    }

    public GltfPrimitiveState primitive(GltfPrimitiveKey key) {
        Objects.requireNonNull(key, "key");
        GltfPrimitiveState state = primitives.get(key);
        if (state != null) return state;
        if (key.nodeIndex() >= nodes.length) {
            throw new IllegalArgumentException("Primitive is not part of this asset: " + key);
        }
        GltfNode definition = node(key.nodeIndex()).definition();
        for (int slot = 0; slot < definition.meshCount(); slot++) {
            if (definition.mesh(slot) != key.meshIndex()) continue;
            List<GltfPrimitive> meshPrimitives = asset.meshes().get(key.meshIndex()).primitives();
            if (key.primitiveIndex() < meshPrimitives.size()) {
                state = new GltfPrimitiveState(this, key, meshPrimitives.get(key.primitiveIndex()));
                primitives.put(key, state);
                return state;
            }
        }
        throw new IllegalArgumentException("Primitive is not part of this asset: " + key);
    }

    public GltfPrimitiveState primitive(int node, int mesh, int primitive) {
        return primitive(new GltfPrimitiveKey(node, mesh, primitive));
    }

    List<GltfPrimitiveState> primitivesForNode(int nodeIndex) {
        List<GltfPrimitiveState> result = new ArrayList<>();
        GltfNode definition = asset.nodes().get(nodeIndex);
        for (int slot = 0; slot < definition.meshCount(); slot++) {
            int mesh = definition.mesh(slot);
            int count = asset.meshes().get(mesh).primitives().size();
            for (int index = 0; index < count; index++) result.add(primitive(nodeIndex, mesh, index));
        }
        return List.copyOf(result);
    }

    void resetPrimitives(int nodeIndex) {
        if (primitives.isEmpty()) return;
        GltfNode definition = asset.nodes().get(nodeIndex);
        for (int slot = 0; slot < definition.meshCount(); slot++) {
            int mesh = definition.mesh(slot);
            int count = asset.meshes().get(mesh).primitives().size();
            for (int index = 0; index < count; index++) {
                GltfPrimitiveState state = primitives.get(new GltfPrimitiveKey(nodeIndex, mesh, index));
                if (state != null) state.reset();
            }
        }
    }

    public List<GltfNodeState> subtree(int root, boolean includeRoot) {
        node(root);
        List<GltfNodeState> result = new ArrayList<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        if (includeRoot) pending.push(root);
        else pushChildren(root, pending);
        while (!pending.isEmpty()) {
            int index = pending.pop();
            result.add(node(index));
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
        List<GltfPrimitiveState> targets = new ArrayList<>();
        forEachPrimitive(nodeIndex, descendants, targets::add);
        List<GltfPrimitiveState.ResolvedMaterial> resolved = new ArrayList<>(targets.size());
        for (GltfPrimitiveState target : targets) resolved.add(prepareMaterial(target, value));
        for (int i = 0; i < targets.size(); i++) setMaterial(targets.get(i), value, resolved.get(i));
        if (!targets.isEmpty()) touch();
        return targets.size();
    }

    public List<Integer> materialsNamed(String name) {
        Objects.requireNonNull(name, "name");
        if (materialsByName == null) {
            Map<String, List<Integer>> index = new LinkedHashMap<>();
            for (int i = 0; i < asset.materials().size(); i++) {
                index.computeIfAbsent(asset.materials().get(i).name(), ignored -> new ArrayList<>()).add(i);
            }
            index.replaceAll((key, values) -> List.copyOf(values));
            materialsByName = index;
        }
        return materialsByName.getOrDefault(name, List.of());
    }

    public int requireMaterial(String name) {
        List<Integer> matches = materialsNamed(name);
        if (matches.isEmpty()) throw new IllegalArgumentException("Unknown material: " + name);
        if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous material name: " + name);
        return matches.get(0);
    }

    /** Computes hierarchy-correct world transforms including all runtime node overrides. */
    public float[] computeWorldMatrices(ModelPose pose) {
        return computeWorldMatricesView(pose, Long.MIN_VALUE).clone();
    }

    /**
     * Allocation-free, read-only world matrix view for renderer/collider hot paths. The returned
     * array belongs to this instance and is invalidated in-place when pose/node revisions change.
     */
    public float[] computeWorldMatricesView(ModelPose pose, long poseRevision) {
        return resolveWorldMatrices(pose, poseRevision, true);
    }

    /** Read-only matrix view for the current animation controller, including runtime overrides. */
    public float[] computeWorldMatricesView(AnimationController controller) {
        return resolveWorldMatrices(AnimationPoseAccess.pose(controller), controller.poseRevision(),
            AnimationPoseAccess.externallyWritable(controller));
    }

    private float[] resolveWorldMatrices(ModelPose pose, long poseRevision, boolean externallyWritable) {
        Objects.requireNonNull(pose, "pose");
        if (pose.asset() != asset) {
            throw new IllegalArgumentException("Pose belongs to a different asset");
        }
        boolean bind = !externallyWritable && poseRevision == 0 && transformOverrides.isEmpty();
        if (bind) {
            float[] shared = GltfAssetAccess.bindWorldMatrices(asset);
            if (resolvedWorld != shared) worldGeneration++;
            dirtyTransforms.clear();
            return resolvedWorld = shared;
        }
        long signature = externallyWritable ? poseSignature(pose) : Long.MIN_VALUE;
        if (worldMatrices == null || cachedWorldPose != pose
            || cachedWorldPoseRevision != poseRevision || cachedWorldNodeRevision != transformRevision
            || cachedWorldPoseSignature != signature || cachedWorldWasWritable != externallyWritable
            || resolvedWorld != worldMatrices) {
            if (externallyWritable) recomputeWorldMatrices(pose);
            else updateManagedWorld(pose, poseRevision);
            cachedWorldPose = pose;
            cachedWorldPoseRevision = poseRevision;
            cachedWorldNodeRevision = transformRevision;
            cachedWorldPoseSignature = signature;
            cachedWorldWasWritable = externallyWritable;
            worldGeneration++;
        }
        return resolvedWorld = worldMatrices;
    }

    private void updateManagedWorld(ModelPose pose, long poseRevision) {
        if (worldMatrices == null || resolvedWorld != worldMatrices || cachedWorldWasWritable
            || cachedWorldPose != pose) {
            float[] bind = GltfAssetAccess.bindWorldMatrices(asset);
            if (worldMatrices == null) worldMatrices = bind.clone();
            else System.arraycopy(bind, 0, worldMatrices, 0, bind.length);
            dirtyTransforms.or(transformOverrides);
            if (poseRevision != 0) dirtyTransforms.or(GltfAssetAccess.animatedTransforms(asset));
        } else if (cachedWorldPoseRevision != poseRevision) {
            dirtyTransforms.or(GltfAssetAccess.animatedTransforms(asset));
        }
        if (worldChain == null) worldChain = new int[nodes.length];
        for (int root = dirtyTransforms.nextSetBit(0); root >= 0; root = dirtyTransforms.nextSetBit(root + 1)) {
            if (hasDirtyAncestor(root)) continue;
            int length = 0;
            worldChain[length++] = root;
            while (length > 0) {
                int index = worldChain[--length];
                GltfNode node = asset.nodes().get(index);
                writeWorld(pose, index, node);
                for (int i = 0; i < node.childCount(); i++) worldChain[length++] = node.child(i);
            }
        }
        dirtyTransforms.clear();
    }

    private boolean hasDirtyAncestor(int index) {
        for (int parent = asset.nodes().get(index).parent(); parent >= 0;
             parent = asset.nodes().get(parent).parent()) {
            if (dirtyTransforms.get(parent)) return true;
        }
        return false;
    }

    /** Visits nodes whose bounds can differ from the shared bind scene. */
    public void forEachMovedNode(AnimationController controller, IntConsumer visitor) {
        if (!transformOverrides.isEmpty()) {
            if (overrideSubtrees == null) overrideSubtrees = GltfAssetAccess.subtrees(asset, transformOverrides);
            visit(overrideSubtrees, visitor);
        }
        if (controller.poseRevision() != 0) visit(GltfAssetAccess.animatedSubtrees(asset), visitor);
    }

    private static void visit(BitSet indices, IntConsumer visitor) {
        for (int index = indices.nextSetBit(0); index >= 0; index = indices.nextSetBit(index + 1)) {
            visitor.accept(index);
        }
    }

    private void recomputeWorldMatrices(ModelPose pose) {
        Objects.requireNonNull(pose, "pose");
        if (pose.asset() != asset) {
            throw new IllegalArgumentException("Pose belongs to a different asset");
        }
        if (worldMatrices == null) {
            worldMatrices = new float[Math.multiplyExact(nodes.length, 16)];
        }
        if (worldState == null) {
            worldState = new byte[nodes.length];
        } else {
            Arrays.fill(worldState, (byte) 0);
        }
        if (worldChain == null) worldChain = new int[nodes.length];
        for (int start = 0; start < nodes.length; start++) {
            if (worldState[start] == 2) continue;
            int length = 0;
            int current = start;
            while (current >= 0 && worldState[current] != 2) {
                if (worldState[current] == 1) {
                    throw new IllegalArgumentException("Cycle in glTF node hierarchy");
                }
                worldState[current] = 1;
                worldChain[length++] = current;
                current = asset.nodes().get(current).parent();
            }
            for (int position = length - 1; position >= 0; position--) {
                int nodeIndex = worldChain[position];
                writeWorld(pose, nodeIndex, asset.nodes().get(nodeIndex));
                worldState[nodeIndex] = 2;
            }
        }
    }

    private void writeWorld(ModelPose pose, int index, GltfNode definition) {
        if (transformOverrides.get(index)) {
            nodes[index].resolveLocalMatrixInto(pose.node(index), worldLocalScratch, worldMultiplyScratch);
        } else if (definition.hasMatrix()) {
            definition.copyMatrixTo(worldLocalScratch);
        } else {
            var value = AnimationPoseAccess.nodeOrNull(pose, index);
            if (value == null) {
                definition.copyTranslationTo(bindTranslation);
                definition.copyRotationTo(bindRotation);
                definition.copyScaleTo(bindScale);
                PoseTransforms.trsMatrixInto(bindTranslation, bindRotation, bindScale, worldLocalScratch, 0);
            } else {
                PoseTransforms.trsMatrixInto(value.translation(), value.rotation(), value.scale(), worldLocalScratch, 0);
            }
        }
        int outputOffset = index * 16;
        int parent = definition.parent();
        if (parent < 0) System.arraycopy(worldLocalScratch, 0, worldMatrices, outputOffset, 16);
        else PoseTransforms.multiplyInto(worldMatrices, parent * 16, worldLocalScratch, 0, worldMatrices, outputOffset);
    }

    private static long poseSignature(ModelPose pose) {
        long result = 0xcbf29ce484222325L;
        for (int node = 0; node < pose.nodeCount(); node++) {
            var value = pose.node(node);
            for (float component : value.translation()) {
                result = (result ^ Float.floatToRawIntBits(component)) * 0x100000001b3L;
            }
            for (float component : value.rotation()) {
                result = (result ^ Float.floatToRawIntBits(component)) * 0x100000001b3L;
            }
            for (float component : value.scale()) {
                result = (result ^ Float.floatToRawIntBits(component)) * 0x100000001b3L;
            }
        }
        return result;
    }

    /** Returns the effective morph weights for rendering/collision, or null when absent. */
    public float[] resolveMorphWeights(int nodeIndex, GltfMesh mesh, ModelPose pose) {
        float[] weights = resolveMorphWeightsView(nodeIndex, mesh, pose);
        return weights == null ? null : weights.clone();
    }

    /** Allocation-free read-only morph-weight view for renderer/collider hot paths. */
    public float[] resolveMorphWeightsView(int nodeIndex, GltfMesh mesh, ModelPose pose) {
        Objects.checkIndex(nodeIndex, nodes.length);
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(pose, "pose");
        float[] defaults = GltfMeshAccess.defaultMorphWeights(mesh);
        var value = pose.node(nodeIndex);
        GltfNodeState state = nodes[nodeIndex];
        if (state != null) return state.resolveMorphWeights(value, defaults);
        return value.weights() == null ? defaults : value.weights();
    }

    /**
     * Allocation-free cached skin palette for a mesh node. The returned array is owned by this
     * instance and must be treated as read-only.
     */
    public float[] computeSkinPaletteView(GltfSkin skin, int meshNode, ModelPose pose,
                                          long poseRevision) {
        return computeSkinPaletteView(skin, meshNode, computeWorldMatricesView(pose, poseRevision));
    }

    /** Uses the already resolved matrix view from this manager without rechecking the pose. */
    public float[] computeSkinPaletteView(GltfSkin skin, int meshNode, float[] world) {
        Objects.checkIndex(meshNode, nodes.length);
        Objects.requireNonNull(skin, "skin");
        if (world != resolvedWorld || world == null) throw new IllegalArgumentException("Stale world view");
        SkinPalette palette = skinPalettes.get(meshNode);
        if (palette == null || palette.skin != skin) {
            palette = new SkinPalette(skin);
            skinPalettes.put(meshNode, palette);
        }
        if (palette.generation != worldGeneration) {
            PoseTransforms.computeSkinPaletteInto(skin, meshNode, world, palette.matrices);
            palette.generation = worldGeneration;
        }
        return palette.matrices;
    }

    /** Returns the current transform in model space, before the instance's placement. */
    public float[] modelMatrix(int index, AnimationController controller) {
        Objects.checkIndex(index, nodes.length);
        return slice(computeWorldMatricesView(controller), index);
    }

    /**
     * Places a node in model space by converting the supplied column-major matrix to parent space.
     * The resulting local override follows future movement of its parent.
     */
    public GltfNodeManager setModelMatrix(int index, AnimationController controller, float[] matrix) {
        GltfNodeState target = node(index);
        int parent = target.parentIndex();
        float[] world = computeWorldMatricesView(controller);
        float[] local = parent < 0 ? Objects.requireNonNull(matrix, "matrix")
            : PoseTransforms.multiply(PoseTransforms.invert(slice(world, parent)), matrix);
        target.localMatrix(local).postTransform(null);
        return this;
    }

    /** Checks runtime subtree visibility without materializing any node state. */
    public boolean isSubtreeVisible(int index) {
        if (hiddenSubtrees == 0) return true;
        for (int current = index; current >= 0; current = asset.nodes().get(current).parent()) {
            if (nodes[current] != null && !nodes[current].subtreeVisible()) return false;
        }
        return true;
    }

    public GltfNodeManager resetStates() {
        for (GltfNodeState node : nodes) {
            if (node != null) node.reset();
        }
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
        for (GltfNodeState node : all()) nodeSnapshots.add(node.snapshot());
        Map<GltfPrimitiveKey, PrimitiveSnapshot> primitiveSnapshots = new LinkedHashMap<>();
        for (GltfPrimitiveState primitive : allPrimitives()) {
            primitiveSnapshots.put(primitive.key(), primitive.snapshot());
        }
        return new Snapshot(asset, nodeSnapshots, primitiveSnapshots, variants);
    }

    /** Restores a snapshot captured from this exact immutable asset. */
    public GltfNodeManager restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.asset() != asset) {
            throw new IllegalArgumentException("Node snapshot belongs to a different glTF asset");
        }
        if (snapshot.nodes().size() != nodes.length) {
            throw new IllegalArgumentException("Node snapshot topology does not match the asset");
        }
        allPrimitives();
        if (!snapshot.primitives().keySet().equals(primitives.keySet())) {
            throw new IllegalArgumentException("Primitive snapshot topology does not match the asset");
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
        if (node.meshCount() == 0) return 0;
        GltfMesh mesh = asset.meshes().get(node.mesh(0));
        return mesh.primitives().isEmpty() ? 0 : mesh.primitives().get(0).morphTargets().size();
    }

    void resolveMaterial(GltfPrimitiveState state, GltfMaterialVariant variant) {
        setMaterial(state, variant, prepareMaterial(state, variant));
    }

    private GltfPrimitiveState.ResolvedMaterial prepareMaterial(GltfPrimitiveState state, GltfMaterialVariant variant) {
        Objects.requireNonNull(variant, "variant");
        validateVariant(variant);
        GltfPrimitiveState.ResolvedMaterial cached = state.cachedMaterial(variant);
        if (cached != null) return cached;
        int materialIndex = variant.materialIndex() == GltfMaterialVariant.PRIMITIVE_MATERIAL
            ? state.definition().material() : variant.materialIndex();
        GltfMaterial material = variant.override().resolve(asset.materials().get(materialIndex));
        validateMaterial(state.definition(), material, state.key());
        return new GltfPrimitiveState.ResolvedMaterial(materialIndex, material);
    }

    private void setMaterial(GltfPrimitiveState state, GltfMaterialVariant variant,
                             GltfPrimitiveState.ResolvedMaterial resolved) {
        state.resolvedMaterial(variant, resolved.index(), resolved.material());
        if (!variant.equals(GltfMaterialVariant.source())) hasMaterialOverrides = true;
    }

    void touch() { revision++; }
    void touchCollision() { revision++; collisionRevision++; }
    void transformChanged(int index, boolean overridden) {
        if (transformOverrides.get(index) != overridden) overrideSubtrees = null;
        transformOverrides.set(index, overridden);
        dirtyTransforms.set(index);
        transformRevision++;
        touchCollision();
    }

    void subtreeVisibilityChanged(boolean visible) {
        hiddenSubtrees += visible ? -1 : 1;
        touch();
    }

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
        validateTexture(primitive, new GltfTextureInfo(material.labPbr().normalTexture(),
            material.baseColorTexCoord()), "LabPBR normal", key);
        validateTexture(primitive, new GltfTextureInfo(material.labPbr().specularTexture(),
            material.baseColorTexCoord()), "LabPBR specular", key);
    }

    private void validateTexture(GltfPrimitive primitive, GltfTextureInfo texture,
                                 String label, GltfPrimitiveKey key) {
        if (texture.texture() < 0) return;
        if (texture.texture() >= asset.textures().size()) {
            throw new IndexOutOfBoundsException(label + " texture " + texture.texture());
        }
        boolean missing = texture.texCoord() == 0
            ? GltfPrimitiveAccess.texCoords0(primitive) == null : GltfPrimitiveAccess.texCoords1(primitive) == null;
        if (missing) {
            throw new IllegalArgumentException(key + " lacks TEXCOORD_" + texture.texCoord()
                + " required by the " + label + " texture");
        }
    }

    private void pushChildren(int nodeIndex, ArrayDeque<Integer> pending) {
        GltfNode node = asset.nodes().get(nodeIndex);
        for (int index = node.childCount() - 1; index >= 0; index--) pending.push(node.child(index));
    }

    private final class NodeList extends AbstractList<GltfNodeState> implements RandomAccess {
        @Override public GltfNodeState get(int index) { return node(index); }
        @Override public int size() { return nodes.length; }
    }

    private static final class SkinPalette {
        final GltfSkin skin;
        final float[] matrices;
        long generation = Long.MIN_VALUE;

        SkinPalette(GltfSkin skin) {
            this.skin = skin;
            matrices = new float[Math.multiplyExact(GltfSkinAccess.joints(skin).length, 16)];
        }
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
