package org.lytharalab.gfbs.gltf.client.render;

import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfLabPbr;
import org.lytharalab.gfbs.gltf.api.client.material.GltfMaterialOverride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lytharalab.gfbs.gltf.client.render.LabPbrEncoder.*;

class LabPbrEncoderTest {
    @Test
    void packsNormalAoHeightAndConvertsPerceptualRoughnessAndMetal() {
        assertEquals(rgba(128, 0, 91, 205), normal(rgba(128, 255, 128, 255), 91, 1, 205));
        GltfMaterial material = GltfMaterial.defaultMaterial();
        assertEquals(rgba(127, 10, 0, 255), specular(rgba(0, 128, 0, 0), material, 255));
        assertEquals(rgba(127, 255, 0, 254), specular(rgba(0, 128, 255, 0), material, 254));
    }

    @Test
    void downsamplingKeepsMaterialIdsAndEmissionSentinelDiscrete() {
        int metallic = rgba(120, 231, 112, 170);
        int dielectric = rgba(80, 10, 20, 255);
        int expected = rgba(110, 231, 112, 170);
        assertEquals(expected, mipSpecular(dielectric, metallic, metallic, metallic));
    }

    @Test
    void convertsIndependentEmissiveSamplesToDiscreteEmissionMasks() {
        GltfMaterial material = GltfMaterialOverride.builder()
            .emissive(1.0f, 1.0f, 1.0f)
            .emissiveStrength(1.0f)
            .build()
            .resolve(GltfMaterial.defaultMaterial());
        assertEquals(255, emission(rgba(0, 0, 0, 255), material));
        assertEquals(127, emission(rgba(0, 128, 0, 255), material));
        assertEquals(254, emission(rgba(255, 255, 255, 255), material));
        assertEquals(255, emission(0.0f));
        assertEquals(1, emission(0.0001f));
    }

    @Test
    void sparseChannelOverridesRetainPackedMapsAndReplacementResetsThem() {
        GltfLabPbr source = new GltfLabPbr(0, 1, 235, 22, 64, 99, 182, 144, 101, 210);
        GltfLabPbr patch = new GltfLabPbr(-1, -1, -1, 33, -1, 88, -1, -1, 177, -1);
        GltfMaterial material = GltfMaterial.defaultMaterial().withLabPbr(source);
        assertEquals(new GltfLabPbr(0, 1, 235, 33, 64, 88, 182, 144, 177, 210),
            GltfMaterialOverride.builder().labPbrChannels(patch).build().resolve(material).labPbr());
        assertEquals(patch, GltfMaterialOverride.builder().labPbr(patch).build().resolve(material).labPbr());
        assertEquals(rgba(182, 235, 22, 255), specular(-1, material, 255));
        assertThrows(IllegalArgumentException.class,
            () -> new GltfLabPbr(-1, -1, -1, -1, -1, -1, 256, -1, -1, -1));
    }
}
