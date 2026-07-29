package org.lytharalab.gfbs.gltf.api.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lytharalab.gfbs.gltf.api.sync.AnimationTargetKey;
import org.lytharalab.gfbs.gltf.client.sync.ClientAnimationSync;

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
}
