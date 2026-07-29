package org.lytharalab.gfbs.gltf.collision;

final class TriangleVoxelizer {
    private TriangleVoxelizer() {
    }

    static VoxelGrid createGrid(float[] positions, float requestedCell, long maximumVoxels) {
        if (positions == null || positions.length < 9) return null;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i + 2 < positions.length; i += 3) {
            float x = positions[i];
            float y = positions[i + 1];
            float z = positions[i + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) continue;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        if (minX > maxX) return null;
        float cell = Math.max(1.0e-4f, requestedCell);
        while (true) {
            float originX = (float) Math.floor(minX / cell) * cell - cell;
            float originY = (float) Math.floor(minY / cell) * cell - cell;
            float originZ = (float) Math.floor(minZ / cell) * cell - cell;
            int sizeX = Math.max(3, (int) Math.ceil((maxX - originX) / cell) + 2);
            int sizeY = Math.max(3, (int) Math.ceil((maxY - originY) / cell) + 2);
            int sizeZ = Math.max(3, (int) Math.ceil((maxZ - originZ) / cell) + 2);
            long total = (long) sizeX * sizeY * sizeZ;
            if (total <= maximumVoxels && total <= Integer.MAX_VALUE) {
                return new VoxelGrid(sizeX, sizeY, sizeZ, cell, originX, originY, originZ);
            }
            cell *= 2.0f;
        }
    }

    static void rasterize(VoxelGrid grid, float[] positions) {
        float cell = grid.cell;
        float half = cell * 0.5f;
        for (int triangle = 0; triangle + 8 < positions.length; triangle += 9) {
            float ax = positions[triangle];
            float ay = positions[triangle + 1];
            float az = positions[triangle + 2];
            float bx = positions[triangle + 3];
            float by = positions[triangle + 4];
            float bz = positions[triangle + 5];
            float cx = positions[triangle + 6];
            float cy = positions[triangle + 7];
            float cz = positions[triangle + 8];
            int minX = clamp((int) Math.floor(
                (Math.min(ax, Math.min(bx, cx)) - grid.originX) / cell
            ), 0, grid.sizeX - 1);
            int minY = clamp((int) Math.floor(
                (Math.min(ay, Math.min(by, cy)) - grid.originY) / cell
            ), 0, grid.sizeY - 1);
            int minZ = clamp((int) Math.floor(
                (Math.min(az, Math.min(bz, cz)) - grid.originZ) / cell
            ), 0, grid.sizeZ - 1);
            int maxX = clamp((int) Math.ceil(
                (Math.max(ax, Math.max(bx, cx)) - grid.originX) / cell
            ) - 1, 0, grid.sizeX - 1);
            int maxY = clamp((int) Math.ceil(
                (Math.max(ay, Math.max(by, cy)) - grid.originY) / cell
            ) - 1, 0, grid.sizeY - 1);
            int maxZ = clamp((int) Math.ceil(
                (Math.max(az, Math.max(bz, cz)) - grid.originZ) / cell
            ) - 1, 0, grid.sizeZ - 1);
            if (maxX < minX) maxX = minX;
            if (maxY < minY) maxY = minY;
            if (maxZ < minZ) maxZ = minZ;
            for (int z = minZ; z <= maxZ; z++) {
                float centerZ = grid.originZ + z * cell + half;
                for (int y = minY; y <= maxY; y++) {
                    float centerY = grid.originY + y * cell + half;
                    for (int x = minX; x <= maxX; x++) {
                        if (grid.get(x, y, z)) continue;
                        float centerX = grid.originX + x * cell + half;
                        if (triangleBoxOverlap(
                            centerX, centerY, centerZ, half,
                            ax, ay, az, bx, by, bz, cx, cy, cz
                        )) {
                            grid.set(x, y, z);
                        }
                    }
                }
            }
        }
    }

    private static boolean triangleBoxOverlap(float centerX, float centerY, float centerZ,
                                              float half, float ax, float ay, float az,
                                              float bx, float by, float bz,
                                              float cx, float cy, float cz) {
        float v0x = ax - centerX;
        float v0y = ay - centerY;
        float v0z = az - centerZ;
        float v1x = bx - centerX;
        float v1y = by - centerY;
        float v1z = bz - centerZ;
        float v2x = cx - centerX;
        float v2y = cy - centerY;
        float v2z = cz - centerZ;
        float e0x = v1x - v0x;
        float e0y = v1y - v0y;
        float e0z = v1z - v0z;
        float e1x = v2x - v1x;
        float e1y = v2y - v1y;
        float e1z = v2z - v1z;
        float e2x = v0x - v2x;
        float e2y = v0y - v2y;
        float e2z = v0z - v2z;
        if (separatingAxes(e0x, e0y, e0z, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, half)) return false;
        if (separatingAxes(e1x, e1y, e1z, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, half)) return false;
        if (separatingAxes(e2x, e2y, e2z, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, half)) return false;
        if (Math.min(v0x, Math.min(v1x, v2x)) > half
            || Math.max(v0x, Math.max(v1x, v2x)) < -half) return false;
        if (Math.min(v0y, Math.min(v1y, v2y)) > half
            || Math.max(v0y, Math.max(v1y, v2y)) < -half) return false;
        if (Math.min(v0z, Math.min(v1z, v2z)) > half
            || Math.max(v0z, Math.max(v1z, v2z)) < -half) return false;
        float normalX = e0y * e1z - e0z * e1y;
        float normalY = e0z * e1x - e0x * e1z;
        float normalZ = e0x * e1y - e0y * e1x;
        float distance = -(normalX * v0x + normalY * v0y + normalZ * v0z);
        float radius = half * (Math.abs(normalX) + Math.abs(normalY) + Math.abs(normalZ));
        return Math.abs(distance) <= radius;
    }

    private static boolean separatingAxes(float edgeX, float edgeY, float edgeZ,
                                          float v0x, float v0y, float v0z,
                                          float v1x, float v1y, float v1z,
                                          float v2x, float v2y, float v2z,
                                          float half) {
        float[][] axes = {
            {0.0f, -edgeZ, edgeY},
            {edgeZ, 0.0f, -edgeX},
            {-edgeY, edgeX, 0.0f}
        };
        for (float[] axis : axes) {
            float p0 = axis[0] * v0x + axis[1] * v0y + axis[2] * v0z;
            float p1 = axis[0] * v1x + axis[1] * v1y + axis[2] * v1z;
            float p2 = axis[0] * v2x + axis[1] * v2y + axis[2] * v2z;
            float minimum = Math.min(p0, Math.min(p1, p2));
            float maximum = Math.max(p0, Math.max(p1, p2));
            float radius = half * (Math.abs(axis[0]) + Math.abs(axis[1]) + Math.abs(axis[2]));
            if (minimum > radius || maximum < -radius) return true;
        }
        return false;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
