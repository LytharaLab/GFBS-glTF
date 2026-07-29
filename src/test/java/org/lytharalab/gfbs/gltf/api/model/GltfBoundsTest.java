package org.lytharalab.gfbs.gltf.api.model;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfBoundsTest {
    @Test
    void computesAndTransformsBounds() {
        GltfBounds bounds = GltfBounds.ofPositions(new float[]{
            -1, -2, -3,
            4, 5, 6
        });
        GltfBounds transformed = bounds.transform(new Matrix4f().translate(10, 20, 30).scale(2));
        assertTrue(transformed.valid());
        assertEquals(8.0f, transformed.minX(), 1.0e-5f);
        assertEquals(30.0f, transformed.maxY(), 1.0e-5f);
        assertEquals(42.0f, transformed.maxZ(), 1.0e-5f);
    }

    @Test
    void emptyBoundsStayEmpty() {
        assertFalse(GltfBounds.EMPTY.transform(new Matrix4f()).valid());
    }
}
