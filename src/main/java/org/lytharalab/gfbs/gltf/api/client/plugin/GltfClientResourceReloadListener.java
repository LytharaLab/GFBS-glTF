package org.lytharalab.gfbs.gltf.api.client.plugin;

import net.minecraft.server.packs.resources.ResourceManager;

/** Resource lifecycle hook for plugin-owned client caches, shaders, and textures. */
public interface GltfClientResourceReloadListener {
    default void beforeGltfResourcesReload(ResourceManager resources) throws Exception {
    }

    default void afterGltfResourcesReload(ResourceManager resources) throws Exception {
    }
}
