package org.lytharalab.gfbs.gltf.client.render;

import org.joml.Matrix4f;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderContext;
import org.lytharalab.gfbs.gltf.api.client.GltfRenderOptions;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.model.GltfBounds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Shared spatial index of bind-pose scene nodes. Deformable nodes are always candidates. */
final class GltfSceneIndex {
    private static final int LEAF_SIZE = 8;
    private final Entry[] entries;
    private final int[] unbounded;
    private final int[] nodeToPlan;
    private final Branch root;

    GltfSceneIndex(GltfGpuModel.NodePlan[] nodes, float[] world) {
        List<Entry> rigid = new ArrayList<>();
        int[] fallback = new int[nodes.length];
        nodeToPlan = new int[world.length / 16];
        Arrays.fill(nodeToPlan, -1);
        int count = 0;
        Matrix4f transform = new Matrix4f();
        for (int i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (node.primitives.length == 0) continue;
            nodeToPlan[node.nodeIndex] = i;
            GltfBounds local = GltfBounds.EMPTY;
            for (var part : node.primitives) {
                if (part.primitive().hasDynamicGeometry() || !part.primitive().bounds().valid()) {
                    local = GltfBounds.EMPTY;
                    break;
                }
                local = local.union(part.primitive().bounds());
            }
            if (local.valid()) {
                transform.set(world, node.nodeIndex * 16);
                GltfBounds bounds = local.transform(transform);
                if (finite(bounds)) {
                    rigid.add(new Entry(i, bounds));
                    continue;
                }
            }
            fallback[count++] = i;
        }
        entries = rigid.toArray(Entry[]::new);
        unbounded = Arrays.copyOf(fallback, count);
        root = entries.length == 0 ? null : build(0, entries.length);
    }

    private static boolean finite(GltfBounds bounds) {
        return Float.isFinite(bounds.minX()) && Float.isFinite(bounds.maxX())
            && Float.isFinite(bounds.minY()) && Float.isFinite(bounds.maxY())
            && Float.isFinite(bounds.minZ()) && Float.isFinite(bounds.maxZ());
    }

    private Branch build(int start, int end) {
        GltfBounds bounds = entries[start].bounds();
        for (int i = start + 1; i < end; i++) bounds = bounds.union(entries[i].bounds());
        if (end - start <= LEAF_SIZE) return new Branch(bounds, start, end, null, null);

        float x = bounds.maxX() - bounds.minX();
        float y = bounds.maxY() - bounds.minY();
        float z = bounds.maxZ() - bounds.minZ();
        Comparator<Entry> order = x >= y && x >= z
            ? Comparator.comparingDouble(e -> e.bounds().centerX())
            : y >= z ? Comparator.comparingDouble(e -> e.bounds().centerY())
            : Comparator.comparingDouble(e -> e.bounds().centerZ());
        Arrays.sort(entries, start, end, order);
        int middle = (start + end) >>> 1;
        return new Branch(bounds, start, end, build(start, middle), build(middle, end));
    }

    void select(GltfInstance instance, Matrix4f placement, GltfRenderContext context,
                GltfRenderOptions options, Selection result) {
        result.size = 0;
        if (root != null) select(root, placement, context, options, result);
        for (int index : unbounded) result.add(index);
        instance.nodes().forEachMovedNode(instance.animations(), node -> {
            int index = nodeToPlan[node];
            if (index >= 0) result.add(index);
        });
        // Keep authored order for callbacks, render types, and equal-depth transparent parts.
        Arrays.sort(result.indices, 0, result.size);
        result.removeDuplicates();
    }

    private void select(Branch branch, Matrix4f placement, GltfRenderContext context,
                        GltfRenderOptions options, Selection result) {
        if (!GltfPrimitiveCuller.isVisible(branch.bounds(), placement, context, options)) return;
        if (branch.left() != null) {
            select(branch.left(), placement, context, options, result);
            select(branch.right(), placement, context, options, result);
            return;
        }
        for (int i = branch.start(); i < branch.end(); i++) {
            Entry entry = entries[i];
            if (GltfPrimitiveCuller.isVisible(entry.bounds(), placement, context, options)) {
                result.add(entry.nodeIndex());
            }
        }
    }

    static final class Selection {
        private int[] indices = new int[64];
        private int size;

        int size() { return size; }
        int get(int index) { return indices[index]; }

        private void add(int index) {
            if (size == indices.length) indices = Arrays.copyOf(indices, size * 2);
            indices[size++] = index;
        }

        private void removeDuplicates() {
            int write = 0;
            for (int read = 0; read < size; read++) {
                if (write == 0 || indices[read] != indices[write - 1]) indices[write++] = indices[read];
            }
            size = write;
        }
    }

    private record Entry(int nodeIndex, GltfBounds bounds) {}
    private record Branch(GltfBounds bounds, int start, int end, Branch left, Branch right) {}
}
