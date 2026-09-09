package org.lytharalab.gfbs.gltf.api.plugin;

import java.util.Optional;

/** Read-only plugin state exposed for diagnostics and integration UIs. */
public record GltfPluginInfo(String ownerModId, GltfPluginMetadata metadata,
                             GltfPluginState state, String detail, int loadOrder) {
    public Optional<String> failureDetail() {
        return detail == null || detail.isBlank() ? Optional.empty() : Optional.of(detail);
    }
}
