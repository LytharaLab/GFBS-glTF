package org.lytharalab.gfbs.gltf.api.plugin;

/** Observable lifecycle state of a registered plugin. */
public enum GltfPluginState {
    REGISTERED,
    BLOCKED,
    LOADING,
    ACTIVE,
    FAILED,
    UNLOADED
}
