package org.lytharalab.gfbs.gltf.client.sync;

import net.minecraft.client.Minecraft;
import org.lytharalab.gfbs.gltf.core.time.FixedStepScheduler;
import org.lytharalab.gfbs.gltf.core.time.SessionClock;

/**
 * Client-side lifecycle driver owned by GFBS : glTF.
 *
 * <p>Rendering calls {@link #beginFrame()}; the heartbeat owns a pause-aware {@link SessionClock} and
 * a {@link FixedStepScheduler}, so synchronized animations advance on our own fixed logical steps
 * instead of the Minecraft client tick counter. No {@code TickEvent} is registered anywhere, and a
 * minimized window or a stalled frame cannot produce a catch-up burst: the scheduler drops the debt
 * and the controller re-aligns with a blend.</p>
 *
 * <p>Frames are taken from {@code RenderLevelStageEvent.Stage.AFTER_ENTITIES}, which runs once per
 * rendered frame while a level exists. Single-player pause freezes the logical clock, so a paused
 * world resumes exactly where it stopped instead of jumping forward by the pause duration.</p>
 */
public final class ClientHeartbeat {
    /** Logical controller steps per second. */
    public static final double LOGICAL_STEPS_PER_SECOND = 20.0d;

    private static final double MAX_FRAME_SECONDS = 0.25d;
    private static final int MAX_CATCH_UP_STEPS = 2;
    private static final double PROBE_INTERVAL_UNSYNCHRONIZED = 0.5d;
    private static final double PROBE_INTERVAL_SYNCHRONIZED = 2.0d;

    private static final SessionClock CLOCK = new SessionClock();
    private static final FixedStepScheduler SCHEDULER =
        new FixedStepScheduler(LOGICAL_STEPS_PER_SECOND, MAX_CATCH_UP_STEPS, MAX_FRAME_SECONDS);

    private static long lastFrameNanos = Long.MIN_VALUE;
    private static double nextProbeSeconds;
    private static long frames;
    private static long pausedFrames;
    private static long steps;

    private ClientHeartbeat() {
    }

    /** Called from the render-frame hook. Safe before a level or connection exists. */
    public static void beginFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        beginFrame(System.nanoTime(), minecraft.isPaused(), minecraft.getConnection() != null);
    }

    static void beginFrame(long wallNanos, boolean paused, boolean connected) {
        if (paused) {
            markPaused(wallNanos);
            return;
        }
        if (CLOCK.paused()) {
            markRunning(wallNanos);
        }
        frames++;
        if (lastFrameNanos == Long.MIN_VALUE) {
            lastFrameNanos = wallNanos;
            return;
        }
        double frameSeconds = SessionClock.seconds(wallNanos - lastFrameNanos);
        lastFrameNanos = wallNanos;

        int due = SCHEDULER.advance(frameSeconds);
        if (due > 0) {
            long logicalNanos = CLOCK.logical(wallNanos);
            for (int step = 0; step < due; step++) {
                ClientAnimationSync.step(logicalNanos);
                steps++;
            }
        }

        double logicalSeconds = SessionClock.seconds(CLOCK.logical(wallNanos));
        if (connected && logicalSeconds >= nextProbeSeconds) {
            nextProbeSeconds = logicalSeconds + (ClientAnimationSync.clockSynchronized()
                ? PROBE_INTERVAL_SYNCHRONIZED
                : PROBE_INTERVAL_UNSYNCHRONIZED);
            ClientAnimationSync.sendClockProbe();
        }
    }

    /** Logical time base: monotonic nanoseconds that stop while the world is paused. */
    static long logicalNanos() {
        return CLOCK.nowNanos();
    }

    /** Logical seconds since this client session began; paused time is excluded. */
    public static double logicalSeconds() {
        return CLOCK.nowSeconds();
    }

    /** Drops all lifecycle state; used when the client leaves a world. */
    public static void reset() {
        CLOCK.reset();
        SCHEDULER.reset();
        lastFrameNanos = Long.MIN_VALUE;
        nextProbeSeconds = 0.0d;
    }

    public static long frames() {
        return frames;
    }

    public static long steps() {
        return steps;
    }

    public static long pausedFrames() {
        return pausedFrames;
    }

    public static long droppedSteps() {
        return SCHEDULER.droppedSteps();
    }

    public static boolean paused() {
        return CLOCK.paused();
    }

    private static void markPaused(long wallNanos) {
        if (!CLOCK.paused()) {
            CLOCK.setPaused(true, wallNanos);
            SCHEDULER.reset();
            ClientAnimationSync.onTimelineRestart();
        }
        lastFrameNanos = wallNanos;
        pausedFrames++;
    }

    private static void markRunning(long wallNanos) {
        CLOCK.setPaused(false, wallNanos);
        SCHEDULER.reset();
        lastFrameNanos = wallNanos;
        nextProbeSeconds = 0.0d;
        ClientAnimationSync.onTimelineRestart();
    }
}
