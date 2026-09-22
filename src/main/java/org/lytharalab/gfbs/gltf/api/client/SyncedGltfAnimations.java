package org.lytharalab.gfbs.gltf.api.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lytharalab.gfbs.gltf.api.sync.AnimationTargetKey;
import org.lytharalab.gfbs.gltf.api.sync.SyncedAnimationState;
import org.lytharalab.gfbs.gltf.client.sync.ClientAnimationSync;
import org.lytharalab.gfbs.gltf.client.sync.ClientHeartbeat;

import java.util.Optional;

/** Binds a rendered instance to a server-authoritative animation target. */
@OnlyIn(Dist.CLIENT)
public final class SyncedGltfAnimations {
    private SyncedGltfAnimations() {
    }

    public static void bind(AnimationTargetKey target, GltfInstance instance) {
        ClientAnimationSync.bind(target, instance);
    }

    public static void unbind(AnimationTargetKey target) {
        ClientAnimationSync.unbind(target);
    }

    public static boolean isBound(AnimationTargetKey target) {
        return ClientAnimationSync.isBound(target);
    }

    public static Optional<SyncedAnimationState> state(AnimationTargetKey target) {
        return ClientAnimationSync.state(target);
    }

    public static boolean clockSynchronized() {
        return ClientAnimationSync.clockSynchronized();
    }

    /** Returns the filtered network RTT in milliseconds, or a negative value before the first probe. */
    public static double estimatedRoundTripMillis() {
        return ClientAnimationSync.estimatedRoundTripMillis();
    }

    /**
     * Returns the server's monotonic timeline position in seconds, or {@code NaN} before the first
     * state packet arrives. Replaces the tick-based {@code estimatedServerTick()} of 1.5.0.
     */
    public static double estimatedServerSeconds() {
        return ClientAnimationSync.estimatedServerSeconds();
    }

    /** Returns the mod-owned logical clock in seconds; it stops while the world is paused. */
    public static double logicalSeconds() {
        return ClientHeartbeat.logicalSeconds();
    }

    /** Number of fixed logical controller steps the heartbeat has executed. */
    public static long heartbeatSteps() {
        return ClientHeartbeat.steps();
    }
}
