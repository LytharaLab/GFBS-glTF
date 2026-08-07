package org.lytharalab.gfbs.gltf.client.render;

import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.MorphTarget;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GltfVertexTransformsTest {
    @Test
    void preparesPositionNormalAndTangentMorphsWithoutMutatingTheAsset() {
        float[] positions = {
            0, 0, 0,
            1, 0, 0,
            0, 1, 0
        };
        float[] normals = {
            0, 0, 1,
            0, 0, 1,
            0, 0, 1
        };
        float[] tangents = {
            1, 0, 0, 1,
            1, 0, 0, 1,
            1, 0, 0, 1
        };
        MorphTarget target = new MorphTarget(
            new float[]{0, 0, 2, 0, 0, 0, 0, 0, 0},
            new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0},
            new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0}
        );
        GltfPrimitive primitive = new GltfPrimitive(
            PrimitiveMode.TRIANGLES, 0, 3,
            positions, normals, tangents, null, null, null,
            null, null, new int[]{0, 1, 2}, List.of(target)
        );

        GltfVertexTransforms.PreparedGeometry prepared =
            GltfVertexTransforms.prepare(primitive, new float[]{0.5f});

        assertEquals(1.0f, prepared.positions()[2], 1.0e-6f);
        assertEquals((float) (1.0 / Math.sqrt(1.25)), prepared.normals()[2], 1.0e-6f);
        assertEquals((float) (0.5 / Math.sqrt(1.25)), prepared.tangents()[1], 1.0e-6f);
        assertArrayEquals(positions, primitive.positions());
        assertArrayEquals(normals, primitive.normals());
        assertArrayEquals(tangents, primitive.tangents());
    }

    @Test
    void zeroMorphPrepareStillReturnsWritableCopies() {
        GltfPrimitive primitive = new GltfPrimitive(
            PrimitiveMode.TRIANGLES, 0, 3,
            new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
            new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1},
            null, null, null, null, null, null, new int[]{0, 1, 2}, List.of()
        );

        GltfVertexTransforms.PreparedGeometry prepared =
            GltfVertexTransforms.prepare(primitive, null);
        prepared.positions()[0] = 99.0f;
        prepared.normals()[0] = 99.0f;

        assertEquals(0.0f, primitive.positions()[0]);
        assertEquals(0.0f, primitive.normals()[0]);
    }

    @Test
    void zeroSkinWeightsPreserveTheOriginalVertex() {
        float[] output = new float[6];
        GltfVertexTransforms.skinVertex(
            0, 1, 2, 3, 0, 1, 0,
            new int[]{0, 0, 0, 0},
            new float[]{0, 0, 0, 0},
            new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
            },
            1,
            output
        );

        assertArrayEquals(new float[]{1, 2, 3, 0, 1, 0}, output);
    }

    @Test
    void computesAStableTriangleNormal() {
        assertArrayEquals(
            new float[]{0, 0, 1},
            GltfVertexTransforms.faceNormal(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                0, 1, 2
            ),
            1.0e-6f
        );
    }
}
