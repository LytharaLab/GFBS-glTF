package org.lytharalab.gfbs.gltf.core.io;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.io.ModelImporters;
import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjAssetImporterTest {
    @Test
    void importsGroupsMaterialsQuadsAndGeneratedNormals() throws Exception {
        ResourceLocation obj = id("models/machine.obj");
        Map<ResourceLocation, byte[]> resources = Map.of(
            obj, """
                mtllib machine.mtl
                o casing
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 1 1
                vt 0 1
                usemtl steel
                f -4/-4 -3/-3 -2/-2 -1/-1
                """.getBytes(StandardCharsets.UTF_8),
            id("models/machine.mtl"), """
                newmtl steel
                Kd 0.4 0.5 0.6
                d 0.75
                """.getBytes(StandardCharsets.UTF_8)
        );

        GltfAsset asset = ModelImporters.load(obj, location ->
            new ByteArrayInputStream(resources.get(location)));

        assertEquals(1, asset.nodes().size());
        assertEquals("casing/steel", asset.nodes().get(0).name());
        assertEquals(6, asset.meshes().get(0).primitives().get(0).indexCount());
        assertEquals(AlphaMode.BLEND, asset.materials().get(1).alphaMode());
        float[] normals = asset.meshes().get(0).primitives().get(0).normals();
        assertTrue(normals[2] > 0.99f);
    }

    @Test
    void rejectsParentTraversalInMaterialReferences() {
        ResourceLocation obj = id("models/machine.obj");
        byte[] source = "mtllib ../secret.mtl\nv 0 0 0\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(GltfLoadException.class, () ->
            ModelImporters.load(obj, location -> new ByteArrayInputStream(source)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
