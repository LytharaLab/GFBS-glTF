package org.lytharalab.gfbs.gltf.api.plugin;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** A required or optional dependency on another GFBS:glTF plugin. */
public record GltfPluginDependency(ResourceLocation id, String versionRange, boolean required) {
    public GltfPluginDependency {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(versionRange, "versionRange");
        if (versionRange.isBlank()) throw new IllegalArgumentException("Version range is blank");
    }

    public static GltfPluginDependency required(ResourceLocation id, String versionRange) {
        return new GltfPluginDependency(id, versionRange, true);
    }

    public static GltfPluginDependency optional(ResourceLocation id, String versionRange) {
        return new GltfPluginDependency(id, versionRange, false);
    }
}
