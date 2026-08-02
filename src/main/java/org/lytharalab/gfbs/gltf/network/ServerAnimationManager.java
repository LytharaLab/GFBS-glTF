package org.lytharalab.gfbs.gltf.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;
import org.lytharalab.gfbs.gltf.api.sync.AnimationTargetKey;
import org.lytharalab.gfbs.gltf.api.sync.SyncedAnimationState;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Stores authoritative clip state; bone matrices and render frames are never streamed. */
public final class ServerAnimationManager {
    private static final Map<MinecraftServer, ServerAnimationManager> INSTANCES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private final WeakReference<MinecraftServer> server;
    private final Map<AnimationTargetKey, SyncedAnimationState> states = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private ServerAnimationManager(MinecraftServer server) {
        this.server = new WeakReference<>(server);
    }

    public static ServerAnimationManager get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(server, ServerAnimationManager::new);
        }
    }

    public static void remove(MinecraftServer server) {
        synchronized (INSTANCES) {
            INSTANCES.remove(server);
        }
    }

    public SyncedAnimationState play(ServerLevel level, AnimationTargetKey target, String animation,
                                     float speed, LoopMode mode, float transition) {
        requireServerThread();
        requireDimension(level, target);
        SyncedAnimationState state = new SyncedAnimationState(
            target,
            animation,
            level.getGameTime(),
            0.0f,
            speed,
            mode,
            transition,
            true,
            false,
            nextSequence()
        );
        states.put(target, state);
        broadcast(level, state);
        return state;
    }

    public void pause(ServerLevel level, AnimationTargetKey target) {
        requireServerThread();
        requireDimension(level, target);
        SyncedAnimationState old = states.get(target);
        if (old == null || old.stopped() || !old.playing()) {
            return;
        }
        float time = old.timeAt(level.getGameTime());
        update(level, new SyncedAnimationState(
            target,
            old.animation(),
            level.getGameTime(),
            time,
            old.speed(),
            old.loopMode(),
            0.0f,
            false,
            false,
            nextSequence()
        ));
    }

    public void resume(ServerLevel level, AnimationTargetKey target) {
        requireServerThread();
        requireDimension(level, target);
        SyncedAnimationState old = states.get(target);
        if (old == null || old.stopped() || old.playing()) {
            return;
        }
        update(level, new SyncedAnimationState(
            target,
            old.animation(),
            level.getGameTime(),
            old.initialSeconds(),
            old.speed(),
            old.loopMode(),
            0.0f,
            true,
            false,
            nextSequence()
        ));
    }

    public void stop(ServerLevel level, AnimationTargetKey target) {
        requireServerThread();
        requireDimension(level, target);
        SyncedAnimationState old = states.remove(target);
        String animation = old == null ? "" : old.animation();
        SyncedAnimationState state = new SyncedAnimationState(
            target,
            animation,
            level.getGameTime(),
            0.0f,
            1.0f,
            LoopMode.ONCE,
            0.0f,
            false,
            true,
            nextSequence()
        );
        broadcast(level, state);
    }

    public void sendSnapshot(ServerPlayer player) {
        requireServerThread();
        long dispatchTick = player.serverLevel().getGameTime();
        for (SyncedAnimationState state : states.values()) {
            if (state.target().dimension().equals(player.level().dimension().location())) {
                GltfNetwork.send(player, new AnimationStatePacket(state, dispatchTick));
            }
        }
    }

    public Collection<SyncedAnimationState> states() {
        requireServerThread();
        return List.copyOf(states.values());
    }

    private void update(ServerLevel level, SyncedAnimationState state) {
        states.put(state.target(), state);
        broadcast(level, state);
    }

    private static void broadcast(ServerLevel level, SyncedAnimationState state) {
        AnimationStatePacket packet = new AnimationStatePacket(state, level.getGameTime());
        for (ServerPlayer player : level.players()) {
            GltfNetwork.send(player, packet);
        }
    }

    private void requireServerThread() {
        MinecraftServer currentServer = server.get();
        if (currentServer == null) {
            throw new IllegalStateException("Minecraft server is no longer available");
        }
        if (!currentServer.isSameThread()) {
            throw new IllegalStateException(
                "Server animation state must be changed on the Minecraft server thread"
            );
        }
    }

    private long nextSequence() {
        long next = sequence.incrementAndGet();
        if (next < 0L) {
            throw new IllegalStateException("Animation sequence overflow");
        }
        return next;
    }

    private static void requireDimension(ServerLevel level, AnimationTargetKey target) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(target, "target");
        if (!level.dimension().location().equals(target.dimension())) {
            throw new IllegalArgumentException("Animation target belongs to another dimension");
        }
    }
}
