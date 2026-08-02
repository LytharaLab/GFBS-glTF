package org.lytharalab.gfbs.gltf.api.sync;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncedAnimationStateTest {
    @Test
    void evaluatesFractionalServerTicksWithoutTwentyHertzQuantization() {
        AnimationTargetKey target = new AnimationTargetKey(
            ResourceLocation.fromNamespaceAndPath("test", "dimension"),
            AnimationTargetKey.Kind.CUSTOM,
            "door"
        );
        SyncedAnimationState state = new SyncedAnimationState(
            target, "open", 100L, 0.0f, 1.0f,
            LoopMode.ONCE, 0.2f, true, false, 1L
        );

        assertEquals(0.025f, state.timeAt(100.5d), 0.000001f);
        assertEquals(0.175f, state.remainingTransitionAt(100.5d), 0.000001f);
    }
}
