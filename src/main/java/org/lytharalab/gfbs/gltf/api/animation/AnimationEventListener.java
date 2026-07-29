package org.lytharalab.gfbs.gltf.api.animation;

@FunctionalInterface
public interface AnimationEventListener {
    void onAnimationEvent(String layer, AnimationClip clip, AnimationEvent event);
}
