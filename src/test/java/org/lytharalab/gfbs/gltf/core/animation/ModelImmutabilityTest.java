package org.lytharalab.gfbs.gltf.core.animation;

import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelImmutabilityTest {
    @Test
    void publicModelArraysAreDefensivelyCopied() {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        int[] indices = {0, 1, 2};
        GltfPrimitive primitive = new GltfPrimitive(PrimitiveMode.TRIANGLES, 0, 3,
            positions, null, null, null, null, null, null, null, indices, List.of());
        positions[0] = 99;
        indices[0] = 2;
        float[] exposedPositions = primitive.positions();
        int[] exposedIndices = primitive.indices();
        exposedPositions[1] = 99;
        exposedIndices[1] = 2;
        assertArrayEquals(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, primitive.positions());
        assertArrayEquals(new int[]{0, 1, 2}, primitive.indices());

        GltfScene scene = new GltfScene("scene", new int[]{0});
        int[] roots = scene.roots();
        roots[0] = 7;
        assertArrayEquals(new int[]{0}, scene.roots());
    }

    @Test
    void rejectsStructurallyInvalidModelObjectsEarly() {
        assertThrows(IllegalArgumentException.class, () -> new GltfMesh("empty", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new MorphTarget(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new org.lytharalab.gfbs.gltf.api.animation.AnimationClip("empty", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GltfMaterial("bad", new float[]{2, 1, 1, 1},
            -1, 0, null, -1, 0, AlphaMode.OPAQUE, 0.5f, false));
        assertThrows(IllegalArgumentException.class, () -> new GltfMaterial("bad", null,
            -1, 0, null, -1, 0, AlphaMode.MASK, Float.NaN, false));
    }

}
