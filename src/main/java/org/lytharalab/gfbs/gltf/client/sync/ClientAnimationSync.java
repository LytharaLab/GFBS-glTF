package org.lytharalab.gfbs.gltf.client.sync;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lytharalab.gfbs.gltf.api.animation.AnimationClip;
import org.lytharalab.gfbs.gltf.api.animation.LoopMode;
import org.lytharalab.gfbs.gltf.api.animation.PlaybackOptions;
import org.lytharalab.gfbs.gltf.api.client.GltfInstance;
import org.lytharalab.gfbs.gltf.api.sync.AnimationTargetKey;
import org.lytharalab.gfbs.gltf.api.sync.SyncedAnimationState;
import org.lytharalab.gfbs.gltf.network.AnimationClockRequestPacket;
import org.lytharalab.gfbs.gltf.network.GltfNetwork;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Client implementation of the server-authoritative animation timeline.
 *
 * <p>The timeline is expressed in monotonic seconds and advanced by {@link ClientHeartbeat}, a
 * lifecycle driver owned by this mod: no {@code TickEvent} is involved and no tick counter is read,
 * so world-time resets and TPS changes cannot move a running animation. Packets establish state and
 * offset anchors; the pose keeps advancing at render frequency while a fixed-rate feedback
 * controller makes small playback-speed adjustments to absorb latency.</p>
 */
public final class ClientAnimationSync {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double PHASE_DEAD_ZONE_SECONDS = 0.015d;
    private static final double PHASE_GAIN = 0.45d;
    private static final double MAX_RELATIVE_SPEED_CORRECTION = 0.35d;
    private static final double MIN_ABSOLUTE_SPEED_CORRECTION = 0.12d;
    private static final double MIN_SPEED_FACTOR = 0.35d;
    private static final double RECOVERY_BLEND_SECONDS = 0.18d;
    private static final double LATE_PACKET_BLEND_SECONDS = 0.10d;
    private static final double RECOVERY_THRESHOLD_SECONDS = 1.50d;
    private static final long RECOVERY_COOLDOWN_NANOS = 5_000_000_000L;
    private static final long CLIP_REPAIR_COOLDOWN_NANOS = 250_000_000L;

    private static final Map<AnimationTargetKey, Binding> BINDINGS = new HashMap<>();
    private static final Map<AnimationTargetKey, SyncedAnimationState> STATES = new HashMap<>();
    private static final Map<AnimationTargetKey, Long> SEQUENCES = new HashMap<>();
    private static final ServerTimeEstimator CLOCK = new ServerTimeEstimator();

    private static long nextClockNonce;
    private static long latestClockNonceReceived = Long.MIN_VALUE;

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
        if (minecraft.level == null
            || !key.dimension().equals(minecraft.level.dimension().location())) {
            return;
        }

        Binding binding = new Binding(instance);
        BINDINGS.put(key, binding);
        SyncedAnimationState state = STATES.get(key);
        if (state != null) {
            applyState(binding, state, System.nanoTime());
            if (state.stopped()) {
                STATES.remove(key, state);
            }
        }
    }

    public static void unbind(AnimationTargetKey key) {
        Objects.requireNonNull(key, "key");
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> unbind(key));
            return;
        }
        BINDINGS.remove(key);
    }

    /**
     * Receives one authoritative state.
     *
     * @param sentAtSeconds server timeline position when the packet left the server
     */
    public static void receive(SyncedAnimationState state, double sentAtSeconds) {
        Objects.requireNonNull(state, "state");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
            || !state.target().dimension().equals(minecraft.level.dimension().location())) {
            return;
        }

        long logicalNanos = ClientHeartbeat.logicalNanos();
        CLOCK.observeServerPacket(sentAtSeconds, logicalNanos);

        long known = SEQUENCES.getOrDefault(state.target(), Long.MIN_VALUE);
        if (state.sequence() <= known) {
            return;
        }
        SEQUENCES.put(state.target(), state.sequence());
        STATES.put(state.target(), state);

        Binding binding = BINDINGS.get(state.target());
        if (binding != null) {
            GltfInstance instance = binding.instance.get();
            if (instance == null) {
                BINDINGS.remove(state.target());
            } else {
                applyState(binding, state, System.nanoTime());
                if (state.stopped()) {
                    STATES.remove(state.target(), state);
                }
            }
        }
    }

    public static void receiveClockSample(long nonce, long clientSendNanos, double serverSeconds) {
        if (nonce <= latestClockNonceReceived) {
            return;
        }
        latestClockNonceReceived = nonce;
        long rttNanos = Math.max(0L, System.nanoTime() - clientSendNanos);
        CLOCK.observePong(rttNanos, ClientHeartbeat.logicalNanos(), serverSeconds);
    }

    /** Sends one clock probe; the cadence is owned by {@link ClientHeartbeat}. */
    static void sendClockProbe() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        GltfNetwork.sendToServer(new AnimationClockRequestPacket(++nextClockNonce, System.nanoTime()));
    }

    /**
     * One fixed logical controller step. The step rate belongs to {@link ClientHeartbeat}, so the
     * smoothing constants below are calibrated for its 20 Hz cadence.
     */
    static void step(long logicalNanos) {
        if (BINDINGS.isEmpty()) {
            return;
        }
        ResourceLocation dimension = currentDimension();
        if (dimension == null) {
            return;
        }
        long wallNanos = System.nanoTime();
        Iterator<Map.Entry<AnimationTargetKey, Binding>> iterator = BINDINGS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AnimationTargetKey, Binding> entry = iterator.next();
            GltfInstance instance = entry.getValue().instance.get();
            if (instance == null) {
                iterator.remove();
                continue;
            }
            SyncedAnimationState state = STATES.get(entry.getKey());
            if (state == null || !state.target().dimension().equals(dimension)) {
                continue;
            }
            try {
                synchronizeBinding(entry.getValue(), instance, state, logicalNanos, wallNanos);
            } catch (RuntimeException exception) {
                LOGGER.error(
                    "Could not synchronize glTF animation {} for target {}",
                    state.animation(),
                    state.target(),
                    exception
                );
            }
        }
    }

    /** Discards offset anchors after a pause, a dimension change or a session change. */
    static void onTimelineRestart() {
        CLOCK.reset();
        for (Binding binding : BINDINGS.values()) {
            binding.smoothedSpeed = Float.NaN;
        }
    }

    public static void clearDimension(ResourceLocation dimension) {
        BINDINGS.keySet().removeIf(key -> key.dimension().equals(dimension));
        STATES.keySet().removeIf(key -> key.dimension().equals(dimension));
        SEQUENCES.keySet().removeIf(key -> key.dimension().equals(dimension));
    }

    public static void clear() {
        BINDINGS.clear();
        STATES.clear();
        SEQUENCES.clear();
        CLOCK.reset();
        ClientHeartbeat.reset();
    }

    public static boolean isBound(AnimationTargetKey target) {
        Binding binding = BINDINGS.get(target);
        return binding != null && binding.instance.get() != null;
    }

    public static Optional<SyncedAnimationState> state(AnimationTargetKey target) {
        return Optional.ofNullable(STATES.get(target));
    }

    public static boolean clockSynchronized() {
        return CLOCK.synchronizedClock();
    }

    public static double estimatedRoundTripMillis() {
        return CLOCK.roundTripMillis();
    }

    /** Best estimate of the server's monotonic timeline in seconds, or {@code NaN} before sync. */
    public static double estimatedServerSeconds() {
        return CLOCK.estimate(ClientHeartbeat.logicalNanos());
    }

    private static ResourceLocation currentDimension() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : minecraft.level.dimension().location();
    }

    private static void synchronizeBinding(Binding binding, GltfInstance instance,
                                           SyncedAnimationState state, long logicalNanos,
                                           long wallNanos) {
        if (binding.appliedSequence != state.sequence()) {
            applyState(binding, state, wallNanos);
            return;
        }
        if (state.stopped()) {
            return;
        }

        AnimationClip expectedClip = instance.asset().animation(state.animation()).orElse(null);
        if (expectedClip == null) {
            throw new IllegalArgumentException("Unknown synchronized animation: " + state.animation());
        }
        AnimationClip currentClip = instance.animations().currentClip().orElse(null);
        if (currentClip != expectedClip) {
            if (wallNanos - binding.lastClipRepairNanos >= CLIP_REPAIR_COOLDOWN_NANOS) {
                applyState(binding, state, wallNanos);
            }
            return;
        }

        // The timeline is real time on both sides, so the authoritative speed needs no TPS scaling.
        float authoritativeSpeed = state.speed();
        if (!state.playing()) {
            instance.animations().setSpeed(authoritativeSpeed);
            instance.animations().pause();
            binding.smoothedSpeed = authoritativeSpeed;
            return;
        }

        double serverSeconds = CLOCK.estimate(logicalNanos);
        if (!Double.isFinite(serverSeconds)) {
            return;
        }
        double duration = (double) expectedClip.duration();
        double expectedTime = normalizeTime(state.timeAt(serverSeconds), duration, state.loopMode());
        double actualTime = instance.animations().time();
        double phaseError = phaseError(expectedTime, actualTime, duration, state.loopMode());

        if (!instance.animations().isPlaying()
            && !isFinishedOnce(expectedTime, duration, state.speed(), state.loopMode())) {
            instance.animations().resume();
        }

        double recoveryThreshold = recoveryThreshold(duration, state.loopMode());
        if (Math.abs(phaseError) > recoveryThreshold
            && wallNanos - binding.lastRecoveryNanos >= RECOVERY_COOLDOWN_NANOS) {
            recoverSmoothly(binding, instance, state, expectedTime, wallNanos);
            return;
        }

        float targetSpeed = correctedSpeed(authoritativeSpeed, phaseError);
        if (!Float.isFinite(binding.smoothedSpeed)) {
            binding.smoothedSpeed = authoritativeSpeed;
        }
        float smoothing = Math.abs(phaseError) > 0.50d ? 0.35f : 0.18f;
        binding.smoothedSpeed += (targetSpeed - binding.smoothedSpeed) * smoothing;
        instance.animations().setSpeed(binding.smoothedSpeed);
    }

    private static void applyState(Binding binding, SyncedAnimationState state, long wallNanos) {
        GltfInstance instance = binding.instance.get();
        if (instance == null) {
            return;
        }
        try {
            if (state.stopped()) {
                instance.animations().stop(true);
                binding.appliedSequence = state.sequence();
                binding.smoothedSpeed = Float.NaN;
                binding.lastClipRepairNanos = wallNanos;
                return;
            }

            double serverSeconds = CLOCK.estimate(ClientHeartbeat.logicalNanos());
            if (!Double.isFinite(serverSeconds)) {
                serverSeconds = state.startSeconds();
            }
            double time = state.timeAt(serverSeconds);
            double remainingTransition = state.remainingTransitionAt(serverSeconds);
            double stateAgeSeconds = Math.max(0.0d, serverSeconds - state.startSeconds());
            if (remainingTransition <= 0.0d && stateAgeSeconds > 0.05d) {
                // The client cannot display a command before it arrives. A very short pose blend
                // hides that unavoidable late arrival without moving the authoritative timeline.
                remainingTransition = LATE_PACKET_BLEND_SECONDS;
            }

            instance.animations().play(
                state.animation(),
                new PlaybackOptions(
                    state.speed(),
                    state.loopMode(),
                    remainingTransition,
                    time
                )
            );
            float authoritativeSpeed = state.speed();
            instance.animations().setSpeed(authoritativeSpeed);
            if (!state.playing()) {
                instance.animations().pause();
            }

            binding.appliedSequence = state.sequence();
            binding.smoothedSpeed = authoritativeSpeed;
            binding.lastClipRepairNanos = wallNanos;
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Could not apply synchronized glTF animation {} to target {}",
                state.animation(),
                state.target(),
                exception
            );
        }
    }

    private static void recoverSmoothly(Binding binding, GltfInstance instance,
                                        SyncedAnimationState state, double expectedTime,
                                        long wallNanos) {
        instance.animations().play(
            state.animation(),
            new PlaybackOptions(
                state.speed(),
                state.loopMode(),
                RECOVERY_BLEND_SECONDS,
                expectedTime
            )
        );
        float authoritativeSpeed = state.speed();
        instance.animations().setSpeed(authoritativeSpeed);
        binding.smoothedSpeed = authoritativeSpeed;
        binding.lastRecoveryNanos = wallNanos;
        binding.lastClipRepairNanos = wallNanos;
    }

    private static float correctedSpeed(float authoritativeSpeed, double phaseError) {
        if (authoritativeSpeed == 0.0f || Math.abs(phaseError) <= PHASE_DEAD_ZONE_SECONDS) {
            return authoritativeSpeed;
        }
        double maxCorrection = Math.max(
            Math.abs((double) authoritativeSpeed) * MAX_RELATIVE_SPEED_CORRECTION,
            MIN_ABSOLUTE_SPEED_CORRECTION
        );
        double correction = clamp(
            phaseError * PHASE_GAIN,
            -maxCorrection,
            maxCorrection
        );
        double corrected = (double) authoritativeSpeed + correction;
        double minimumMagnitude = Math.abs((double) authoritativeSpeed) * MIN_SPEED_FACTOR;
        if (authoritativeSpeed > 0.0f) {
            corrected = Math.max(minimumMagnitude, corrected);
        } else {
            corrected = Math.min(-minimumMagnitude, corrected);
        }
        return (float) corrected;
    }

    private static double phaseError(double expected, double actual, double duration,
                                     LoopMode loopMode) {
        double error = expected - actual;
        if (loopMode == LoopMode.LOOP && duration > 0.0d) {
            double half = duration * 0.5d;
            while (error > half) {
                error -= duration;
            }
            while (error < -half) {
                error += duration;
            }
        }
        return error;
    }

    private static double normalizeTime(double time, double duration, LoopMode loopMode) {
        if (loopMode == LoopMode.LOOP && duration > 0.0d) {
            double result = time % duration;
            return result < 0.0d ? result + duration : result;
        }
        return Math.max(0.0d, Math.min(duration, time));
    }

    private static boolean isFinishedOnce(double expectedTime, double duration, float speed,
                                          LoopMode loopMode) {
        if (loopMode == LoopMode.LOOP) {
            return false;
        }
        return speed > 0.0f ? expectedTime >= duration : expectedTime <= 0.0d;
    }

    private static double recoveryThreshold(double duration, LoopMode loopMode) {
        if (loopMode == LoopMode.LOOP && duration > 0.0d) {
            return Math.max(0.35d, Math.min(RECOVERY_THRESHOLD_SECONDS, duration * 0.45d));
        }
        return RECOVERY_THRESHOLD_SECONDS;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Binding {
        private final WeakReference<GltfInstance> instance;
        private long appliedSequence = Long.MIN_VALUE;
        private long lastRecoveryNanos;
        private long lastClipRepairNanos;
        private float smoothedSpeed = Float.NaN;

        private Binding(GltfInstance instance) {
            this.instance = new WeakReference<>(instance);
        }
    }
}
