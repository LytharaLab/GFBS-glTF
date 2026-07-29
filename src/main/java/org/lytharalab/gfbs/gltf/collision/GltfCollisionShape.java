package org.lytharalab.gfbs.gltf.collision;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GltfCollisionShape {
    public static final GltfCollisionShape EMPTY = new GltfCollisionShape(new double[0], 0);
    private static final double CELL = 1.0;
    private final double[] boxes;
    private final int count;
    private final VoxelShape[] shapes;
    private final Map<Long, int[]> grid;
    private final AABB bounds;

    private GltfCollisionShape(double[] boxes, int count) {
        this.boxes = boxes;
        this.count = count;
        shapes = new VoxelShape[count];
        Map<Long, List<Integer>> cells = new HashMap<>();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < count; index++) {
            int offset = index * 6;
            AABB box = new AABB(
                boxes[offset], boxes[offset + 1], boxes[offset + 2],
                boxes[offset + 3], boxes[offset + 4], boxes[offset + 5]
            );
            shapes[index] = Shapes.create(box);
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
            for (int z = cell(box.minZ); z <= cell(box.maxZ); z++) {
                for (int y = cell(box.minY); y <= cell(box.maxY); y++) {
                    for (int x = cell(box.minX); x <= cell(box.maxX); x++) {
                        cells.computeIfAbsent(key(x, y, z), ignored -> new ArrayList<>()).add(index);
                    }
                }
            }
        }
        grid = new HashMap<>(cells.size());
        for (Map.Entry<Long, List<Integer>> entry : cells.entrySet()) {
            grid.put(entry.getKey(), entry.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        bounds = count == 0 ? new AABB(0, 0, 0, 0, 0, 0)
            : new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static GltfCollisionShape ofWorld(float[] worldBoxes, int count) {
        if (count <= 0) return EMPTY;
        double[] boxes = new double[count * 6];
        for (int i = 0; i < boxes.length; i++) boxes[i] = worldBoxes[i];
        return new GltfCollisionShape(boxes, count);
    }

    public int boxCount() { return count; }
    public boolean isEmpty() { return count == 0; }

    public void collect(AABB area, List<VoxelShape> output) {
        if (count == 0 || !bounds.intersects(area)) return;
        int minX = cell(area.minX);
        int minY = cell(area.minY);
        int minZ = cell(area.minZ);
        int maxX = cell(area.maxX);
        int maxY = cell(area.maxY);
        int maxZ = cell(area.maxZ);
        long cellCount = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cellCount > count) {
            for (int i = 0; i < count; i++) if (overlaps(i, area)) output.add(shapes[i]);
            return;
        }
        BitSet seen = new BitSet(count);
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int[] indices = grid.get(key(x, y, z));
                    if (indices == null) continue;
                    for (int index : indices) {
                        if (seen.get(index)) continue;
                        seen.set(index);
                        if (overlaps(index, area)) output.add(shapes[index]);
                    }
                }
            }
        }
    }

    public Vec3 clip(Vec3 from, Vec3 to) {
        if (count == 0 || !bounds.intersects(new AABB(from, to))) return null;
        Vec3 closest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < count; index++) {
            int offset = index * 6;
            AABB box = new AABB(
                boxes[offset], boxes[offset + 1], boxes[offset + 2],
                boxes[offset + 3], boxes[offset + 4], boxes[offset + 5]
            );
            var hit = box.clip(from, to);
            if (hit.isEmpty()) continue;
            double candidate = from.distanceToSqr(hit.get());
            if (candidate < distance) {
                distance = candidate;
                closest = hit.get();
            }
        }
        return closest;
    }

    private boolean overlaps(int index, AABB area) {
        int offset = index * 6;
        return area.maxX > boxes[offset] && area.minX < boxes[offset + 3]
            && area.maxY > boxes[offset + 1] && area.minY < boxes[offset + 4]
            && area.maxZ > boxes[offset + 2] && area.minZ < boxes[offset + 5];
    }

    private static int cell(double value) { return (int) Math.floor(value / CELL); }
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1fffff) << 42)
            | ((long) (y & 0x1fffff) << 21)
            | (z & 0x1fffff);
    }
}
