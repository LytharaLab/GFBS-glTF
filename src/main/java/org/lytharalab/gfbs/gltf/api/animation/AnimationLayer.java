package org.lytharalab.gfbs.gltf.api.animation;

/** Immutable public state snapshot of one mixer layer. */
public record AnimationLayer(String name, String animation, float time, float weight,
                             boolean playing, AnimationBlendMode blendMode) {
}
