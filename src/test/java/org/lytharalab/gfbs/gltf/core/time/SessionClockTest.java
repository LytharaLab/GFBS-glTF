package org.lytharalab.gfbs.gltf.core.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionClockTest {
    @Test
    void convertsNanosecondsWithoutFloatQuantization() {
        assertEquals(1_000_000.03125d, SessionClock.seconds(1_000_000_031_250_000L), 1.0e-9d);
    }

    @Test
    void excludesPausedWallTime() {
        SessionClock clock = new SessionClock(1_000_000_000L);
        assertEquals(0.0d, SessionClock.seconds(clock.logical(1_000_000_000L)), 1.0e-9d);

        clock.setPaused(true, 2_000_000_000L);
        assertTrue(clock.paused());
        assertEquals(1.0d, SessionClock.seconds(clock.logical(5_000_000_000L)), 1.0e-9d);

        clock.setPaused(false, 6_000_000_000L);
        assertFalse(clock.paused());
        assertEquals(2.0d, SessionClock.seconds(clock.logical(7_000_000_000L)), 1.0e-9d);
    }

    @Test
    void resetRestartsLogicalTime() {
        SessionClock clock = new SessionClock(0L);
        clock.setPaused(true, 1_000_000_000L);
        clock.setPaused(false, 2_000_000_000L);
        clock.reset(9_000_000_000L);

        assertEquals(0.0d, SessionClock.seconds(clock.logical(9_000_000_000L)), 1.0e-9d);
        assertEquals(0.5d, SessionClock.seconds(clock.logical(9_500_000_000L)), 1.0e-9d);
    }
}
