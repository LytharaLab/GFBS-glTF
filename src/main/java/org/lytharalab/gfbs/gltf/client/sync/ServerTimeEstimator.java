package org.lytharalab.gfbs.gltf.client.sync;

import org.lytharalab.gfbs.gltf.core.time.SessionClock;

/**
 * Client-side estimate of the server's monotonic timeline, expressed in seconds.
 *
 * <p>Both endpoints derive time from {@link System#nanoTime()}, so the estimator only has to solve
 * for an offset: one round-trip sample yields a phase-corrected anchor and every later logical
 * sample extrapolates from it at real-time rate. The 1.5.0 tick-rate inference is gone together
 * with the tick domain, which also removes the periodic rate re-learning that used to nudge the
 * client's playback speed while a model was animating.</p>
 *
 * <p>All {@code *Nanos} parameters are samples of the caller's monotonic time base; the estimator
 * never interprets them as a wall-clock date, only as differences.</p>
 */
final class ServerTimeEstimator {
    private static final double STALE_AFTER_SECONDS = 15.0d;
    private static final double PONG_PHASE_GAIN = 0.35d;
    private static final double PACKET_PHASE_GAIN = 0.08d;
    private static final double MAX_PHASE_STEP_SECONDS = 0.30d;
    private static final double HARD_RESET_ERROR_SECONDS = 4.0d;

    private boolean initialized;
    private long anchorClientNanos;
    private double anchorServerSeconds;
    private double baseRttNanos = Double.NaN;
    private long lastSampleClientNanos = Long.MIN_VALUE;
    private double seedServerSeconds = Double.NaN;
    private long seedClientNanos = Long.MIN_VALUE;

    void reset() {
        initialized = false;
        anchorClientNanos = 0L;
        anchorServerSeconds = 0.0d;
        baseRttNanos = Double.NaN;
        lastSampleClientNanos = Long.MIN_VALUE;
        seedServerSeconds = Double.NaN;
        seedClientNanos = Long.MIN_VALUE;
    }

    /**
     * Feeds one clock-probe result.
     *
     * @param rttNanos               measured round-trip time of the probe
     * @param logicalReceiveNanos    the caller's logical sample at which the reply arrived
     * @param serverSecondsAtReply   server timeline position carried by the reply
     */
    void observePong(long rttNanos, long logicalReceiveNanos, double serverSecondsAtReply) {
        if (rttNanos < 0L || !Double.isFinite(serverSecondsAtReply)) {
            return;
        }
        updateBaseRtt(rttNanos);
        double sample = serverSecondsAtReply + oneWaySeconds();
        seed(sample, logicalReceiveNanos);
        discipline(sample, logicalReceiveNanos, PONG_PHASE_GAIN);
    }

    /**
     * Feeds a state packet dispatch stamp. Before the first probe completes this only seeds the
     * timeline, so a player joining a busy world still sees animations immediately.
     */
    void observeServerPacket(double sentAtSeconds, long logicalReceiveNanos) {
        if (!Double.isFinite(sentAtSeconds)) {
            return;
        }
        boolean latencyKnown = initialized && Double.isFinite(baseRttNanos);
        double sample = sentAtSeconds + (latencyKnown ? oneWaySeconds() : 0.0d);
        seed(sentAtSeconds, logicalReceiveNanos);
        discipline(sample, logicalReceiveNanos, PACKET_PHASE_GAIN);
    }

    /**
     * Best estimate of the server timeline at a logical sample, or {@link Double#NaN} when no packet
     * has been observed yet.
     */
    double estimate(long logicalNanos) {
        if (initialized) {
            boolean fresh = lastSampleClientNanos != Long.MIN_VALUE
                && SessionClock.seconds(logicalNanos - lastSampleClientNanos) <= STALE_AFTER_SECONDS;
            if (fresh) {
                return rawEstimate(logicalNanos);
            }
        }
        if (seedClientNanos != Long.MIN_VALUE) {
            return seedServerSeconds + SessionClock.seconds(logicalNanos - seedClientNanos);
        }
        return Double.NaN;
    }

    boolean synchronizedClock() {
        return initialized && Double.isFinite(baseRttNanos);
    }

    /** Filtered network RTT in milliseconds, or a negative value before the first probe. */
    double roundTripMillis() {
        return Double.isFinite(baseRttNanos) ? baseRttNanos / 1.0e6d : -1.0d;
    }

    private void seed(double serverSeconds, long logicalNanos) {
        seedServerSeconds = serverSeconds;
        seedClientNanos = logicalNanos;
    }

    private void discipline(double sample, long logicalNanos, double gain) {
        if (!initialized) {
            initialized = true;
            anchorClientNanos = logicalNanos;
            anchorServerSeconds = sample;
        } else {
            double predicted = rawEstimate(logicalNanos);
            double error = sample - predicted;
            if (!Double.isFinite(error) || Math.abs(error) > HARD_RESET_ERROR_SECONDS) {
                // A jump this large is a session change (respawn, server restart, pause), not drift.
                anchorServerSeconds = sample;
            } else {
                anchorServerSeconds = predicted + clamp(
                    error * gain,
                    -MAX_PHASE_STEP_SECONDS,
                    MAX_PHASE_STEP_SECONDS
                );
            }
            anchorClientNanos = logicalNanos;
        }
        lastSampleClientNanos = logicalNanos;
    }

    private double rawEstimate(long logicalNanos) {
        return anchorServerSeconds + SessionClock.seconds(logicalNanos - anchorClientNanos);
    }

    private void updateBaseRtt(long rttNanos) {
        double sample = (double) rttNanos;
        if (!Double.isFinite(baseRttNanos) || sample < baseRttNanos) {
            // A lower sample is the best evidence of the real path latency; accept it immediately.
            baseRttNanos = sample;
        } else {
            // Let the baseline rise when the path genuinely becomes slower, but reject short spikes.
            baseRttNanos += (sample - baseRttNanos) * 0.20d;
        }
    }

    private double oneWaySeconds() {
        if (!Double.isFinite(baseRttNanos)) {
            return 0.0d;
        }
        return Math.max(0.0d, baseRttNanos * 0.5d) / 1.0e9d;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
