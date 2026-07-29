package org.lytharalab.gfbs.gltf.collision;

/**
 * Narrow bridge used by the public collider without exposing the mutable voxel grid.
 */
public final class TriangleVoxelizerAccess {
    private TriangleVoxelizerAccess() {
    }

    public static float[] voxelize(float[] triangles, float precision, long maximumVoxels,
                                   boolean solid, float margin, int maximumBoxes) {
        VoxelGrid grid = TriangleVoxelizer.createGrid(triangles, precision, maximumVoxels);
        if (grid == null) return new float[0];
        TriangleVoxelizer.rasterize(grid, triangles);
        if (solid) grid.fillInterior();
        return grid.greedyBoxes(margin, maximumBoxes);
    }
}
