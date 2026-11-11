package org.lytharalab.gfbs.gltf.api.model;

/**
 * Optional LabPBR 1.3 material channels. The packed maps share the base color UV mapping.
 * A value of -1 selects the glTF-derived/default value. Channel overrides are exact bytes.
 */
public record GltfLabPbr(int normalTexture, int specularTexture, int reflectance,
                        int porosityOrSubsurface, int height, int emission,
                        int smoothness, int normalX, int normalY, int ambientOcclusion) {
    public static final GltfLabPbr DEFAULT = new GltfLabPbr(-1, -1, -1, -1, -1, -1);

    public GltfLabPbr(int normalTexture, int specularTexture, int reflectance,
                      int porosityOrSubsurface, int height, int emission) {
        this(normalTexture, specularTexture, reflectance, porosityOrSubsurface, height, emission,
            -1, -1, -1, -1);
    }

    public GltfLabPbr {
        if (normalTexture < -1 || specularTexture < -1) {
            throw new IllegalArgumentException("Invalid LabPBR texture index");
        }
        checkChannel(reflectance);
        checkChannel(porosityOrSubsurface);
        checkChannel(height);
        checkChannel(emission);
        checkChannel(smoothness);
        checkChannel(normalX);
        checkChannel(normalY);
        checkChannel(ambientOcclusion);
    }

    public static GltfLabPbr packed(int normalTexture, int specularTexture) {
        return new GltfLabPbr(normalTexture, specularTexture, -1, -1, -1, -1);
    }

    /** Applies specified fields while retaining every -1 field from this value. */
    public GltfLabPbr overlay(GltfLabPbr patch) {
        java.util.Objects.requireNonNull(patch, "patch");
        return new GltfLabPbr(
            inherit(normalTexture, patch.normalTexture), inherit(specularTexture, patch.specularTexture),
            inherit(reflectance, patch.reflectance), inherit(porosityOrSubsurface, patch.porosityOrSubsurface),
            inherit(height, patch.height), inherit(emission, patch.emission),
            inherit(smoothness, patch.smoothness), inherit(normalX, patch.normalX),
            inherit(normalY, patch.normalY), inherit(ambientOcclusion, patch.ambientOcclusion));
    }

    private static int inherit(int original, int patch) { return patch < 0 ? original : patch; }

    private static void checkChannel(int value) {
        if (value < -1 || value > 255) {
            throw new IllegalArgumentException("LabPBR channel must be -1 (automatic) or a byte");
        }
    }
}
