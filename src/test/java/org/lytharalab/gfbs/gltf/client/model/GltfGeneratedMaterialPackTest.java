package org.lytharalab.gfbs.gltf.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.client.material.GltfPbrBakeOptions;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfGeneratedMaterialPackTest {
    private static final GltfAsset ASSET = new GltfAsset(
        ResourceLocation.fromNamespaceAndPath("test", "models/triangle.gltf"),
        List.of(), List.of(), List.of(), List.of(GltfMaterial.defaultMaterial()),
        List.of(), List.of(), List.of(), List.of(), List.of()
    );

    @Test
    void separatesUprightAndFlippedAtlasSprites() {
        GltfGeneratedMaterialPack pack = new GltfGeneratedMaterialPack("test");
        try {
            GltfPbrBakeOptions options = new GltfPbrBakeOptions(256, 64, 1024);
            ResourceLocation upright = pack.sprite(ASSET, 0, 1, options, false, 2);
            ResourceLocation flipped = pack.sprite(ASSET, 0, 1, options, true, 2);
            assertNotEquals(upright, flipped);
            assertTrue(upright.getPath().contains("/upright/2"));
            assertTrue(flipped.getPath().contains("/flipped/2"));
            assertTrue(upright.getPath().contains("/0/1/256_64_1024/"));
            assertEquals(upright, pack.sprite(ASSET, 0, 1, options, 2));
        } finally {
            pack.close();
        }
    }

    @Test
    void parsesStaticBakeOptions() {
        JsonObject model = JsonParser.parseString(
            "{\"pbr_bake\":{\"page_size\":512,\"chart_size\":64,\"max_pixels\":123}}"
        ).getAsJsonObject();
        assertEquals(new GltfPbrBakeOptions(512, 64, 123), GltfGeneratedMaterialPack.bakeOptions(model));
        assertEquals(GltfPbrBakeOptions.DEFAULT, GltfGeneratedMaterialPack.bakeOptions(new JsonObject()));
    }
}
