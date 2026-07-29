package org.lytharalab.gfbs.gltf.core.io;

import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

public final class GltfLoadException extends IOException {
    private static final long serialVersionUID = 1L;

    public GltfLoadException(ResourceLocation asset, String message) {
        super("Failed to load " + asset + ": " + message);
    }

    public GltfLoadException(ResourceLocation asset, String message, Throwable cause) {
        super("Failed to load " + asset + ": " + message, cause);
    }

    public GltfLoadException(String message) {
        super(message);
    }

    public GltfLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
