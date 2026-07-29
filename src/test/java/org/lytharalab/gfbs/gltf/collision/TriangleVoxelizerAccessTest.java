package org.lytharalab.gfbs.gltf.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangleVoxelizerAccessTest {
    @Test
    void voxelizesAndHonorsBoxLimit() {
        float[] triangle = {
            0, 0, 0,
            1, 0, 0,
            0, 1, 0
        };
        float[] boxes = TriangleVoxelizerAccess.voxelize(
            triangle, 0.25f, 1_000_000, false, 0.01f, 8
        );
        assertTrue(boxes.length > 0);
        assertEquals(0, boxes.length % 6);
        assertTrue(boxes.length / 6 <= 8);
    }
}
