package org.lytharalab.gfbs.gltf.api.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncedAnimationStateTest {
    private static final AnimationTargetKey TARGET = new AnimationTargetKey(
        ResourceLocation.fromNamespaceAndPath("test", "dimension"),
        AnimationTargetKey.Kind.CUSTOM,
        "door"
    );

    @Test
    void evaluatesTheTimelineInSecondsWithoutFloatQuantization() {
        SyncedAnimationState state = new SyncedAnimationState(
            TARGET, "open", 100.0d, 0.0d, 1.0f, LoopMode.ONCE, 0.2d, true, false, 1L
        );

        assertEquals(0.025d, state.timeAt(100.025d), 1.0e-12d);
        assertEquals(0.175d, state.remainingTransitionAt(100.025d), 1.0e-12d);
    }

    @Test
    void keepsSubMillisecondResolutionAtElevenDaysOfLogicalTime() {
        SyncedAnimationState state = new SyncedAnimationState(
            TARGET, "spin", 0.0d, 1_000_000.0d, 1.0f, LoopMode.LOOP, 0.0d, true, false, 2L
        );

        double first = state.timeAt(0.02d);
        double second = state.timeAt(0.03d);

        assertEquals(1_000_000.02d, first, 1.0e-9d);
        assertEquals(0.01d, second - first, 1.0e-9d);
        // 1.5.0 returned a float here and snapped this timestamp onto a 62.5 ms grid:
        assertEquals(1_000_000.0f, 1_000_000.02f);
    }

    @Test
    void frozenStatesReturnTheirStoredTime() {
        SyncedAnimationState paused = new SyncedAnimationState(
            TARGET, "open", 10.0d, 2.5d, 1.0f, LoopMode.ONCE, 0.0d, false, false, 3L
        );

        assertEquals(2.5d, paused.timeAt(999.0d), 1.0e-12d);
    }

    @Test
    void nbtRoundTripPreservesTheDoubleTimeline() {
        SyncedAnimationState state = new SyncedAnimationState(
            TARGET, "open", 12_345.6789d, 0.03125d, 2.0f, LoopMode.LOOP, 0.25d, true, false, 7L
        );

        SyncedAnimationState loaded = SyncedAnimationState.load(state.save());

        assertEquals(state.startSeconds(), loaded.startSeconds(), 1.0e-12d);
        assertEquals(state.initialSeconds(), loaded.initialSeconds(), 1.0e-12d);
        assertEquals(state.transitionSeconds(), loaded.transitionSeconds(), 1.0e-12d);
        assertEquals(state.sequence(), loaded.sequence());
    }

    @Test
    void legacyTickSavesStillLoad() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("dimension", "test:dimension");
        legacy.putString("kind", AnimationTargetKey.Kind.CUSTOM.name());
        legacy.putString("target", "door");
        legacy.putString("animation", "open");
        legacy.putLong("startTick", 400L);
        legacy.putFloat("initialSeconds", 0.0f);
        legacy.putFloat("speed", 1.0f);
        legacy.putString("loopMode", LoopMode.ONCE.name());
        legacy.putFloat("transitionSeconds", 0.2f);
        legacy.putBoolean("playing", true);
        legacy.putBoolean("stopped", false);
        legacy.putLong("sequence", 1L);

        SyncedAnimationState loaded = SyncedAnimationState.load(legacy);

        assertEquals(20.0d, loaded.startSeconds(), 1.0e-9d);
        assertEquals(0.2d, loaded.transitionSeconds(), 1.0e-6d);
    }

    @Test
    void rejectsInvalidPlaybackValues() {
        assertThrows(IllegalArgumentException.class, () -> new SyncedAnimationState(
            TARGET, "open", 0.0d, 0.0d, 0.0f, LoopMode.ONCE, 0.0d, true, false, 1L
        ));
        assertThrows(IllegalArgumentException.class, () -> new SyncedAnimationState(
            TARGET, "open", Double.NaN, 0.0d, 1.0f, LoopMode.ONCE, 0.0d, true, false, 1L
        ));
        assertThrows(IllegalArgumentException.class, () -> new SyncedAnimationState(
            TARGET, "open", 0.0d, 0.0d, 1.0f, LoopMode.ONCE, 0.0d, true, true, 1L
        ));
        assertThrows(IllegalStateException.class, () -> {
            SyncedAnimationState runaway = new SyncedAnimationState(
                TARGET, "open", 0.0d, 0.0d, 1.0f, LoopMode.ONCE, 0.0d, true, false, 1L
            );
            runaway.timeAt(SyncedAnimationState.MAX_LOGICAL_SECONDS * 2.0d);
        });
    }
}
