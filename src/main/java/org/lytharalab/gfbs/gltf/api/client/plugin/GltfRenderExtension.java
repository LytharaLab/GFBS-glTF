package org.lytharalab.gfbs.gltf.api.client.plugin;

/**
 * Stage hook for adding passes or replacing built-in model passes without modifying the renderer.
 * An exception quarantines the extension for the rest of the game session.
 */
@FunctionalInterface
public interface GltfRenderExtension {
    void render(GltfRenderStage stage, GltfRenderFrame frame) throws Exception;
}
