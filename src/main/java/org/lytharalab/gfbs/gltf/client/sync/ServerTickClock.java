package org.lytharalab.gfbs.gltf.client.sync;

/**
 * Disciplined client-side estimate of the server's logical game tick.
 *
 * <p>The estimator uses echoed clock probes to separate stable network transit time from server
 * queue stalls. It also derives the server's actual logical tick rate from server-side monotonic
 * timestamps, so synchronized animations remain authoritative when TPS drops.</p>
 */
final class ServerTickClock {
    static final double NOMINAL_TICKS_PER_SECOND = 20.0d;

    private static final double MAX_TICKS_PER_SECOND = 20.5d;
    private static final double MIN_TICKS_PER_SECOND = 0.0d;
    private static final double RATE_SMOOTHING = 0.30d;
    private static final double PONG_PHASE_GAIN = 0.35d;
    private static final double PACKET_PHASE_GAIN = 0.08d;
    private static final double MAX_PHASE_STEP_TICKS = 6.0d;
    private static final double HARD_RESET_ERROR_TICKS = 80.0d;
    private static final long STALE_AFTER_NANOS = 15_000_000_000L;

    private boolean initialized;
    private long anchorClientNanos;
    private double anchorServerTick;
    private double tickRate = NOMINAL_TICKS_PER_SECOND;
    private double baseRttNanos = Double.NaN;
    private long lastSampleClientNanos = Long.MIN_VALUE;
    private long previousServerNanos = Long.MIN_VALUE;
    private long previousServerTick;

    void reset() {
        initialized = false;
        anchorClientNanos = 0L;
        anchorServerTick = 0.0d;
        tickRate = NOMINAL_TICKS_PER_SECOND;
        baseRttNanos = Double.NaN;
        lastSampleClientNanos = Long.MIN_VALUE;
        previousServerNanos = Long.MIN_VALUE;
        previousServerTick = 0L;
    }

    void observePong(long clientSendNanos, long clientReceiveNanos,
                     long serverGameTick, long serverNanos, double fallbackServerTick) {
        if (clientReceiveNanos < clientSendNanos) {
            return;
        }

        long rttNanos = clientReceiveNanos - clientSendNanos;
        updateBaseRtt(rttNanos);
        updateTickRate(serverGameTick, serverNanos);

        double oneWaySeconds = estimatedOneWayNanos() / 1_000_000_000.0d;
        double serverTickAtReceive = (double) serverGameTick + oneWaySeconds * tickRate;
        if (!Double.isFinite(serverTickAtReceive)) {
            serverTickAtReceive = fallbackServerTick;
        }
        discipline(serverTickAtReceive, clientReceiveNanos, fallbackServerTick, PONG_PHASE_GAIN);
    }

    void observeServerPacket(long serverTickAtSend, long clientReceiveNanos,
                             double fallbackServerTick) {
        double sample;
        if (initialized && Double.isFinite(baseRttNanos)) {
            double oneWaySeconds = estimatedOneWayNanos() / 1_000_000_000.0d;
            sample = (double) serverTickAtSend + oneWaySeconds * tickRate;
        } else {
            sample = Math.max((double) serverTickAtSend, fallbackServerTick);
        }
        discipline(sample, clientReceiveNanos, fallbackServerTick, PACKET_PHASE_GAIN);
    }

    double estimate(long clientNanos, double fallbackServerTick) {
        if (!initialized) {
            return fallbackServerTick;
        }
        if (lastSampleClientNanos != Long.MIN_VALUE
            && clientNanos - lastSampleClientNanos > STALE_AFTER_NANOS) {
            return fallbackServerTick;
        }
        return rawEstimate(clientNanos);
    }

    boolean synchronizedClock() {
        return initialized && Double.isFinite(baseRttNanos);
    }

    double roundTripMillis() {
        return Double.isFinite(baseRttNanos) ? baseRttNanos / 1_000_000.0d : -1.0d;
    }

    double ticksPerSecond() {
        return tickRate;
    }

    private void updateBaseRtt(long rttNanos) {
        if (rttNanos < 0L) {
            return;
        }
        double sample = (double) rttNanos;
        if (!Double.isFinite(baseRttNanos)) {
            baseRttNanos = sample;
        } else if (sample < baseRttNanos) {
            // A lower sample is the best evidence of the real path latency; accept it immediately.
            baseRttNanos = sample;
        } else {
            // Let the baseline rise when the path genuinely becomes slower, but reject short spikes.
            baseRttNanos += (sample - baseRttNanos) * 0.20d;
        }
    }

    private void updateTickRate(long serverGameTick, long serverNanos) {
        if (previousServerNanos != Long.MIN_VALUE && serverNanos > previousServerNanos) {
            long tickDelta = serverGameTick - previousServerTick;
            long nanosDelta = serverNanos - previousServerNanos;
            if (tickDelta >= 0L && nanosDelta > 0L) {
                double sampleRate = (double) tickDelta * 1_000_000_000.0d / (double) nanosDelta;
                if (Double.isFinite(sampleRate)) {
                    sampleRate = clamp(sampleRate, MIN_TICKS_PER_SECOND, MAX_TICKS_PER_SECOND);
                    tickRate += (sampleRate - tickRate) * RATE_SMOOTHING;
                }
            }
        }
        previousServerTick = serverGameTick;
        previousServerNanos = serverNanos;
    }

    private void discipline(double sampleServerTick, long clientNanos,
                            double fallbackServerTick, double gain) {
        if (!Double.isFinite(sampleServerTick)) {
            sampleServerTick = fallbackServerTick;
        }
        if (!initialized) {
            initialized = true;
            anchorClientNanos = clientNanos;
            anchorServerTick = sampleServerTick;
            lastSampleClientNanos = clientNanos;
            return;
        }

        double predicted = rawEstimate(clientNanos);
        double error = sampleServerTick - predicted;
        if (!Double.isFinite(error) || Math.abs(error) > HARD_RESET_ERROR_TICKS) {
            anchorServerTick = sampleServerTick;
        } else {
            double step = clamp(error * gain, -MAX_PHASE_STEP_TICKS, MAX_PHASE_STEP_TICKS);
            anchorServerTick = predicted + step;
        }
        anchorClientNanos = clientNanos;
        lastSampleClientNanos = clientNanos;
    }

    private double rawEstimate(long clientNanos) {
        double elapsedSeconds = (double) (clientNanos - anchorClientNanos) / 1_000_000_000.0d;
        return anchorServerTick + elapsedSeconds * tickRate;
    }

    private double estimatedOneWayNanos() {
        return Double.isFinite(baseRttNanos) ? Math.max(0.0d, baseRttNanos * 0.5d) : 0.0d;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
