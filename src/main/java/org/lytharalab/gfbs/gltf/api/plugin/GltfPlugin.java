package org.lytharalab.gfbs.gltf.api.plugin;

/**
 * One independently addressable GFBS:glTF plugin.
 *
 * <p>A Forge mod may register any number of plugin instances. Implementations should keep their
 * common entry point safe to load on a dedicated server and register client-only extensions only
 * when {@link GltfPluginContext#isClient()} is true.</p>
 */
public interface GltfPlugin {
    GltfPluginMetadata metadata();

    /** Registers extensions and performs plugin initialization. */
    void onLoad(GltfPluginContext context) throws Exception;

    /** Called after every loadable plugin has completed {@link #onLoad(GltfPluginContext)}. */
    default void onReady(GltfPluginContext context) throws Exception {
    }

    /** Called during rollback, explicit disable, or host shutdown. */
    default void onUnload(GltfPluginContext context) throws Exception {
    }
}
