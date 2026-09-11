package org.lytharalab.gfbs.gltf.api.client.plugin;

import net.minecraft.resources.ResourceLocation;

/** Declarative custom pass. Return {@code null} from {@link #layer} to skip a primitive. */
public interface GltfCustomRenderPass {
    ResourceLocation id();

    GltfRenderLayer layer(GltfPrimitiveRenderContext primitive) throws Exception;
}
