package org.lytharalab.gfbs.gltf.client.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lytharalab.gfbs.gltf.api.model.GltfBounds;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitiveAccess;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

import java.util.ArrayList;
import java.util.List;

/** Spatial sections for oversized rigid triangle primitives. Vertex data stays shared. */
final class GltfPrimitiveClusters {
    private static final int MIN_TRIANGLES = 16_384;
    private static final int TARGET_TRIANGLES = 2_048;
    private static final int MAX_SECTION_TRIANGLES = 8_192;
    private static final Section[] WHOLE = {null};

    private GltfPrimitiveClusters() {}

    static Section[] whole() { return WHOLE; }

    static boolean eligible(GltfPrimitive primitive) {
        return primitive.mode() == PrimitiveMode.TRIANGLES
            && primitive.indexCount() / 3 >= MIN_TRIANGLES && primitive.bounds().valid();
    }

    static Section[] build(GltfPrimitive primitive) {
        GltfBounds bounds = primitive.bounds();
        double dx = (double) bounds.maxX() - bounds.minX();
        double dy = (double) bounds.maxY() - bounds.minY();
        double dz = (double) bounds.maxZ() - bounds.minZ();
        double extent = Math.max(dx, Math.max(dy, dz));
        if (!(extent > 0 && Double.isFinite(extent))) return WHOLE;
        int axes = (dx >= extent * 0.05 ? 1 : 0) + (dy >= extent * 0.05 ? 1 : 0)
            + (dz >= extent * 0.05 ? 1 : 0);
        int side = Math.min(128, (int) Math.ceil(Math.pow(
            primitive.indexCount() / (3.0 * TARGET_TRIANGLES), 1.0 / axes)));
        int nx = Math.max(1, (int) Math.ceil(dx / extent * side));
        int ny = Math.max(1, (int) Math.ceil(dy / extent * side));
        int nz = Math.max(1, (int) Math.ceil(dz / extent * side));

        float[] positions = GltfPrimitiveAccess.positions(primitive);
        int[] indices = GltfPrimitiveAccess.indices(primitive);
        Int2ObjectAVLTreeMap<IntArrayList> cells = new Int2ObjectAVLTreeMap<>();
        for (int triangle = 0; triangle + 2 < primitive.indexCount(); triangle += 3) {
            int a = vertex(indices, triangle) * 3;
            int b = vertex(indices, triangle + 1) * 3;
            int c = vertex(indices, triangle + 2) * 3;
            int x = cell(center(positions, a, b, c), bounds.minX(), dx, nx);
            int y = cell(center(positions, a + 1, b + 1, c + 1), bounds.minY(), dy, ny);
            int z = cell(center(positions, a + 2, b + 2, c + 2), bounds.minZ(), dz, nz);
            cells.computeIfAbsent((z * ny + y) * nx + x, ignored -> new IntArrayList()).add(triangle);
        }
        if (cells.size() == 1) return WHOLE;

        List<Section> result = new ArrayList<>(cells.size());
        for (IntArrayList cell : cells.values()) {
            int[] triangles = cell.toIntArray();
            for (int start = 0; start < triangles.length; start += MAX_SECTION_TRIANGLES) {
                int end = Math.min(triangles.length, start + MAX_SECTION_TRIANGLES);
                result.add(new Section(triangles, start, end, bounds(positions, indices, triangles, start, end)));
            }
        }
        return result.toArray(Section[]::new);
    }

    private static int vertex(int[] indices, int offset) { return indices == null ? offset : indices[offset]; }

    private static double center(float[] positions, int a, int b, int c) {
        return ((double) positions[a] + positions[b] + positions[c]) / 3;
    }

    private static int cell(double center, float min, double extent, int count) {
        if (count == 1) return 0;
        return Math.max(0, Math.min(count - 1, (int) ((center - min) / extent * count)));
    }

    private static GltfBounds bounds(float[] positions, int[] indices, int[] triangles, int start, int end) {
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = start; i < end; i++) {
            for (int corner = 0; corner < 3; corner++) {
                int offset = vertex(indices, triangles[i] + corner) * 3;
                float x = positions[offset], y = positions[offset + 1], z = positions[offset + 2];
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
        }
        return new GltfBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    record Section(int[] triangles, int start, int end, GltfBounds bounds) {
        int vertices() { return (end - start) * 3; }
    }
}
