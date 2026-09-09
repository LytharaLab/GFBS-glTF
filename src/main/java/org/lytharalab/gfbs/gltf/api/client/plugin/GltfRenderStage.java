package org.lytharalab.gfbs.gltf.api.client.plugin;

/** Stable insertion points around the built-in rendering passes. */
public enum GltfRenderStage {
    BEFORE_BUILTIN_PASSES,
    AFTER_BASE_PASS,
    AFTER_EMISSIVE_PASS,
    AFTER_SHADOW_PASS,
    AFTER_RENDER
}
