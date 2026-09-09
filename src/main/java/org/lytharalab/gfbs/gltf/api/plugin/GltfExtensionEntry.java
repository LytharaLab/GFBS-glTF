package org.lytharalab.gfbs.gltf.api.plugin;

import net.minecraft.resources.ResourceLocation;

/** An extension together with its owner and deterministic execution order. */
public record GltfExtensionEntry<T>(ResourceLocation pluginId, T extension, int order) {
}
