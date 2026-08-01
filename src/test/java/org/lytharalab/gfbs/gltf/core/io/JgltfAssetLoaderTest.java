package org.lytharalab.gfbs.gltf.core.io;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JgltfAssetLoaderTest {
    private static final ResourceLocation GLTF = ResourceLocation.fromNamespaceAndPath("test", "models/triangle.gltf");

    @Test
    void rejectsLegacyGltfOneAssets() {
        String legacy = "{\"asset\":{\"version\":\"1.0\"}}";
        assertThrows(GltfLoadException.class, () -> new JgltfAssetLoader().load(GLTF, ignored ->
            new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void loadsGltfWithEmbeddedBinaryDataUri() throws Exception {
        byte[] bin = triangleBinary();
        String json = json("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bin), bin.length);
        GltfAsset asset = new JgltfAssetLoader().load(GLTF, ignored ->
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertTriangle(asset);
    }


    @Test
    void loadsPercentEncodedDataUri() throws Exception {
        byte[] bin = triangleBinary();
        StringBuilder uri = new StringBuilder("data:application/octet-stream,");
        for (byte value : bin) uri.append(String.format("%%%02X", value & 0xFF));
        String json = json(uri.toString(), bin.length);
        GltfAsset asset = new JgltfAssetLoader().load(GLTF, ignored ->
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertTriangle(asset);
    }

    @Test
    void rejectsMalformedDataUris() {
        assertThrows(GltfLoadException.class, () -> loadEmbedded("data:application/octet-stream;base64,%%%"));
        assertThrows(GltfLoadException.class, () -> loadEmbedded("data:application/octet-stream,%0"));
        assertThrows(GltfLoadException.class, () -> loadEmbedded("data:application/octet-stream,é"));
    }


    @Test
    void rejectsTrailingJsonAndExcessiveNesting() {
        byte[] bin = triangleBinary();
        String trailing = json("data:application/octet-stream;base64,"
            + Base64.getEncoder().encodeToString(bin), bin.length) + "{}";
        assertThrows(GltfLoadException.class, () -> new JgltfAssetLoader().load(GLTF, ignored ->
            new ByteArrayInputStream(trailing.getBytes(StandardCharsets.UTF_8))));

        String nested = "[".repeat(513) + "0" + "]".repeat(513);
        String excessive = "{\"asset\":{\"version\":\"2.0\"},\"extras\":" + nested + "}";
        assertThrows(GltfLoadException.class, () -> new JgltfAssetLoader().load(GLTF, ignored ->
            new ByteArrayInputStream(excessive.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void resolvesExternalBinRelativeToGltf() throws Exception {
        byte[] bin = triangleBinary();
        byte[] json = json("triangle.bin", bin.length).getBytes(StandardCharsets.UTF_8);
        GltfAsset asset = new JgltfAssetLoader().load(GLTF, id -> {
            if (id.equals(GLTF)) return new ByteArrayInputStream(json);
            if (id.equals(ResourceLocation.fromNamespaceAndPath("test", "models/triangle.bin"))) return new ByteArrayInputStream(bin);
            throw new AssertionError("Unexpected resource " + id);
        });
        assertTriangle(asset);
    }

    @Test
    void loadsBinaryGlbContainer() throws Exception {
        byte[] glb = glb(triangleBinary());
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("test", "models/triangle.glb");
        GltfAsset asset = new JgltfAssetLoader().load(id, ignored -> new ByteArrayInputStream(glb));
        assertTriangle(asset);
    }

    @Test
    void loadsUnlitAndEmissiveStrengthMaterialExtensions() throws Exception {
        byte[] bin = triangleBinary();
        String source = json(
            "data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(bin),
            bin.length
        );
        String extended = source.replace(
            "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}]}",
            """
            "extensionsUsed":["KHR_materials_unlit","KHR_materials_emissive_strength"],
            "materials":[{
              "emissiveFactor":[0.25,0.5,1.0],
              "extensions":{
                "KHR_materials_unlit":{},
                "KHR_materials_emissive_strength":{"emissiveStrength":4.0}
              }
            }],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1,"material":0}]}]}
            """
        );
        GltfAsset asset = new JgltfAssetLoader().load(GLTF, ignored ->
            new ByteArrayInputStream(extended.getBytes(StandardCharsets.UTF_8)));
        var material = asset.materials().get(0);
        assertTrue(material.unlit());
        assertEquals(4.0f, material.emissiveStrength());
        assertArrayEquals(new float[]{.25f, .5f, 1.0f}, material.emissive(), 1.0e-6f);
    }


    @Test
    void synthesizesDefaultSceneWhenSceneArrayIsMissing() throws Exception {
        byte[] bin = triangleBinary();
        String json = json("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bin), bin.length)
            .replace("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],", "");
        GltfAsset asset = new JgltfAssetLoader().load(GLTF, ignored ->
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, asset.scenes().size());
        assertArrayEquals(new int[]{0}, asset.scenes().get(0).roots());
        assertEquals(0, asset.defaultScene());
    }

    @Test
    void rejectsNetworkAndNamespaceEscapingReferences() {
        assertThrows(Exception.class, () -> loadWithUri("https://example.invalid/triangle.bin"));
        assertThrows(Exception.class, () -> loadWithUri("../../triangle.bin"));
        assertThrows(Exception.class, () -> loadWithUri("/triangle.bin"));
    }

    @Test
    void enforcesCombinedResourceBudget() {
        byte[] oversized = new byte[900];
        System.arraycopy(triangleBinary(), 0, oversized, 0, triangleBinary().length);
        byte[] json = json("triangle.bin", oversized.length).getBytes(StandardCharsets.UTF_8);
        assertThrows(GltfLoadException.class, () -> new JgltfAssetLoader(1024).load(GLTF, id -> {
            if (id.equals(GLTF)) return new ByteArrayInputStream(json);
            return new ByteArrayInputStream(oversized);
        }));
    }


    private static void loadEmbedded(String uri) throws Exception {
        byte[] bin = triangleBinary();
        String json = json(uri, bin.length);
        new JgltfAssetLoader().load(GLTF, ignored ->
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static void loadWithUri(String uri) throws Exception {
        byte[] bin = triangleBinary();
        byte[] json = json(uri, bin.length).getBytes(StandardCharsets.UTF_8);
        new JgltfAssetLoader().load(GLTF, id -> {
            if (id.equals(GLTF)) return new ByteArrayInputStream(json);
            return new ByteArrayInputStream(bin);
        });
    }

    private static void assertTriangle(GltfAsset asset) {
        assertEquals(1, asset.scenes().size());
        assertEquals(1, asset.meshes().size());
        var primitive = asset.meshes().get(0).primitives().get(0);
        assertArrayEquals(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, primitive.positions(), 1.0e-6f);
        assertArrayEquals(new int[]{0, 1, 2}, primitive.indices());
    }

    private static byte[] triangleBinary() {
        ByteBuffer data = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}) data.putFloat(value);
        data.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0);
        return data.array();
    }

    private static String json(String uri, int byteLength) {
        String buffer = uri == null ? "{\"byteLength\":" + byteLength + "}"
            : "{\"byteLength\":" + byteLength + ",\"uri\":\"" + uri + "\"}";
        return """
            {"asset":{"version":"2.0"},
             "scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
             "buffers":[%s],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},
                            {"buffer":0,"byteOffset":36,"byteLength":6,"target":34963}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                          {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}
            """.formatted(buffer);
    }

    private static byte[] glb(byte[] bin) {
        byte[] json = json(null, bin.length).getBytes(StandardCharsets.UTF_8);
        int jsonLength = (json.length + 3) & ~3;
        ByteBuffer result = ByteBuffer.allocate(12 + 8 + jsonLength + 8 + bin.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x46546C67).putInt(2).putInt(result.capacity());
        result.putInt(jsonLength).putInt(0x4E4F534A).put(json);
        while (result.position() < 20 + jsonLength) result.put((byte) 0x20);
        result.putInt(bin.length).putInt(0x004E4942).put(bin);
        return result.array();
    }
}
