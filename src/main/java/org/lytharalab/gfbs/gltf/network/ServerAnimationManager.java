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

/**
 * Stores authoritative clip state; bone matrices and render frames are never streamed.
 *
 * <p>Every timestamp comes from {@link ServerClock}, a monotonic seconds timeline owned by this mod.
 * Nothing here reads {@code Level#getGameTime()}, so world-time resets, {@code /time set} and tick
 * freezes can no longer move a running animation.</p>
 */
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
                                     float speed, LoopMode mode, double transition) {
        requireServerThread();
        requireDimension(level, target);
        double now = clock().nowSeconds();
        SyncedAnimationState state = new SyncedAnimationState(
            target,
            animation,
            now,
            0.0d,
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
        double now = clock().nowSeconds();
        double time = old.timeAt(now);
        update(level, new SyncedAnimationState(
            target,
            old.animation(),
            now,
            time,
            old.speed(),
            old.loopMode(),
            0.0d,
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
            clock().nowSeconds(),
            old.initialSeconds(),
            old.speed(),
            old.loopMode(),
            0.0d,
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
            clock().nowSeconds(),
            0.0d,
            1.0f,
            LoopMode.ONCE,
            0.0d,
            false,
            true,
            nextSequence()
        );
        broadcast(level, state);
    }

    public void sendSnapshot(ServerPlayer player) {
        requireServerThread();
        double sentAtSeconds = clock().nowSeconds();
        for (SyncedAnimationState state : states.values()) {
            if (state.target().dimension().equals(player.level().dimension().location())) {
                GltfNetwork.send(player, new AnimationStatePacket(state, sentAtSeconds));
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
        AnimationStatePacket packet = new AnimationStatePacket(
            state,
            ServerClock.get(level.getServer()).nowSeconds()
        );
        for (ServerPlayer player : level.players()) {
            GltfNetwork.send(player, packet);
        }
    }

    private ServerClock clock() {
        return ServerClock.get(requireServer());
    }

    private void requireServerThread() {
        MinecraftServer currentServer = requireServer();
        if (!currentServer.isSameThread()) {
            throw new IllegalStateException(
                "Server animation state must be changed on the Minecraft server thread"
            );
        }
    }

    private MinecraftServer requireServer() {
        MinecraftServer currentServer = server.get();
        if (currentServer == null) {
            throw new IllegalStateException("Minecraft server is no longer available");
        }
        return currentServer;
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
