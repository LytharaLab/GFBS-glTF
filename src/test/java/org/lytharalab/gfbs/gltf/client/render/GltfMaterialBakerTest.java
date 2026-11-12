package org.lytharalab.gfbs.gltf.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialOverride;
import org.lytharalab.gfbs.gltf.api.client.material.GltfPbrBakeOptions;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfPrimitive;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;
import org.lytharalab.gfbs.gltf.api.model.GltfTexture;
import org.lytharalab.gfbs.gltf.api.model.PrimitiveMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfMaterialBakerTest {
    @Test
    void bakesAnIndependentEmissiveTextureIntoTheSpecularAlphaChannel() throws IOException {
        GltfTexture base = texture(new int[]{0xff202020, 0xff202020, 0xff202020, 0xff202020});
        GltfTexture emissive = texture(new int[]{0xff000000, 0xffffffff, 0xff000000, 0xffffffff});
        GltfMaterial material = GltfMaterialOverride.builder()
            .baseColorTexture(new GltfTextureInfo(0, 0))
            .emissiveTexture(new GltfTextureInfo(1, 0))
            .emissive(1.0f, 1.0f, 1.0f)
            .build()
            .resolve(GltfMaterial.defaultMaterial());
        GltfPrimitive primitive = new GltfPrimitive(
            PrimitiveMode.TRIANGLES, 0, 3,
            new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
            null, null,
            new float[]{0, 0, 1, 0, 0, 1},
            null, null, null, null, new int[]{0, 1, 2}, List.of()
        );

        GltfMaterialBaker.Material baked = new GltfMaterialBaker(List.of(base, emissive))
            .bake(primitive, material, GltfPbrBakeOptions.DEFAULT, true, true);
        try (NativeImage specular = NativeImage.read(baked.pages().get(0).images[2])) {
            int[] pixels = specular.makePixelArray();
            assertArrayEquals(new int[]{255, 254, 255, 254},
                new int[]{pixels[0] >>> 24, pixels[1] >>> 24, pixels[2] >>> 24, pixels[3] >>> 24});
        }
    }

    @Test
    void optInEmissionRecognizesADifferentEmissiveTexture() {
        GltfMaterial material = GltfMaterialOverride.builder()
            .baseColorTexture(new GltfTextureInfo(0, 0))
            .emissiveTexture(new GltfTextureInfo(1, 0))
            .emissive(1.0f, 1.0f, 1.0f)
            .build()
            .resolve(GltfMaterial.defaultMaterial());

        assertTrue(GltfMaterialBaker.emissionInSpecular(material, true));
        assertFalse(GltfMaterialBaker.emissionInSpecular(material, false));
    }

    @Test
    void canonicalizesACompleteRepeatedTextureTileWithoutResamplingTriangles() {
        GltfTextureInfo source = new GltfTextureInfo(0, 0);
        float[] uv = {
            0.10f, 1.05f,
            0.40f, 1.80f,
            0.90f, 1.95f
        };

        GltfTextureInfo canonical = GltfMaterialBaker.canonicalTextureInfo(
            uv, source, 10497, 10497
        );
        float[] point = new float[2];

        canonical.transform(0.10f, 1.05f, point);
        assertEquals(0.10f, point[0], 1.0e-6f);
        assertEquals(0.05f, point[1], 1.0e-6f);
        canonical.transform(0.90f, 1.95f, point);
        assertEquals(0.90f, point[0], 1.0e-6f);
        assertEquals(0.95f, point[1], 1.0e-6f);
    }

    @Test
    void retainsChartsWhenThePrimitiveCrossesARepeatCellOrUsesClamp() {
        GltfTextureInfo source = new GltfTextureInfo(0, 0);
        float[] crossing = {
            0.25f, 1.80f,
            0.50f, 2.10f,
            0.75f, 2.40f
        };
        float[] outside = {
            0.0f, 1.1f,
            0.5f, 1.5f,
            1.0f, 1.9f
        };

        assertSame(source, GltfMaterialBaker.canonicalTextureInfo(
            crossing, source, 10497, 10497
        ));
        assertSame(source, GltfMaterialBaker.canonicalTextureInfo(
            outside, source, 33071, 33071
        ));
    }

    private static GltfTexture texture(int[] pixels) throws IOException {
        try (NativeImage image = new NativeImage(2, 2, false)) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    image.setPixelRGBA(x, y, pixels[y * 2 + x]);
                }
            }
            return new GltfTexture("test", "image/png", ByteBuffer.wrap(image.asByteArray()),
                9728, 9728, 10497, 10497);
        }
    }
}
