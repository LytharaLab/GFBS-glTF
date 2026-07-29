package org.lytharalab.gfbs.gltf.collision;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

final class VoxelGrid {
    final int sizeX;
    final int sizeY;
    final int sizeZ;
    final float cell;
    final float originX;
    final float originY;
    final float originZ;
    private final BitSet occupied;

    VoxelGrid(int sizeX, int sizeY, int sizeZ, float cell,
              float originX, float originY, float originZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cell = cell;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        occupied = new BitSet(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ));
    }

    boolean get(int x, int y, int z) {
        return occupied.get(index(x, y, z));
    }

    void set(int x, int y, int z) {
        occupied.set(index(x, y, z));
    }

    void fillInterior() {
        BitSet exterior = new BitSet(sizeX * sizeY * sizeZ);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int z = 0; z < sizeZ; z++) {
            for (int y = 0; y < sizeY; y++) {
                seed(0, y, z, exterior, queue);
                seed(sizeX - 1, y, z, exterior, queue);
            }
        }
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                seed(x, 0, z, exterior, queue);
                seed(x, sizeY - 1, z, exterior, queue);
            }
        }
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                seed(x, y, 0, exterior, queue);
                seed(x, y, sizeZ - 1, exterior, queue);
            }
        }
        int strideZ = sizeX * sizeY;
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int z = current / strideZ;
            int remainder = current - z * strideZ;
            int y = remainder / sizeX;
            int x = remainder - y * sizeX;
            seed(x - 1, y, z, exterior, queue);
            seed(x + 1, y, z, exterior, queue);
            seed(x, y - 1, z, exterior, queue);
            seed(x, y + 1, z, exterior, queue);
            seed(x, y, z - 1, exterior, queue);
            seed(x, y, z + 1, exterior, queue);
        }
        int total = sizeX * sizeY * sizeZ;
        for (int index = 0; index < total; index++) {
            if (!exterior.get(index)) occupied.set(index);
        }
    }

    float[] greedyBoxes(float margin, int maximum) {
        BitSet consumed = new BitSet(sizeX * sizeY * sizeZ);
        List<Float> boxes = new ArrayList<>();
        int count = 0;
        for (int z = 0; z < sizeZ && count < maximum; z++) {
            for (int y = 0; y < sizeY && count < maximum; y++) {
                for (int x = 0; x < sizeX && count < maximum; x++) {
                    if (!available(x, y, z, consumed)) continue;
                    int endX = x + 1;
                    while (endX < sizeX && available(endX, y, z, consumed)) endX++;
                    int endY = y + 1;
                    while (endY < sizeY && rowAvailable(x, endX, endY, z, consumed)) endY++;
                    int endZ = z + 1;
                    while (endZ < sizeZ && planeAvailable(x, endX, y, endY, endZ, consumed)) endZ++;
                    for (int fillZ = z; fillZ < endZ; fillZ++) {
                        for (int fillY = y; fillY < endY; fillY++) {
                            for (int fillX = x; fillX < endX; fillX++) {
                                consumed.set(index(fillX, fillY, fillZ));
                            }
                        }
                    }
                    boxes.add(originX + x * cell - margin);
                    boxes.add(originY + y * cell - margin);
                    boxes.add(originZ + z * cell - margin);
                    boxes.add(originX + endX * cell + margin);
                    boxes.add(originY + endY * cell + margin);
                    boxes.add(originZ + endZ * cell + margin);
                    count++;
                }
            }
        }
        float[] result = new float[boxes.size()];
        for (int i = 0; i < result.length; i++) result[i] = boxes.get(i);
        return result;
    }

    private boolean rowAvailable(int minX, int maxX, int y, int z, BitSet consumed) {
        for (int x = minX; x < maxX; x++) {
            if (!available(x, y, z, consumed)) return false;
        }
        return true;
    }

    private boolean planeAvailable(int minX, int maxX, int minY, int maxY,
                                   int z, BitSet consumed) {
        if (z < 0 || z >= sizeZ || minY < 0 || maxY > sizeY) return false;
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                if (!available(x, y, z, consumed)) return false;
            }
        }
        return true;
    }

    private boolean available(int x, int y, int z, BitSet consumed) {
        int index = index(x, y, z);
        return occupied.get(index) && !consumed.get(index);
    }

    private void seed(int x, int y, int z, BitSet exterior, ArrayDeque<Integer> queue) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return;
        int index = index(x, y, z);
        if (occupied.get(index) || exterior.get(index)) return;
        exterior.set(index);
        queue.addLast(index);
    }

    private int index(int x, int y, int z) {
        return x + y * sizeX + z * sizeX * sizeY;
    }
}
