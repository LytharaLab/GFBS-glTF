package org.lytharalab.gfbs.gltf.api.client;

/**
 * Stable description passed to part filters and RenderType factories.
 * {@code materialIndex} is the effective source material after instance-level switching.
 */
public record GltfRenderPart(int nodeIndex, String nodeName,
                             int meshIndex, int primitiveIndex,
                             int materialIndex) {
}
