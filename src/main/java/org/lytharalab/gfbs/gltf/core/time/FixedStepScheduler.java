package org.lytharalab.gfbs.gltf.core.time;

/**
 * Frame-rate independent fixed-step driver owned by GFBS : glTF.
 *
 * <p>Rendering hands the scheduler the wall duration of a frame; the scheduler converts that into a
 * bounded number of fixed logical steps. It replaces every design that previously piggy-backed on
 * Minecraft's 20 Hz tick counter: the step count is produced by this object, the step size is a
 * constant, and a stalled frame can never trigger an unbounded catch-up burst.</p>
 */
public final class FixedStepScheduler {
    private final double stepSeconds;
    private final int maxStepsPerAdvance;
    private final double maxFrameSeconds;

    private double accumulatorSeconds;
    private long steps;
    private long droppedSteps;

    /**
     * @param stepsPerSecond    fixed logical steps per second, for example 20
     * @param maxStepsPerAdvance upper bound of steps emitted for a single frame
     * @param maxFrameSeconds   frames longer than this are treated as a stall: no catch-up at all
     */
    public FixedStepScheduler(double stepsPerSecond, int maxStepsPerAdvance, double maxFrameSeconds) {
        if (!Double.isFinite(stepsPerSecond) || stepsPerSecond <= 0.0d) {
            throw new IllegalArgumentException("Steps per second must be positive and finite");
        }
        if (maxStepsPerAdvance < 1) {
            throw new IllegalArgumentException("Catch-up limit must be at least one step");
        }
        if (!Double.isFinite(maxFrameSeconds) || maxFrameSeconds <= 0.0d) {
            throw new IllegalArgumentException("Frame cap must be positive and finite");
        }
        this.stepSeconds = 1.0d / stepsPerSecond;
        this.maxStepsPerAdvance = maxStepsPerAdvance;
        this.maxFrameSeconds = maxFrameSeconds;
    }

    /** Number of fixed steps to run for a frame of {@code frameSeconds}. */
    public int advance(double frameSeconds) {
        if (!Double.isFinite(frameSeconds) || frameSeconds <= 0.0d) {
            return 0;
        }
        if (frameSeconds > maxFrameSeconds) {
            // A stalled or minimized frame is a lifecycle break: drop the debt instead of replaying it.
            droppedSteps += (long) Math.floor((accumulatorSeconds + frameSeconds) / stepSeconds);
            accumulatorSeconds = 0.0d;
            return 0;
        }
        accumulatorSeconds += frameSeconds;
        int due = (int) Math.floor(accumulatorSeconds / stepSeconds);
        if (due <= 0) {
            return 0;
        }
        if (due > maxStepsPerAdvance) {
            droppedSteps += due - maxStepsPerAdvance;
            accumulatorSeconds = 0.0d;
            due = maxStepsPerAdvance;
        } else {
            accumulatorSeconds -= (double) due * stepSeconds;
        }
        steps += due;
        return due;
    }

    /** Fraction of the next step that has already elapsed, in {@code [0, 1)}. */
    public double alpha() {
        double alpha = accumulatorSeconds / stepSeconds;
        return alpha < 0.0d ? 0.0d : Math.min(alpha, 0.999999d);
    }

    public double stepSeconds() {
        return stepSeconds;
    }

    /** Logical steps emitted since construction or the last {@link #reset()}. */
    public long steps() {
        return steps;
    }

    /** Steps discarded because a frame stalled or the catch-up limit was reached. */
    public long droppedSteps() {
        return droppedSteps;
    }

    public void reset() {
        accumulatorSeconds = 0.0d;
        steps = 0L;
        droppedSteps = 0L;
    }
}
