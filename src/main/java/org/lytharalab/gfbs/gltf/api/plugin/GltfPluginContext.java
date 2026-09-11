package org.lytharalab.gfbs.gltf.api.plugin;

import net.minecraftforge.api.distmarker.Dist;
import org.lytharalab.gfbs.gltf.api.io.ModelImporter;
import org.slf4j.Logger;

import java.util.List;

/** Capabilities scoped to the currently loading plugin. */
public interface GltfPluginContext {
    String ownerModId();
    GltfPluginMetadata metadata();
    Dist dist();
    boolean production();
    Logger logger();

    default boolean isClient() {
        return dist() == Dist.CLIENT;
    }

    default <T> GltfExtensionRegistration<T> register(GltfExtensionPoint<T> point, T extension) {
        return register(point, extension, 0);
    }

    <T> GltfExtensionRegistration<T> register(GltfExtensionPoint<T> point, T extension, int order);

    /** Registers an importer and automatically removes it if this plugin is rolled back. */
    GltfExtensionRegistration<ModelImporter> registerImporter(ModelImporter importer);

    <T> List<GltfExtensionEntry<T>> extensions(GltfExtensionPoint<T> point);
}
