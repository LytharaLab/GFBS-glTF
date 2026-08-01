package org.lytharalab.gfbs.gltf.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GltfTextureInfoTest {
    @Test
    void appliesKhrTextureTransformInSpecificationOrder() {
        GltfTextureInfo info = new GltfTextureInfo(
            3, 1, 0.25f, -0.5f, 2.0f, 3.0f, (float) (Math.PI * 0.5)
        );
        float[] output = new float[2];
        info.transform(0.5f, 0.25f, output);
        assertArrayEquals(new float[]{-0.5f, 0.5f}, output, 1.0e-5f);
        assertTrue(info.present());
        assertTrue(info.transformed());
    }

    @Test
    void validatesTextureCoordinatesAndFiniteTransforms() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new GltfTextureInfo(0, 2)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new GltfTextureInfo(0, 0, 0, 0, Float.NaN, 1, 0)
        );
        assertFalse(GltfTextureInfo.absent().present());
        assertFalse(GltfTextureInfo.absent().transformed());
    }
}
