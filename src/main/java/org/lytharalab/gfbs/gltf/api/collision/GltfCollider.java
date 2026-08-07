package org.lytharalab.gfbs.gltf.api.collision;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lytharalab.gfbs.gltf.api.animation.ModelPose;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.client.node.GltfPrimitiveKey;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfBounds;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;
import org.lytharalab.gfbs.gltf.api.model.GltfSkin;
import org.lytharalab.gfbs.gltf.api.model.GltfSkinAccess;
import org.lytharalab.gfbs.gltf.api.model.MorphTarget;
import org.lytharalab.gfbs.gltf.api.model.MorphTargetAccess;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;
import org.lytharalab.gfbs.gltf.collision.GltfCollisionManager;
import org.lytharalab.gfbs.gltf.collision.GltfCollisionShape;
import org.lytharalab.gfbs.gltf.collision.TriangleVoxelizerAccess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-instance voxel collision. The caller supplies a model-to-world transform; animation, morph
 * targets and skinning are read from the instance.
 */
public final class GltfCollider implements ClippableSource, AutoCloseable {
    private final GltfInstance instance;
    private final CollisionOptions options = new CollisionOptions();
    private final Matrix4f modelToWorld = new Matrix4f();
    private final Map<GltfPrimitive, float[]> localBoxCache = new IdentityHashMap<>();
    private final AtomicBoolean building = new AtomicBoolean();
    private volatile GltfCollisionShape shape = GltfCollisionShape.EMPTY;
    private volatile Level level;
    private volatile long buildingSignature;
    private boolean registered;
    private boolean dirty = true;
    private long signature = Long.MIN_VALUE;
    private long selectionSignature = Long.MIN_VALUE;
    private boolean[] selectedNodes;
    private boolean[] hiddenNodes;

    public GltfCollider(GltfInstance instance) {
        this.instance = instance;
        selectedNodes = new boolean[instance.asset().nodes().size()];
        hiddenNodes = new boolean[selectedNodes.length];
    }

    public CollisionOptions options() { return options; }
    public GltfCollisionShape shape() { return shape; }
    public boolean isEnabled() { return options.enabled(); }

    public GltfCollider enable() {
        options.wholeModel().enabled(true);
        return enableInternal();
    }

    public GltfCollider enable(String... nodeGroups) {
        options.groups(nodeGroups).enabled(true);
        return enableInternal();
    }

    private GltfCollider enableInternal() {
        dirty = true;
        selectionSignature = Long.MIN_VALUE;
        if (!registered) {
            GltfCollisionManager.register(this);
            registered = true;
        }
        update();
        return this;
    }

    public GltfCollider disable() {
        options.enabled(false);
        shape = GltfCollisionShape.EMPTY;
        Arrays.fill(hiddenNodes, false);
        if (registered) {
            GltfCollisionManager.unregister(this);
            registered = false;
        }
        return this;
    }

    public GltfCollider attach(Level level) {
        this.level = level;
        return this;
    }

    public GltfCollider transform(Matrix4f transform) {
        modelToWorld.set(transform == null ? new Matrix4f() : transform);
        dirty = true;
        return this;
    }

    public Matrix4f transform() {
        return new Matrix4f(modelToWorld);
    }

    public GltfCollider markDirty() {
        dirty = true;
        return this;
    }

    public GltfCollider markSelectionDirty() {
        selectionSignature = Long.MIN_VALUE;
        dirty = true;
        return this;
    }

    public boolean isNodeHidden(int nodeIndex) {
        return nodeIndex >= 0 && nodeIndex < hiddenNodes.length && hiddenNodes[nodeIndex];
    }

    public void update() {
        if (!options.enabled()) {
            shape = GltfCollisionShape.EMPTY;
            return;
        }
        refreshSelection();
        ModelPose pose = instance.animations().pose();
        float[] world = instance.nodes().computeWorldMatricesView(
            pose, instance.animations().poseRevision()
        );
        long currentSignature = computeSignature(world, pose);
        if (!dirty && currentSignature == signature) return;
        signature = currentSignature;
        dirty = false;
        try {
            switch (options.mode()) {
                case BOUNDS -> shape = buildBounds(world);
                case FAST -> shape = buildFast(world);
                case PRECISE -> buildPrecise(world, pose, currentSignature);
            }
        } catch (RuntimeException failure) {
            shape = GltfCollisionShape.EMPTY;
        }
    }

    private void refreshSelection() {
        long current = options.groups().hashCode() * 31L
            + Boolean.hashCode(options.includeDescendants());
        if (selectionSignature == current) return;
        selectionSignature = current;
        Arrays.fill(selectedNodes, false);
        Arrays.fill(hiddenNodes, false);
        GltfAsset asset = instance.asset();
        Set<String> groups = options.groups();
        if (groups.isEmpty()) {
            Arrays.fill(selectedNodes, true);
        } else {
            for (int root : asset.scenes().get(instance.scene()).roots()) {
                selectRecursive(root, false, groups);
            }
            if (options.hideColliderNodes()) {
                System.arraycopy(selectedNodes, 0, hiddenNodes, 0, selectedNodes.length);
            }
        }
        localBoxCache.clear();
        dirty = true;
    }

    private void selectRecursive(int nodeIndex, boolean inherited, Set<String> groups) {
        GltfNode node = instance.asset().nodes().get(nodeIndex);
        boolean selected = groups.contains(node.name())
            || (inherited && options.includeDescendants());
        selectedNodes[nodeIndex] = selected;
        for (int child : node.children()) selectRecursive(child, selected, groups);
    }

    private GltfCollisionShape buildBounds(float[] world) {
        List<float[]> boxes = new ArrayList<>();
        GltfAsset asset = instance.asset();
        forEachPrimitive((nodeIndex, mesh, primitive) -> {
            Matrix4f transform = nodeTransform(world, nodeIndex);
            GltfBounds bounds = primitive.bounds().transform(transform);
            if (!bounds.valid()) return;
            float margin = options.margin();
            boxes.add(new float[]{
                bounds.minX() - margin, bounds.minY() - margin, bounds.minZ() - margin,
                bounds.maxX() + margin, bounds.maxY() + margin, bounds.maxZ() + margin
            });
        });
        return shapeOf(boxes, options.maxBoxes());
    }

    private GltfCollisionShape buildFast(float[] world) {
        List<float[]> boxes = new ArrayList<>();
        forEachPrimitive((nodeIndex, mesh, primitive) -> {
            if (!isTriangleMode(primitive.mode())) return;
            float[] local = localBoxCache.computeIfAbsent(primitive, this::voxelizeLocal);
            Matrix4f transform = nodeTransform(world, nodeIndex);
            for (int i = 0; i + 5 < local.length && boxes.size() < options.maxBoxes(); i += 6) {
                boxes.add(transformBox(transform, local, i, options.margin()));
            }
        });
        return shapeOf(boxes, options.maxBoxes());
    }

    private float[] voxelizeLocal(GltfPrimitive primitive) {
        float[] triangles = expandTriangles(primitive, GltfPrimitiveAccess.positions(primitive));
        return TriangleVoxelizerAccess.voxelize(
            triangles, options.precision(), options.maxVoxels(), options.solid(), 0.0f,
            options.maxBoxes()
        );
    }

    private void buildPrecise(float[] world, ModelPose pose, long expectedSignature) {
        List<float[]> chunks = new ArrayList<>();
        int[] size = new int[1];
        GltfAsset asset = instance.asset();
        forEachPrimitive((nodeIndex, mesh, primitive) -> {
            if (!isTriangleMode(primitive.mode())) return;
            float[] positions = GltfPrimitiveAccess.positions(primitive).clone();
            float[] morphWeights = instance.nodes().resolveMorphWeightsView(nodeIndex, mesh, pose);
            applyMorphs(primitive, morphWeights, positions);
            GltfNode node = asset.nodes().get(nodeIndex);
            int[] joints = GltfPrimitiveAccess.joints(primitive);
            float[] weights = GltfPrimitiveAccess.weights(primitive);
            if (node.skin() >= 0 && joints != null && weights != null) {
                GltfSkin skin = asset.skins().get(node.skin());
                float[] palette = instance.nodes().computeSkinPaletteView(
                    skin, nodeIndex, pose, instance.animations().poseRevision()
                );
                skinPositions(positions, joints, weights, palette, GltfSkinAccess.joints(skin).length);
            }
            float[] triangles = expandTriangles(primitive, positions);
            Matrix4f transform = nodeTransform(world, nodeIndex);
            transformPositions(triangles, transform);
            chunks.add(triangles);
            size[0] = Math.addExact(size[0], triangles.length);
        });
        if (size[0] < 9) {
            shape = GltfCollisionShape.EMPTY;
            return;
        }
        float[] positions = new float[size[0]];
        int offset = 0;
        for (float[] chunk : chunks) {
            System.arraycopy(chunk, 0, positions, offset, chunk.length);
            offset += chunk.length;
        }
        CollisionOptions snapshot = options.copy();
        Runnable build = () -> {
            float[] boxes = TriangleVoxelizerAccess.voxelize(
                positions, snapshot.precision(), snapshot.maxVoxels(), snapshot.solid(),
                snapshot.margin(), snapshot.maxBoxes()
            );
            if (buildingSignature == expectedSignature && options.enabled()) {
                shape = GltfCollisionShape.ofWorld(boxes, boxes.length / 6);
            } else {
                dirty = true;
            }
        };
        if (positions.length / 9 <= options.asyncTriangleThreshold()) {
            buildingSignature = expectedSignature;
            build.run();
            return;
        }
        buildingSignature = expectedSignature;
        if (!building.compareAndSet(false, true)) return;
        GltfCollisionManager.submit(() -> {
            try {
                build.run();
            } finally {
                building.set(false);
            }
        });
    }

    private long computeSignature(float[] world, ModelPose pose) {
        long result = options.signature();
        result = result * 31 + Long.hashCode(instance.nodes().collisionRevision());
        float[] transform = modelToWorld.get(new float[16]);
        for (float value : transform) result = result * 31 + Float.floatToIntBits(value);
        for (float value : world) result = result * 31 + Float.floatToIntBits(value);
        for (int node = 0; node < instance.asset().nodes().size(); node++) {
            for (int meshIndex : instance.asset().nodes().get(node).meshes()) {
                float[] weights = instance.nodes().resolveMorphWeightsView(
                    node, instance.asset().meshes().get(meshIndex), pose
                );
                if (weights != null) {
                    for (float weight : weights) result = result * 31 + Float.floatToIntBits(weight);
                }
            }
        }
        return result;
    }

    private Matrix4f nodeTransform(float[] world, int nodeIndex) {
        return new Matrix4f(modelToWorld).mul(new Matrix4f().set(world, nodeIndex * 16));
    }

    private void forEachPrimitive(PrimitiveConsumer consumer) {
        GltfAsset asset = instance.asset();
        for (int nodeIndex = 0; nodeIndex < asset.nodes().size(); nodeIndex++) {
            if (!selectedNodes[nodeIndex] || !instance.nodes().node(nodeIndex).collisionEnabled()) continue;
            GltfNode node = asset.nodes().get(nodeIndex);
            for (int meshIndex : node.meshes()) {
                GltfMesh mesh = asset.meshes().get(meshIndex);
                for (int primitiveIndex = 0; primitiveIndex < mesh.primitives().size(); primitiveIndex++) {
                    if (!instance.nodes().primitive(
                        new GltfPrimitiveKey(nodeIndex, meshIndex, primitiveIndex)
                    ).collisionEnabled()) continue;
                    GltfPrimitive primitive = mesh.primitives().get(primitiveIndex);
                    consumer.accept(nodeIndex, mesh, primitive);
                }
            }
        }
    }

    private static GltfCollisionShape shapeOf(List<float[]> boxes, int maximum) {
        int count = Math.min(boxes.size(), maximum);
        if (count == 0) return GltfCollisionShape.EMPTY;
        float[] flattened = new float[count * 6];
        for (int i = 0; i < count; i++) System.arraycopy(boxes.get(i), 0, flattened, i * 6, 6);
        return GltfCollisionShape.ofWorld(flattened, count);
    }

    private static float[] transformBox(Matrix4f matrix, float[] source, int offset, float margin) {
        GltfBounds bounds = new GltfBounds(
            source[offset], source[offset + 1], source[offset + 2],
            source[offset + 3], source[offset + 4], source[offset + 5]
        ).transform(matrix);
        return new float[]{
            bounds.minX() - margin, bounds.minY() - margin, bounds.minZ() - margin,
            bounds.maxX() + margin, bounds.maxY() + margin, bounds.maxZ() + margin
        };
    }

    private static float[] expandTriangles(GltfPrimitive primitive, float[] positions) {
        int[] indices = GltfPrimitiveAccess.indices(primitive);
        if (indices == null) {
            indices = new int[primitive.vertexCount()];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
        }
        List<Integer> triangles = new ArrayList<>();
        switch (primitive.mode()) {
            case TRIANGLES -> {
                for (int index : indices) triangles.add(index);
            }
            case TRIANGLE_STRIP -> {
                for (int i = 2; i < indices.length; i++) {
                    int a = indices[i - 2];
                    int b = indices[i - 1];
                    if ((i & 1) != 0) {
                        int swap = a;
                        a = b;
                        b = swap;
                    }
                    triangles.add(a);
                    triangles.add(b);
                    triangles.add(indices[i]);
                }
            }
            case TRIANGLE_FAN -> {
                for (int i = 2; i < indices.length; i++) {
                    triangles.add(indices[0]);
                    triangles.add(indices[i - 1]);
                    triangles.add(indices[i]);
                }
            }
            default -> {
                return new float[0];
            }
        }
        float[] expanded = new float[triangles.size() * 3];
        for (int i = 0; i < triangles.size(); i++) {
            int source = triangles.get(i) * 3;
            System.arraycopy(positions, source, expanded, i * 3, 3);
        }
        return expanded;
    }

    private static void applyMorphs(GltfPrimitive primitive, float[] weights, float[] positions) {
        for (int target = 0; target < primitive.morphTargets().size(); target++) {
            float weight = weights != null && target < weights.length ? weights[target] : 0.0f;
            if (weight == 0.0f) continue;
            MorphTarget morph = primitive.morphTargets().get(target);
            float[] delta = MorphTargetAccess.positions(morph);
            if (delta == null) continue;
            for (int i = 0; i < positions.length; i++) positions[i] += delta[i] * weight;
        }
    }

    private static void skinPositions(float[] positions, int[] joints, float[] weights,
                                      float[] palette, int jointCount) {
        for (int vertex = 0; vertex < positions.length / 3; vertex++) {
            int position = vertex * 3;
            int influences = vertex * 4;
            float x = positions[position];
            float y = positions[position + 1];
            float z = positions[position + 2];
            float outX = 0.0f;
            float outY = 0.0f;
            float outZ = 0.0f;
            float total = 0.0f;
            for (int influence = 0; influence < 4; influence++) {
                float weight = Math.max(0.0f, weights[influences + influence]);
                int joint = Math.max(0, Math.min(jointCount - 1, joints[influences + influence]));
                int matrix = joint * 16;
                outX += (palette[matrix] * x + palette[matrix + 4] * y
                    + palette[matrix + 8] * z + palette[matrix + 12]) * weight;
                outY += (palette[matrix + 1] * x + palette[matrix + 5] * y
                    + palette[matrix + 9] * z + palette[matrix + 13]) * weight;
                outZ += (palette[matrix + 2] * x + palette[matrix + 6] * y
                    + palette[matrix + 10] * z + palette[matrix + 14]) * weight;
                total += weight;
            }
            if (total > 1.0e-8f) {
                positions[position] = outX / total;
                positions[position + 1] = outY / total;
                positions[position + 2] = outZ / total;
            }
        }
    }

    private static void transformPositions(float[] positions, Matrix4f matrix) {
        Vector3f point = new Vector3f();
        for (int i = 0; i + 2 < positions.length; i += 3) {
            point.set(positions[i], positions[i + 1], positions[i + 2]);
            matrix.transformPosition(point);
            positions[i] = point.x;
            positions[i + 1] = point.y;
            positions[i + 2] = point.z;
        }
    }

    private static boolean isTriangleMode(PrimitiveMode mode) {
        return mode == PrimitiveMode.TRIANGLES
            || mode == PrimitiveMode.TRIANGLE_STRIP
            || mode == PrimitiveMode.TRIANGLE_FAN;
    }

    @Override
    public void collect(AABB area, List<VoxelShape> output) {
        shape.collect(area, output);
    }

    @Override
    public Level level() { return level; }

    @Override
    public boolean isActive() {
        return options.enabled() && !shape.isEmpty();
    }

    @Override
    public Vec3 clip(Vec3 from, Vec3 to) {
        return shape.clip(from, to);
    }

    @Override
    public void close() {
        disable();
        localBoxCache.clear();
    }

    @FunctionalInterface
    private interface PrimitiveConsumer {
        void accept(int nodeIndex, GltfMesh mesh, GltfPrimitive primitive);
    }
}
