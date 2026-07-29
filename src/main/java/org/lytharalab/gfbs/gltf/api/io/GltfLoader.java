package org.lytharalab.gfbs.gltf.api.io;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;
import org.lytharalab.gfbs.gltf.core.io.JgltfAssetLoader;

import java.io.IOException;

/** Side-neutral entry point for synchronous glTF/GLB decoding. */
public final class GltfLoader {
    private GltfLoader() {
    }

    public static GltfAsset load(ResourceLocation location, GltfResolver resolver) throws IOException {
        return new JgltfAssetLoader().load(location, resolver::open);
    }

    public static GltfAsset load(ResourceLocation location, GltfResolver resolver,
                                 long maxResourceBytes) throws IOException {
        return new JgltfAssetLoader(maxResourceBytes).load(location, resolver::open);
    }
}
