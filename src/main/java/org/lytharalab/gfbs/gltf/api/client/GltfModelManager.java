package org.lytharalab.gfbs.gltf.api.client;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface GltfModelManager {
    CompletableFuture<GltfAsset> load(ResourceLocation location);
    Optional<GltfAsset> getIfLoaded(ResourceLocation location);
    void invalidate(ResourceLocation location);
}
