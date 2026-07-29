package org.lytharalab.gfbs.gltf.api.io;

import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.model.GltfAsset;

import java.io.IOException;
import java.util.Collection;

/**
 * Extension point for formats which can be converted into GFBS' immutable asset model.
 */
public interface ModelImporter {
    Collection<String> extensions();

    GltfAsset load(ResourceLocation source, GltfResolver resolver) throws IOException;
}
