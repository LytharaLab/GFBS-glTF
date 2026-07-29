package org.lytharalab.gfbs.gltf.api.model;

import java.util.Arrays;

public final class GltfMaterial {
    private final String name;
    private final float[] baseColor;
    private final int baseColorTexture;
    private final int baseColorTexCoord;
    private final float metallicFactor, roughnessFactor;
    private final int metallicRoughnessTexture, metallicRoughnessTexCoord, normalTexture, normalTexCoord, occlusionTexture, occlusionTexCoord;
    private final float normalScale, occlusionStrength;
    private final float[] emissive;
    private final int emissiveTexture;
    private final int emissiveTexCoord;
    private final AlphaMode alphaMode;
    private final float alphaCutoff;
    private final boolean doubleSided;

    public GltfMaterial(String name, float[] baseColor, int baseColorTexture,
                        int baseColorTexCoord, float[] emissive, int emissiveTexture,
                        int emissiveTexCoord, AlphaMode alphaMode,
                        float alphaCutoff, boolean doubleSided) {
        this(
            name,
            baseColor,
            baseColorTexture,
            baseColorTexCoord,
            1.0f,
            1.0f,
            -1,
            0,
            -1,
            0,
            1.0f,
            -1,
            0,
            1.0f,
            emissive,
            emissiveTexture,
            emissiveTexCoord,
            alphaMode,
            alphaCutoff,
            doubleSided
        );
    }

    public GltfMaterial(
        String name,
        float[] baseColor,
        int baseColorTexture,
        int baseColorTexCoord,
        float metallicFactor,
        float roughnessFactor,
        int metallicRoughnessTexture,
        int metallicRoughnessTexCoord,
        int normalTexture,
        int normalTexCoord,
        float normalScale,
        int occlusionTexture,
        int occlusionTexCoord,
        float occlusionStrength,
        float[] emissive,
        int emissiveTexture,
        int emissiveTexCoord,
        AlphaMode alphaMode,
        float alphaCutoff,
        boolean doubleSided
    ) {
        this.name = name == null ? "" : name;
        this.baseColor = copy(baseColor, 4, new float[]{1, 1, 1, 1}, "base color");
        requireRange(this.baseColor, 0.0f, 1.0f, "base color");
        if (baseColorTexture < -1) throw new IllegalArgumentException("Invalid base color texture index");
        this.baseColorTexture = baseColorTexture;
        requireSupportedTexCoord(baseColorTexCoord);
        this.baseColorTexCoord = baseColorTexCoord;
        this.metallicFactor = unit(metallicFactor, "metallic factor");
        this.roughnessFactor = unit(roughnessFactor, "roughness factor");
        this.metallicRoughnessTexture = texture(
            metallicRoughnessTexture,
            "metallic-roughness"
        );
        requireSupportedTexCoord(metallicRoughnessTexCoord);
        this.metallicRoughnessTexCoord = metallicRoughnessTexCoord;
        this.normalTexture = texture(normalTexture, "normal");
        requireSupportedTexCoord(normalTexCoord);
        this.normalTexCoord = normalTexCoord;
        this.normalScale = finite(normalScale, "normal scale");
        this.occlusionTexture = texture(occlusionTexture, "occlusion");
        requireSupportedTexCoord(occlusionTexCoord);
        this.occlusionTexCoord = occlusionTexCoord;
        this.occlusionStrength = unit(occlusionStrength, "occlusion strength");
        this.emissive = copy(emissive, 3, new float[]{0, 0, 0}, "emissive factor");
        requireRange(this.emissive, 0.0f, 1.0f, "emissive factor");
        if (emissiveTexture < -1) throw new IllegalArgumentException("Invalid emissive texture index");
        this.emissiveTexture = emissiveTexture;
        requireSupportedTexCoord(emissiveTexCoord);
        this.emissiveTexCoord = emissiveTexCoord;
        this.alphaMode = alphaMode == null ? AlphaMode.OPAQUE : alphaMode;
        if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
            throw new IllegalArgumentException("Alpha cutoff must be between 0 and 1");
        }
        this.alphaCutoff = alphaCutoff;
        this.doubleSided = doubleSided;
    }

    private static int texture(int value, String label) {
        if (value < -1) throw new IllegalArgumentException("Invalid " + label + " texture index");
        return value;
    }

    private static float unit(float value, String label) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(label + " must be between 0 and 1");
        }
        return value;
    }

    private static float finite(float value, String label) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(label + " must be finite");
        return value;
    }

    private static float[] copy(float[] input, int length, float[] fallback, String label) {
        float[] result;
        if (input == null) result = fallback.clone();
        else {
            if (input.length != length) throw new IllegalArgumentException("Expected " + length + " values for " + label);
            result = Arrays.copyOf(input, length);
        }
        for (float value : result) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException(label + " contains a non-finite value");
        }
        return result;
    }

    private static void requireRange(float[] values, float minimum, float maximum, String label) {
        for (float value : values) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(label + " values must be between " + minimum + " and " + maximum);
            }
        }
    }

    private static void requireSupportedTexCoord(int texCoord) {
        if (texCoord < 0 || texCoord > 1) {
            throw new IllegalArgumentException("GFBS:glTF supports TEXCOORD_0 and TEXCOORD_1");
        }
    }

    public static GltfMaterial defaultMaterial() {
        return new GltfMaterial("default", null, -1, 0, null, -1, 0,
            AlphaMode.OPAQUE, 0.5f, false);
    }

    public String name() { return name; }
    public float[] baseColor() { return baseColor.clone(); }
    public int baseColorTexture() { return baseColorTexture; }
    public int baseColorTexCoord() { return baseColorTexCoord; }
    public float metallicFactor() { return metallicFactor; }
    public float roughnessFactor() { return roughnessFactor; }
    public int metallicRoughnessTexture() { return metallicRoughnessTexture; }
    public int metallicRoughnessTexCoord() { return metallicRoughnessTexCoord; }
    public int normalTexture() { return normalTexture; }
    public int normalTexCoord() { return normalTexCoord; }
    public float normalScale() { return normalScale; }
    public int occlusionTexture() { return occlusionTexture; }
    public int occlusionTexCoord() { return occlusionTexCoord; }
    public float occlusionStrength() { return occlusionStrength; }
    public float[] emissive() { return emissive.clone(); }
    public int emissiveTexture() { return emissiveTexture; }
    public int emissiveTexCoord() { return emissiveTexCoord; }
    public AlphaMode alphaMode() { return alphaMode; }
    public float alphaCutoff() { return alphaCutoff; }
    public boolean doubleSided() { return doubleSided; }
}
