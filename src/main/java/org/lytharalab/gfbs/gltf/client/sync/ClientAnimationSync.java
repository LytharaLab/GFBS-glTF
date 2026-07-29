package org.lytharalab.gfbs.gltf.client.sync;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.animation.PlaybackOptions;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.sync.AnimationTargetKey;
import org.lytharalab.gfbs.gltf.api.sync.SyncedAnimationState;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public final class ClientAnimationSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<AnimationTargetKey, WeakReference<GltfInstance>> INSTANCES = new HashMap<>();
    private static final Map<AnimationTargetKey, SyncedAnimationState> STATES = new HashMap<>();
    private static final Map<AnimationTargetKey, Long> SEQUENCES = new HashMap<>();

    private ClientAnimationSync() {
    }

    public static void bind(AnimationTargetKey key, GltfInstance instance) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(instance, "instance");
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> bind(key, instance));
            return;
        }
        if (minecraft.level == null || !key.dimension().equals(minecraft.level.dimension().location())) return;
        INSTANCES.put(key, new WeakReference<>(instance));
        SyncedAnimationState state = STATES.get(key);
        if (state != null) {
            apply(instance, state);
            if (state.stopped()) STATES.remove(key, state);
        }
    }

    public static void unbind(AnimationTargetKey key) {
        Objects.requireNonNull(key, "key");
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> unbind(key));
            return;
        }
        INSTANCES.remove(key);
    }

    public static void receive(SyncedAnimationState state) {
        Objects.requireNonNull(state, "state");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
            || !state.target().dimension().equals(minecraft.level.dimension().location())) return;
        long known = SEQUENCES.getOrDefault(state.target(), Long.MIN_VALUE);
        if (state.sequence() <= known) return;
        SEQUENCES.put(state.target(), state.sequence());
        STATES.put(state.target(), state);
        WeakReference<GltfInstance> reference = INSTANCES.get(state.target());
        GltfInstance instance = reference == null ? null : reference.get();
        if (instance == null) {
            if (reference != null) INSTANCES.remove(state.target());
        } else {
            apply(instance, state);
        }
        if (state.stopped() && instance != null) {
            // Keep the sequence tombstone until the dimension is cleared so an older packet
            // cannot resurrect a stopped animation. If no instance is bound yet, retain the
            // stopped state as well so a late-bound instance is reset to its default pose.
            STATES.remove(state.target(), state);
        }
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.isPaused()) return;
        ResourceLocation dimension = minecraft.level.dimension().location();
        long tick = minecraft.level.getGameTime();
        Iterator<Map.Entry<AnimationTargetKey, WeakReference<GltfInstance>>> iterator = INSTANCES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AnimationTargetKey, WeakReference<GltfInstance>> entry = iterator.next();
            GltfInstance instance = entry.getValue().get();
            if (instance == null) {
                iterator.remove();
                continue;
            }
            SyncedAnimationState state = STATES.get(entry.getKey());
            if (state == null || state.stopped() || !state.playing() || !state.target().dimension().equals(dimension)) continue;
            try {
                var expected = instance.asset().animation(state.animation()).orElse(null);
                if (expected != null && instance.animations().currentClip().orElse(null) == expected) {
                    instance.animations().seek(state.timeAt(tick));
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Could not resynchronize glTF animation target {}", state.target(), exception);
            }
        }
    }

    public static void clearDimension(ResourceLocation dimension) {
        INSTANCES.keySet().removeIf(key -> key.dimension().equals(dimension));
        STATES.keySet().removeIf(key -> key.dimension().equals(dimension));
        SEQUENCES.keySet().removeIf(key -> key.dimension().equals(dimension));
    }

    public static void clear() {
        INSTANCES.clear();
        STATES.clear();
        SEQUENCES.clear();
    }

    private static void apply(GltfInstance instance, SyncedAnimationState state) {
        try {
            if (state.stopped()) {
                instance.animations().stop(true);
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            long tick = minecraft.level == null ? state.serverStartTick() : minecraft.level.getGameTime();
            float time = state.timeAt(tick);
            double elapsed = Math.abs(((double) tick - (double) state.serverStartTick()) / 20.0d);
            float remainingTransition = (float) Math.max(0.0d, state.transitionSeconds() - elapsed);
            instance.animations().play(state.animation(), new PlaybackOptions(state.speed(), state.loopMode(),
                remainingTransition, time));
            if (!state.playing()) instance.animations().pause();
        } catch (RuntimeException exception) {
            LOGGER.error("Could not apply synchronized glTF animation {} to target {}",
                state.animation(), state.target(), exception);
        }
    }
}
