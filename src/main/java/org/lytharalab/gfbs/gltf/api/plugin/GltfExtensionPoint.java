package org.lytharalab.gfbs.gltf.api.plugin;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * A type-safe extension channel. Plugins may publish their own points as well as use the built-in
 * points, allowing cooperation without changes to GFBS:glTF itself.
 */
public record GltfExtensionPoint<T>(ResourceLocation id, Class<T> type, boolean multiple) {
    public GltfExtensionPoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }

    public static <T> GltfExtensionPoint<T> multiple(ResourceLocation id, Class<T> type) {
        return new GltfExtensionPoint<>(id, type, true);
    }

    public static <T> GltfExtensionPoint<T> single(ResourceLocation id, Class<T> type) {
        return new GltfExtensionPoint<>(id, type, false);
    }
}
