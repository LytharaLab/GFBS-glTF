package org.lytharalab.gfbs.gltf.api.animation;

/** Internal read-only access to the evaluated pose. Never mutate or expose this view. */
public final class AnimationPoseAccess {
    private AnimationPoseAccess() {}

    public static ModelPose pose(AnimationController controller) { return controller.poseView(); }
    public static boolean externallyWritable(AnimationController controller) {
        return controller.poseExposed();
    }
    public static NodePose nodeOrNull(ModelPose pose, int index) { return pose.nodeOrNull(index); }
}
