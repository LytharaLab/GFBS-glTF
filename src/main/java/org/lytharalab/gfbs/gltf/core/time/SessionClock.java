package org.lytharalab.gfbs.gltf.core.time;

/**
 * Pause-aware monotonic clock owned by GFBS : glTF.
 *
 * <p>This clock deliberately avoids every Minecraft tick counter. Logical time is derived from
 * {@link System#nanoTime()} and only excludes the intervals the owner explicitly declares paused
 * (for example while an integrated server is paused). Animation timelines therefore survive world
 * time resets, TPS drops and tick freezes without ever reading {@code Level#getGameTime()}.</p>
 *
 * <p>All values are expressed in seconds as {@code double}, so a multi-day logical timestamp still
 * carries sub-microsecond resolution.</p>
 */
public final class SessionClock {
    public static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final double NANOS_PER_SECOND_D = (double) NANOS_PER_SECOND;
    private static final long NOT_PAUSED = Long.MIN_VALUE;

    private long anchorNanos;
    private long excludedNanos;
    private long pausedAtNanos = NOT_PAUSED;

    /** Creates a clock anchored at the current wall sample. */
    public SessionClock() {
        this(System.nanoTime());
    }

    /** Creates a clock whose logical time starts at zero for the given wall sample. */
    public SessionClock(long anchorNanos) {
        this.anchorNanos = anchorNanos;
    }

    /** Converts a nanosecond delta into seconds. */
    public static double seconds(long nanos) {
        return (double) nanos / NANOS_PER_SECOND_D;
    }

    /** Logical nanoseconds elapsed since the anchor, excluding paused intervals. */
    public long nowNanos() {
        return logical(System.nanoTime());
    }

    /** Logical nanoseconds for an explicit wall-clock sample. */
    public long logical(long wallNanos) {
        long excluded = excludedNanos;
        if (pausedAtNanos != NOT_PAUSED && wallNanos > pausedAtNanos) {
            excluded += wallNanos - pausedAtNanos;
        }
        return wallNanos - anchorNanos - excluded;
    }

    /** Logical seconds elapsed since the anchor, excluding paused intervals. */
    public double nowSeconds() {
        return seconds(nowNanos());
    }

    public boolean paused() {
        return pausedAtNanos != NOT_PAUSED;
    }

    /**
     * Marks the clock paused or running. Paused wall time is removed from every later reading, so a
     * paused world resumes exactly where it stopped instead of jumping forward.
     */
    public void setPaused(boolean paused, long wallNanos) {
        if (paused) {
            if (pausedAtNanos == NOT_PAUSED) {
                pausedAtNanos = wallNanos;
            }
            return;
        }
        if (pausedAtNanos != NOT_PAUSED) {
            if (wallNanos > pausedAtNanos) {
                excludedNanos += wallNanos - pausedAtNanos;
            }
            pausedAtNanos = NOT_PAUSED;
        }
    }

    public void setPaused(boolean paused) {
        setPaused(paused, System.nanoTime());
    }

    /** Restarts the clock so that logical time is zero at the given wall sample. */
    public void reset(long wallNanos) {
        anchorNanos = wallNanos;
        excludedNanos = 0L;
        pausedAtNanos = NOT_PAUSED;
    }

    public void reset() {
        reset(System.nanoTime());
    }
}
