package org.lytharalab.gfbs.gltf.core.io;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfMesh;
import org.lytharalab.gfbs.gltf.api.model.GltfNode;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfScene;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GltfAssetValidationTest {
    @Test
    void rejectsEveryOutOfRangePbrTextureReference() {
        GltfMaterial material = new GltfMaterial(
            "invalid-normal",
            null,
            -1,
            0,
            0.0f,
            1.0f,
            -1,
            0,
            1,
            0,
            1.0f,
            -1,
            0,
            1.0f,
            null,
            -1,
            0,
            AlphaMode.OPAQUE,
            0.5f,
            false
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> asset(material, texturedPrimitive(), List.of(dummyTexture()))
        );
    }

    @Test
    void rejectsAReferencedTextureWithoutItsUvSet() {
        GltfMaterial material = new GltfMaterial(
            "missing-uv",
            null,
            0,
            1,
            null,
            -1,
            0,
            AlphaMode.OPAQUE,
            0.5f,
            false
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> asset(material, texturedPrimitive(), List.of(dummyTexture()))
        );
    }

    private static GltfAsset asset(
        GltfMaterial material,
        GltfPrimitive primitive,
        List<GltfTexture> textures
    ) {
        GltfNode node = new GltfNode(
            "root",
            -1,
            new int[0],
            new int[]{0},
            -1,
            null,
            null,
            null,
            null,
            null
        );
        return new GltfAsset(
            ResourceLocation.fromNamespaceAndPath("gfbs_gltf", "validation_test"),
            List.of(new GltfScene("scene", new int[]{0})),
            List.of(node),
            List.of(new GltfMesh("mesh", List.of(primitive), null)),
            List.of(material),
            textures,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static GltfPrimitive texturedPrimitive() {
        return new GltfPrimitive(
            PrimitiveMode.TRIANGLES,
            0,
            3,
            new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
            null,
            null,
            new float[]{0, 0, 1, 0, 0, 1},
            null,
            null,
            null,
            null,
            new int[]{0, 1, 2},
            List.of()
        );
    }

    private static GltfTexture dummyTexture() {
        return new GltfTexture(
            "dummy",
            "application/octet-stream",
            ByteBuffer.wrap(new byte[]{1}),
            9729,
            9729,
            10497,
            10497
        );
    }
}
