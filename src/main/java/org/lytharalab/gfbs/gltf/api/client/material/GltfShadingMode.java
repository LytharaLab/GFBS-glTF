package org.lytharalab.gfbs.gltf.api.client.material;

/**
 * Runtime shading policy applied on top of a glTF material.
 *
 * <p>{@link #NEON} is a full-bright solid surface plus a dedicated emissive pass. It is intended
 * for game-style material switching and bloom; it does not create a Minecraft block light or a
 * dynamic point light.</p>
 */
public enum GltfShadingMode {
    /** Normal world-lit metallic/roughness rendering. */
    PBR,
    /** Full-bright base rendering while retaining explicitly configured emissive properties. */
    UNLIT,
    /** Full-bright base rendering with the effective base color/texture also used for emission. */
    NEON
}
