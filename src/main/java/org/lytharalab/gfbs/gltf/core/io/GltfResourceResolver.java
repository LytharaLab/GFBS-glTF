package org.lytharalab.gfbs.gltf.core.io;

import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface GltfResourceResolver {
    InputStream open(ResourceLocation location) throws IOException;
}
