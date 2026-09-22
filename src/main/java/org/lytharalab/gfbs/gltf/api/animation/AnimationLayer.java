package org.lytharalab.gfbs.gltf.api.animation;

/**
 * Immutable public state snapshot of one mixer layer.
 *
 * <p>{@code time} is the exact double playhead of the track; casting it to {@code float} is left to
 * callers that only display it.</p>
 */
public record AnimationLayer(String name, String animation, double time, float weight,
                             boolean playing, AnimationBlendMode blendMode) {
}
