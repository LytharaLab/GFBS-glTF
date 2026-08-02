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
 * <p>Network packets establish state and clock anchors. They never drive individual frames.
 * Bound instances continue to advance at render-frame frequency, while a low-frequency feedback
 * controller makes small playback-speed adjustments to absorb latency and clock drift smoothly.</p>
 */
public final class ClientAnimationSync {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int UNSYNCHRONIZED_CLOCK_PROBE_TICKS = 20;
    private static final int SYNCHRONIZED_CLOCK_PROBE_TICKS = 40;
    private static final double PHASE_DEAD_ZONE_SECONDS = 0.015d;
    private static final double PHASE_GAIN = 0.45d;
    private static final double MAX_RELATIVE_SPEED_CORRECTION = 0.35d;
    private static final double MIN_ABSOLUTE_SPEED_CORRECTION = 0.12d;
    private static final double MIN_SPEED_FACTOR = 0.35d;
    private static final double RECOVERY_BLEND_SECONDS = 0.18d;
    private static final float LATE_PACKET_BLEND_SECONDS = 0.10f;
    private static final double RECOVERY_THRESHOLD_SECONDS = 1.50d;
    private static final long RECOVERY_COOLDOWN_NANOS = 5_000_000_000L;
    private static final long CLIP_REPAIR_COOLDOWN_NANOS = 250_000_000L;

    private static final Map<AnimationTargetKey, Binding> BINDINGS = new HashMap<>();
    private static final Map<AnimationTargetKey, SyncedAnimationState> STATES = new HashMap<>();
    private static final Map<AnimationTargetKey, Long> SEQUENCES = new HashMap<>();
    private static final ServerTickClock CLOCK = new ServerTickClock();

    private static long clientTickCounter;
    private static long nextClockProbeTick;
    private static long nextClockNonce;
    private static long latestClockNonceReceived = Long.MIN_VALUE;
    private static boolean wasPaused;

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

    public static void receive(SyncedAnimationState state, long serverTickAtSend) {
        Objects.requireNonNull(state, "state");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
            || !state.target().dimension().equals(minecraft.level.dimension().location())) {
            return;
        }

        long now = System.nanoTime();
        CLOCK.observeServerPacket(
            serverTickAtSend,
            now,
            minecraft.level.getGameTime()
        );

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
                applyState(binding, state, now);
                if (state.stopped()) {
                    STATES.remove(state.target(), state);
                }
            }
        }
    }

    public static void receiveClockSample(long nonce, long clientSendNanos,
                                          long serverGameTick, long serverNanos) {
        if (nonce <= latestClockNonceReceived) {
            return;
        }
        latestClockNonceReceived = nonce;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        CLOCK.observePong(
            clientSendNanos,
            System.nanoTime(),
            serverGameTick,
            serverNanos,
            minecraft.level.getGameTime()
        );
    }

    /**
     * Performs cleanup, clock probing, and smooth drift feedback.
     * This method deliberately never seeks every tick.
     */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        clientTickCounter++;
        if (minecraft.isPaused()) {
            wasPaused = true;
            removeCollectedBindings();
            return;
        }
        if (wasPaused) {
            // An integrated server does not advance while the game is paused. Discard monotonic
            // extrapolation across the pause and use the level tick until a fresh probe arrives.
            CLOCK.reset();
            latestClockNonceReceived = nextClockNonce;
            nextClockProbeTick = 0L;
            wasPaused = false;
        }
        probeClockIfDue(minecraft);

        ResourceLocation dimension = minecraft.level.dimension().location();
        long now = System.nanoTime();
        double serverTick = CLOCK.estimate(now, minecraft.level.getGameTime());

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
                synchronizeBinding(entry.getValue(), instance, state, serverTick, now);
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

    public static void clearDimension(ResourceLocation dimension) {
        BINDINGS.keySet().removeIf(key -> key.dimension().equals(dimension));
        STATES.keySet().removeIf(key -> key.dimension().equals(dimension));
        SEQUENCES.keySet().removeIf(key -> key.dimension().equals(dimension));
        resetClockState();
    }

    public static void clear() {
        BINDINGS.clear();
        STATES.clear();
        SEQUENCES.clear();
        resetClockState();
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

    public static double estimatedServerTicksPerSecond() {
        return CLOCK.ticksPerSecond();
    }

    public static double estimatedServerTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0.0d;
        }
        return CLOCK.estimate(System.nanoTime(), minecraft.level.getGameTime());
    }

    private static void removeCollectedBindings() {
        BINDINGS.entrySet().removeIf(entry -> entry.getValue().instance.get() == null);
    }

    private static void probeClockIfDue(Minecraft minecraft) {
        if (minecraft.getConnection() == null || clientTickCounter < nextClockProbeTick) {
            return;
        }
        long nonce = ++nextClockNonce;
        long sendNanos = System.nanoTime();
        GltfNetwork.sendToServer(new AnimationClockRequestPacket(nonce, sendNanos));
        nextClockProbeTick = clientTickCounter + (CLOCK.synchronizedClock()
            ? SYNCHRONIZED_CLOCK_PROBE_TICKS
            : UNSYNCHRONIZED_CLOCK_PROBE_TICKS);
    }

    private static void synchronizeBinding(Binding binding, GltfInstance instance,
                                           SyncedAnimationState state, double serverTick,
                                           long nowNanos) {
        if (binding.appliedSequence != state.sequence()) {
            applyState(binding, state, nowNanos);
            return;
        }
        if (state.stopped()) {
            return;
        }

        AnimationClip expectedClip = instance.asset().animation(state.animation()).orElse(null);
        AnimationClip currentClip = instance.animations().currentClip().orElse(null);
        if (expectedClip == null) {
            throw new IllegalArgumentException("Unknown synchronized animation: " + state.animation());
        }
        if (currentClip != expectedClip) {
            if (nowNanos - binding.lastClipRepairNanos >= CLIP_REPAIR_COOLDOWN_NANOS) {
                applyState(binding, state, nowNanos);
            }
            return;
        }

        float authoritativeClientSpeed = effectiveClientSpeed(state.speed());
        if (!state.playing()) {
            instance.animations().setSpeed(authoritativeClientSpeed);
            instance.animations().pause();
            binding.smoothedSpeed = authoritativeClientSpeed;
            return;
        }

        float duration = expectedClip.duration();
        double expectedTime = normalizeTime(state.timeAt(serverTick), duration, state.loopMode());
        double actualTime = instance.animations().time();
        double phaseError = phaseError(expectedTime, actualTime, duration, state.loopMode());

        if (!instance.animations().isPlaying()
            && !isFinishedOnce(expectedTime, duration, state.speed(), state.loopMode())) {
            instance.animations().resume();
        }

        double recoveryThreshold = recoveryThreshold(duration, state.loopMode());
        if (Math.abs(phaseError) > recoveryThreshold
            && nowNanos - binding.lastRecoveryNanos >= RECOVERY_COOLDOWN_NANOS) {
            recoverSmoothly(binding, instance, state, expectedTime, nowNanos);
            return;
        }

        float targetSpeed = correctedSpeed(authoritativeClientSpeed, phaseError);
        if (!Float.isFinite(binding.smoothedSpeed)) {
            binding.smoothedSpeed = authoritativeClientSpeed;
        }
        float smoothing = Math.abs(phaseError) > 0.50d ? 0.35f : 0.18f;
        binding.smoothedSpeed += (targetSpeed - binding.smoothedSpeed) * smoothing;
        instance.animations().setSpeed(binding.smoothedSpeed);
    }

    private static void applyState(Binding binding, SyncedAnimationState state, long nowNanos) {
        GltfInstance instance = binding.instance.get();
        if (instance == null) {
            return;
        }
        try {
            if (state.stopped()) {
                instance.animations().stop(true);
                binding.appliedSequence = state.sequence();
                binding.smoothedSpeed = Float.NaN;
                binding.lastClipRepairNanos = nowNanos;
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            double fallbackTick = minecraft.level == null
                ? state.serverStartTick()
                : minecraft.level.getGameTime();
            double serverTick = CLOCK.estimate(nowNanos, fallbackTick);
            float time = state.timeAt(serverTick);
            float remainingTransition = scaleLogicalDurationToClient(
                state.remainingTransitionAt(serverTick)
            );
            double stateAgeSeconds = Math.max(
                0.0d,
                (serverTick - (double) state.serverStartTick()) / 20.0d
            );
            if (remainingTransition == 0.0f && stateAgeSeconds > 0.05d) {
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
            float authoritativeClientSpeed = effectiveClientSpeed(state.speed());
            instance.animations().setSpeed(authoritativeClientSpeed);
            if (!state.playing()) {
                instance.animations().pause();
            }

            binding.appliedSequence = state.sequence();
            binding.smoothedSpeed = authoritativeClientSpeed;
            binding.lastClipRepairNanos = nowNanos;
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
                                        long nowNanos) {
        instance.animations().play(
            state.animation(),
            new PlaybackOptions(
                state.speed(),
                state.loopMode(),
                (float) RECOVERY_BLEND_SECONDS,
                (float) expectedTime
            )
        );
        float authoritativeClientSpeed = effectiveClientSpeed(state.speed());
        instance.animations().setSpeed(authoritativeClientSpeed);
        binding.smoothedSpeed = authoritativeClientSpeed;
        binding.lastRecoveryNanos = nowNanos;
        binding.lastClipRepairNanos = nowNanos;
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

    private static float effectiveClientSpeed(float stateSpeed) {
        double ratio = CLOCK.synchronizedClock()
            ? clamp(CLOCK.ticksPerSecond() / ServerTickClock.NOMINAL_TICKS_PER_SECOND, 0.0d, 1.025d)
            : 1.0d;
        return (float) ((double) stateSpeed * ratio);
    }

    private static float scaleLogicalDurationToClient(float logicalSeconds) {
        if (logicalSeconds <= 0.0f || !CLOCK.synchronizedClock()) {
            return logicalSeconds;
        }
        double ratio = clamp(
            CLOCK.ticksPerSecond() / ServerTickClock.NOMINAL_TICKS_PER_SECOND,
            0.0d,
            1.025d
        );
        if (ratio <= 1.0e-4d) {
            return logicalSeconds;
        }
        return (float) Math.min(10.0d, (double) logicalSeconds / ratio);
    }

    private static double phaseError(double expected, double actual, float duration,
                                     LoopMode loopMode) {
        double error = expected - actual;
        if (loopMode == LoopMode.LOOP && duration > 0.0f) {
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

    private static double normalizeTime(double time, float duration, LoopMode loopMode) {
        if (loopMode == LoopMode.LOOP && duration > 0.0f) {
            double result = time % duration;
            return result < 0.0d ? result + duration : result;
        }
        return Math.max(0.0d, Math.min(duration, time));
    }

    private static boolean isFinishedOnce(double expectedTime, float duration, float speed,
                                          LoopMode loopMode) {
        if (loopMode == LoopMode.LOOP) {
            return false;
        }
        return speed > 0.0f ? expectedTime >= duration : expectedTime <= 0.0d;
    }

    private static double recoveryThreshold(float duration, LoopMode loopMode) {
        if (loopMode == LoopMode.LOOP && duration > 0.0f) {
            return Math.max(0.35d, Math.min(RECOVERY_THRESHOLD_SECONDS, duration * 0.45d));
        }
        return RECOVERY_THRESHOLD_SECONDS;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void resetClockState() {
        CLOCK.reset();
        clientTickCounter = 0L;
        nextClockProbeTick = 0L;
        // Reject replies to probes issued before the dimension/session reset while keeping the
        // nonce monotonic for the lifetime of the client process.
        latestClockNonceReceived = nextClockNonce;
        wasPaused = false;
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
