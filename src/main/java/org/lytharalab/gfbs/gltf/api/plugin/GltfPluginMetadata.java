package org.lytharalab.gfbs.gltf.api.plugin;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable identity, version, and dependency declaration for a plugin. */
public record GltfPluginMetadata(ResourceLocation id, String version, String displayName,
                                 String description, List<GltfPluginDependency> dependencies) {
    public GltfPluginMetadata {
        Objects.requireNonNull(id, "id");
        version = requireText(version, "version");
        displayName = requireText(displayName, "displayName");
        description = Objects.requireNonNull(description, "description");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        for (GltfPluginDependency dependency : dependencies) {
            if (dependency.id().equals(id)) {
                throw new IllegalArgumentException("A plugin cannot depend on itself: " + id);
            }
        }
    }

    public static Builder builder(ResourceLocation id, String version) {
        return new Builder(id, version);
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) throw new IllegalArgumentException(label + " is blank");
        return value;
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final String version;
        private String displayName;
        private String description = "";
        private final List<GltfPluginDependency> dependencies = new ArrayList<>();

        private Builder(ResourceLocation id, String version) {
            this.id = Objects.requireNonNull(id, "id");
            this.version = requireText(version, "version");
            this.displayName = id.toString();
        }

        public Builder displayName(String value) {
            displayName = requireText(value, "displayName");
            return this;
        }

        public Builder description(String value) {
            description = Objects.requireNonNull(value, "description");
            return this;
        }

        public Builder requires(ResourceLocation pluginId, String versionRange) {
            dependencies.add(GltfPluginDependency.required(pluginId, versionRange));
            return this;
        }

        public Builder optional(ResourceLocation pluginId, String versionRange) {
            dependencies.add(GltfPluginDependency.optional(pluginId, versionRange));
            return this;
        }

        public Builder dependency(GltfPluginDependency dependency) {
            dependencies.add(Objects.requireNonNull(dependency, "dependency"));
            return this;
        }

        public GltfPluginMetadata build() {
            return new GltfPluginMetadata(id, version, displayName, description, dependencies);
        }
    }
}
