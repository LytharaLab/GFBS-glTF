package org.lytharalab.gfbs.gltf.api.sync;

import net.minecraft.server.level.ServerLevel;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;
import org.lytharalab.gfbs.gltf.network.ServerAnimationManager;

public final class ServerAnimations {
    private ServerAnimations() {
    }

    public static SyncedAnimationState play(ServerLevel level, AnimationTargetKey target, String animation,
                                             float speed, LoopMode loopMode, float transitionSeconds) {
        return ServerAnimationManager.get(level.getServer()).play(level, target, animation, speed, loopMode, transitionSeconds);
    }

    public static void pause(ServerLevel level, AnimationTargetKey target) {
        ServerAnimationManager.get(level.getServer()).pause(level, target);
    }

    public static void resume(ServerLevel level, AnimationTargetKey target) {
        ServerAnimationManager.get(level.getServer()).resume(level, target);
    }

    public static void stop(ServerLevel level, AnimationTargetKey target) {
        ServerAnimationManager.get(level.getServer()).stop(level, target);
    }
}
