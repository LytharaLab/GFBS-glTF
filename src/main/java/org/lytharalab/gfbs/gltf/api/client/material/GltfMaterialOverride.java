package org.lytharalab.gfbs.gltf.api.client.material;

import org.lytharalab.gfbs.gltf.api.model.AlphaMode;
import org.lytharalab.gfbs.gltf.api.model.GltfMaterial;
import org.lytharalab.gfbs.gltf.api.model.GltfTextureInfo;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, reusable override for every runtime-adjustable glTF material property.
 * Unspecified properties are inherited from the selected source material.
 */
public final class GltfMaterialOverride {
    public static final float DEFAULT_NEON_STRENGTH = 4.0f;
    private static final GltfMaterialOverride NONE = new Builder().build();

    private final float[] baseColor;
    private final GltfTextureInfo baseColorTexture;
    private final Float metallicFactor;
    private final Float roughnessFactor;
    private final GltfTextureInfo metallicRoughnessTexture;
    private final GltfTextureInfo normalTexture;
    private final Float normalScale;
    private final GltfTextureInfo occlusionTexture;
    private final Float occlusionStrength;
    private final float[] emissive;
    private final GltfTextureInfo emissiveTexture;
    private final Float emissiveStrength;
    private final AlphaMode alphaMode;
    private final Float alphaCutoff;
    private final Boolean doubleSided;
    private final GltfShadingMode shadingMode;
    private final Float neonStrength;

    private GltfMaterialOverride(Builder builder) {
        baseColor = copy(builder.baseColor);
        baseColorTexture = builder.baseColorTexture;
        metallicFactor = builder.metallicFactor;
        roughnessFactor = builder.roughnessFactor;
        metallicRoughnessTexture = builder.metallicRoughnessTexture;
        normalTexture = builder.normalTexture;
        normalScale = builder.normalScale;
        occlusionTexture = builder.occlusionTexture;
        occlusionStrength = builder.occlusionStrength;
        emissive = copy(builder.emissive);
        emissiveTexture = builder.emissiveTexture;
        emissiveStrength = builder.emissiveStrength;
        alphaMode = builder.alphaMode;
        alphaCutoff = builder.alphaCutoff;
        doubleSided = builder.doubleSided;
        shadingMode = builder.shadingMode;
        neonStrength = builder.neonStrength;
    }

    public static Builder builder() { return new Builder(); }
    public static GltfMaterialOverride none() { return NONE; }

    public boolean isEmpty() {
        return baseColor == null && baseColorTexture == null && metallicFactor == null
            && roughnessFactor == null && metallicRoughnessTexture == null
            && normalTexture == null && normalScale == null && occlusionTexture == null
            && occlusionStrength == null && emissive == null && emissiveTexture == null
            && emissiveStrength == null && alphaMode == null && alphaCutoff == null
            && doubleSided == null && shadingMode == null && neonStrength == null;
    }

    public GltfShadingMode shadingMode() { return shadingMode; }
    public Float neonStrength() { return neonStrength; }
    public Optional<float[]> baseColor() {
        return baseColor == null ? Optional.empty() : Optional.of(baseColor.clone());
    }
    public Optional<GltfTextureInfo> baseColorTexture() { return Optional.ofNullable(baseColorTexture); }
    public Optional<Float> metallicFactor() { return Optional.ofNullable(metallicFactor); }
    public Optional<Float> roughnessFactor() { return Optional.ofNullable(roughnessFactor); }
    public Optional<GltfTextureInfo> metallicRoughnessTexture() {
        return Optional.ofNullable(metallicRoughnessTexture);
    }
    public Optional<GltfTextureInfo> normalTexture() { return Optional.ofNullable(normalTexture); }
    public Optional<Float> normalScale() { return Optional.ofNullable(normalScale); }
    public Optional<GltfTextureInfo> occlusionTexture() { return Optional.ofNullable(occlusionTexture); }
    public Optional<Float> occlusionStrength() { return Optional.ofNullable(occlusionStrength); }
    public Optional<float[]> emissive() {
        return emissive == null ? Optional.empty() : Optional.of(emissive.clone());
    }
    public Optional<GltfTextureInfo> emissiveTexture() { return Optional.ofNullable(emissiveTexture); }
    public Optional<Float> emissiveStrength() { return Optional.ofNullable(emissiveStrength); }
    public Optional<AlphaMode> alphaMode() { return Optional.ofNullable(alphaMode); }
    public Optional<Float> alphaCutoff() { return Optional.ofNullable(alphaCutoff); }
    public Optional<Boolean> doubleSided() { return Optional.ofNullable(doubleSided); }
    public Optional<GltfShadingMode> shading() { return Optional.ofNullable(shadingMode); }
    public Optional<Float> neonEmissionStrength() { return Optional.ofNullable(neonStrength); }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.baseColor = copy(baseColor);
        builder.baseColorTexture = baseColorTexture;
        builder.metallicFactor = metallicFactor;
        builder.roughnessFactor = roughnessFactor;
        builder.metallicRoughnessTexture = metallicRoughnessTexture;
        builder.normalTexture = normalTexture;
        builder.normalScale = normalScale;
        builder.occlusionTexture = occlusionTexture;
        builder.occlusionStrength = occlusionStrength;
        builder.emissive = copy(emissive);
        builder.emissiveTexture = emissiveTexture;
        builder.emissiveStrength = emissiveStrength;
        builder.alphaMode = alphaMode;
        builder.alphaCutoff = alphaCutoff;
        builder.doubleSided = doubleSided;
        builder.shadingMode = shadingMode;
        builder.neonStrength = neonStrength;
        return builder;
    }

    /** Resolves this sparse override into a complete immutable material. */
    public GltfMaterial resolve(GltfMaterial source) {
        Objects.requireNonNull(source, "source");
        if (isEmpty()) return source;

        float[] resolvedBase = baseColor == null ? source.baseColor() : baseColor.clone();
        GltfTextureInfo resolvedBaseTexture = value(baseColorTexture, source.baseColorTextureInfo());
        float resolvedMetallic = value(metallicFactor, source.metallicFactor());
        float resolvedRoughness = value(roughnessFactor, source.roughnessFactor());
        GltfTextureInfo resolvedMetallicTexture = value(
            metallicRoughnessTexture, source.metallicRoughnessTextureInfo()
        );
        GltfTextureInfo resolvedNormalTexture = value(normalTexture, source.normalTextureInfo());
        float resolvedNormalScale = value(normalScale, source.normalScale());
        GltfTextureInfo resolvedOcclusionTexture = value(
            occlusionTexture, source.occlusionTextureInfo()
        );
        float resolvedOcclusionStrength = value(occlusionStrength, source.occlusionStrength());
        float[] resolvedEmissive = emissive == null ? source.emissive() : emissive.clone();
        GltfTextureInfo resolvedEmissiveTexture = value(
            emissiveTexture, source.emissiveTextureInfo()
        );
        float resolvedEmissiveStrength = value(emissiveStrength, source.emissiveStrength());
        AlphaMode resolvedAlphaMode = value(alphaMode, source.alphaMode());
        float resolvedAlphaCutoff = value(alphaCutoff, source.alphaCutoff());
        boolean resolvedDoubleSided = value(doubleSided, source.doubleSided());
        boolean resolvedUnlit = source.unlit();

        if (shadingMode == GltfShadingMode.PBR) {
            resolvedUnlit = false;
        } else if (shadingMode == GltfShadingMode.UNLIT) {
            resolvedUnlit = true;
        } else if (shadingMode == GltfShadingMode.NEON) {
            resolvedUnlit = true;
            if (emissive == null) {
                resolvedEmissive = Arrays.copyOf(resolvedBase, 3);
            }
            if (emissiveTexture == null) {
                resolvedEmissiveTexture = resolvedBaseTexture;
            }
            if (emissiveStrength == null) {
                resolvedEmissiveStrength = neonStrength == null
                    ? DEFAULT_NEON_STRENGTH : neonStrength;
            }
        }

        return new GltfMaterial(
            source.name(), resolvedBase, resolvedBaseTexture,
            resolvedMetallic, resolvedRoughness, resolvedMetallicTexture,
            resolvedNormalTexture, resolvedNormalScale,
            resolvedOcclusionTexture, resolvedOcclusionStrength,
            resolvedEmissive, resolvedEmissiveTexture, resolvedEmissiveStrength,
            resolvedAlphaMode, resolvedAlphaCutoff, resolvedDoubleSided, resolvedUnlit
        );
    }

    private static float[] copy(float[] value) { return value == null ? null : value.clone(); }
    private static <T> T value(T override, T fallback) { return override == null ? fallback : override; }
    private static float value(Float override, float fallback) { return override == null ? fallback : override; }
    private static boolean value(Boolean override, boolean fallback) { return override == null ? fallback : override; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof GltfMaterialOverride other)) return false;
        return Arrays.equals(baseColor, other.baseColor)
            && Objects.equals(baseColorTexture, other.baseColorTexture)
            && Objects.equals(metallicFactor, other.metallicFactor)
            && Objects.equals(roughnessFactor, other.roughnessFactor)
            && Objects.equals(metallicRoughnessTexture, other.metallicRoughnessTexture)
            && Objects.equals(normalTexture, other.normalTexture)
            && Objects.equals(normalScale, other.normalScale)
            && Objects.equals(occlusionTexture, other.occlusionTexture)
            && Objects.equals(occlusionStrength, other.occlusionStrength)
            && Arrays.equals(emissive, other.emissive)
            && Objects.equals(emissiveTexture, other.emissiveTexture)
            && Objects.equals(emissiveStrength, other.emissiveStrength)
            && alphaMode == other.alphaMode
            && Objects.equals(alphaCutoff, other.alphaCutoff)
            && Objects.equals(doubleSided, other.doubleSided)
            && shadingMode == other.shadingMode
            && Objects.equals(neonStrength, other.neonStrength);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(baseColor);
        result = 31 * result + Objects.hash(
            baseColorTexture, metallicFactor, roughnessFactor, metallicRoughnessTexture,
            normalTexture, normalScale, occlusionTexture, occlusionStrength,
            emissiveTexture, emissiveStrength, alphaMode, alphaCutoff, doubleSided,
            shadingMode, neonStrength
        );
        return 31 * result + Arrays.hashCode(emissive);
    }

    public static final class Builder {
        private float[] baseColor;
        private GltfTextureInfo baseColorTexture;
        private Float metallicFactor;
        private Float roughnessFactor;
        private GltfTextureInfo metallicRoughnessTexture;
        private GltfTextureInfo normalTexture;
        private Float normalScale;
        private GltfTextureInfo occlusionTexture;
        private Float occlusionStrength;
        private float[] emissive;
        private GltfTextureInfo emissiveTexture;
        private Float emissiveStrength;
        private AlphaMode alphaMode;
        private Float alphaCutoff;
        private Boolean doubleSided;
        private GltfShadingMode shadingMode;
        private Float neonStrength;

        private Builder() {}

        public Builder baseColor(float red, float green, float blue, float alpha) {
            baseColor = new float[]{red, green, blue, alpha};
            return this;
        }
        public Builder baseColorTexture(GltfTextureInfo texture) {
            baseColorTexture = Objects.requireNonNull(texture, "texture"); return this;
        }
        public Builder metallicFactor(float value) { metallicFactor = value; return this; }
        public Builder roughnessFactor(float value) { roughnessFactor = value; return this; }
        public Builder metallicRoughnessTexture(GltfTextureInfo texture) {
            metallicRoughnessTexture = Objects.requireNonNull(texture, "texture"); return this;
        }
        public Builder normalTexture(GltfTextureInfo texture) {
            normalTexture = Objects.requireNonNull(texture, "texture"); return this;
        }
        public Builder normalScale(float value) { normalScale = value; return this; }
        public Builder occlusionTexture(GltfTextureInfo texture) {
            occlusionTexture = Objects.requireNonNull(texture, "texture"); return this;
        }
        public Builder occlusionStrength(float value) { occlusionStrength = value; return this; }
        public Builder emissive(float red, float green, float blue) {
            emissive = new float[]{red, green, blue}; return this;
        }
        public Builder emissiveTexture(GltfTextureInfo texture) {
            emissiveTexture = Objects.requireNonNull(texture, "texture"); return this;
        }
        public Builder emissiveStrength(float value) { emissiveStrength = value; return this; }
        public Builder alphaMode(AlphaMode value) {
            alphaMode = Objects.requireNonNull(value, "value"); return this;
        }
        public Builder alphaCutoff(float value) { alphaCutoff = value; return this; }
        public Builder doubleSided(boolean value) { doubleSided = value; return this; }
        public Builder shadingMode(GltfShadingMode value) {
            shadingMode = Objects.requireNonNull(value, "value"); return this;
        }
        public Builder neonStrength(float value) {
            if (!Float.isFinite(value) || value < 0.0f) {
                throw new IllegalArgumentException("Neon strength must be finite and non-negative");
            }
            neonStrength = value;
            return this;
        }

        public GltfMaterialOverride build() {
            // Constructing a throwaway default-based material centralizes all glTF range checks.
            GltfMaterialOverride result = new GltfMaterialOverride(this);
            if (!result.isEmpty()) result.resolve(GltfMaterial.defaultMaterial());
            return result;
        }
    }
}
