package org.lytharalab.gfbs.gltf.api.model;

/**
 * Immutable glTF texture reference, including {@code KHR_texture_transform}.
 *
 * <p>The transform follows the glTF extension order: scale, rotate, then translate.</p>
 */
public record GltfTextureInfo(
    int texture,
    int texCoord,
    float offsetU,
    float offsetV,
    float scaleU,
    float scaleV,
    float rotation
) {
    public GltfTextureInfo {
        if (texture < -1) throw new IllegalArgumentException("Invalid texture index");
        if (texCoord < 0 || texCoord > 1) {
            throw new IllegalArgumentException("GFBS:glTF supports TEXCOORD_0 and TEXCOORD_1");
        }
        if (!Float.isFinite(offsetU) || !Float.isFinite(offsetV)
            || !Float.isFinite(scaleU) || !Float.isFinite(scaleV)
            || !Float.isFinite(rotation)) {
            throw new IllegalArgumentException("Texture transform contains a non-finite value");
        }
    }

    public GltfTextureInfo(int texture, int texCoord) {
        this(texture, texCoord, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f);
    }

    public static GltfTextureInfo absent() {
        return new GltfTextureInfo(-1, 0);
    }

    public boolean present() {
        return texture >= 0;
    }

    public boolean transformed() {
        return offsetU != 0.0f || offsetV != 0.0f
            || scaleU != 1.0f || scaleV != 1.0f || rotation != 0.0f;
    }

    /**
     * Applies this texture transform and writes the result into {@code output[0..1]}.
     */
    public void transform(float u, float v, float[] output) {
        if (output == null || output.length < 2) {
            throw new IllegalArgumentException("Texture-coordinate output must contain two values");
        }
        float scaledU = u * scaleU;
        float scaledV = v * scaleV;
        float cosine = (float) Math.cos(rotation);
        float sine = (float) Math.sin(rotation);
        output[0] = offsetU + cosine * scaledU - sine * scaledV;
        output[1] = offsetV + sine * scaledU + cosine * scaledV;
    }
}
