package org.lytharalab.gfbs.gltf.api.io;

import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;

/** Opens glTF roots and their namespace-local external buffers or images. */
@FunctionalInterface
public interface GltfResolver {
    InputStream open(ResourceLocation location) throws IOException;
}
