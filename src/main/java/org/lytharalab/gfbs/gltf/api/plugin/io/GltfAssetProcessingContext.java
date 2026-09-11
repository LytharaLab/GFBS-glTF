package org.lytharalab.gfbs.gltf.api.plugin.io;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.io.GltfResolver;
import org.lytharalab.gfbs.gltf.api.io.ModelImporter;

import java.util.Objects;

/** Input provenance and resolver supplied to asset processors. */
public record GltfAssetProcessingContext(ResourceLocation source, GltfResolver resolver,
                                         ModelImporter importer) {
    public GltfAssetProcessingContext {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(importer, "importer");
    }
}
