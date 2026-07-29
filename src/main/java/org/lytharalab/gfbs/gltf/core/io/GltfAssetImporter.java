package org.lytharalab.gfbs.gltf.core.io;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.io.GltfResolver;
import org.lytharalab.gfbs.gltf.api.io.ModelImporter;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.io.IOException;
import java.util.List;

public final class GltfAssetImporter implements ModelImporter {
    @Override
    public List<String> extensions() {
        return List.of("gltf", "glb");
    }

    @Override
    public GltfAsset load(ResourceLocation source, GltfResolver resolver) throws IOException {
        return new JgltfAssetLoader().load(source, resolver::open);
    }
}
