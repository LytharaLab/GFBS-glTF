package org.lytharalab.gfbs.gltf.api.plugin;

/** A removable registration. Closing it is idempotent. */
public interface GltfExtensionRegistration<T> extends AutoCloseable {
    GltfExtensionPoint<T> point();
    GltfExtensionEntry<T> entry();
    boolean active();

    @Override
    void close();
}
