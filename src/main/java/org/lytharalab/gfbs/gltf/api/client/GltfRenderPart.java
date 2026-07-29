package org.lytharalab.gfbs.gltf.api.client;

/**
 * Stable description passed to part filters and RenderType factories.
 */
public record GltfRenderPart(int nodeIndex, String nodeName,
                             int meshIndex, int primitiveIndex,
                             int materialIndex) {
}
