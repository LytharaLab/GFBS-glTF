package org.lytharalab.gfbs.gltf.api.client.material;

import java.util.Objects;

/**
 * Reusable material state. A variant may switch to another asset material, override its
 * properties, or do both. {@value #PRIMITIVE_MATERIAL} retains the primitive's authored material.
 */
public record GltfMaterialVariant(int materialIndex, GltfMaterialOverride override) {
    public static final int PRIMITIVE_MATERIAL = -1;

    public GltfMaterialVariant {
        if (materialIndex < PRIMITIVE_MATERIAL) {
            throw new IllegalArgumentException("Material index must be -1 or non-negative");
        }
        override = Objects.requireNonNull(override, "override");
    }

    public static GltfMaterialVariant source() {
        return new GltfMaterialVariant(PRIMITIVE_MATERIAL, GltfMaterialOverride.none());
    }

    public static GltfMaterialVariant material(int materialIndex) {
        return new GltfMaterialVariant(materialIndex, GltfMaterialOverride.none());
    }

    public static GltfMaterialVariant override(GltfMaterialOverride override) {
        return new GltfMaterialVariant(PRIMITIVE_MATERIAL, override);
    }

    public static GltfMaterialVariant neon() {
        return override(GltfMaterialOverride.builder()
            .shadingMode(GltfShadingMode.NEON)
            .build());
    }
}
