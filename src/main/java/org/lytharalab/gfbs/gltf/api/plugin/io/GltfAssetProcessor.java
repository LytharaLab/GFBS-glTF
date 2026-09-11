package org.lytharalab.gfbs.gltf.api.plugin.io;

import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.io.IOException;

/** Transforms or validates an imported immutable asset before it enters the model cache. */
@FunctionalInterface
public interface GltfAssetProcessor {
    GltfAsset process(GltfAsset asset, GltfAssetProcessingContext context) throws IOException;
}
