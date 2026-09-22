package org.lytharalab.gfbs.gltf.client.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTimeEstimatorTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    void compensatesForFiveHundredMillisecondRoundTripTime() {
        ServerTimeEstimator clock = new ServerTimeEstimator();
        long receive = 20_000_000_000L + 500L * MILLIS;

        clock.observePong(500L * MILLIS, receive, 10.0d);

        assertTrue(clock.synchronizedClock());
        assertEquals(500.0d, clock.roundTripMillis(), 0.001d);
        assertEquals(10.25d, clock.estimate(receive), 1.0e-6d);
        assertEquals(10.75d, clock.estimate(receive + 500L * MILLIS), 1.0e-6d);
    }

    @Test
    void statePacketSeedsTheClockBeforeTheFirstProbeCompletes() {
        ServerTimeEstimator clock = new ServerTimeEstimator();
        clock.observeServerPacket(200.0d, 5_000_000_000L);

        assertFalse(clock.synchronizedClock());
        assertEquals(200.0d, clock.estimate(5_000_000_000L), 1.0e-6d);
        assertEquals(201.5d, clock.estimate(5_000_000_000L + 1_500L * MILLIS), 1.0e-6d);
    }

    @Test
    void keepsSubMillisecondResolutionAtElevenDaysOfTimeline() {
        ServerTimeEstimator clock = new ServerTimeEstimator();
        clock.observePong(0L, 0L, 1_000_000.0d);

        // 31.25 ms later: a float timeline would have snapped this to a 62.5 ms step.
        assertEquals(1_000_000.03125d, clock.estimate(31_250_000L), 1.0e-9d);
    }

    @Test
    void fallsBackToTheLastPacketWhenSamplesGoStale() {
        ServerTimeEstimator clock = new ServerTimeEstimator();
        clock.observePong(100L * MILLIS, 0L, 10.0d);
        clock.observeServerPacket(10.0d, 0L);

        double stale = clock.estimate(20_000L * MILLIS);
        assertEquals(30.0d, stale, 1.0e-6d);
    }

    @Test
    void hardResetsWhenTheSessionJumps() {
        ServerTimeEstimator clock = new ServerTimeEstimator();
        clock.observePong(0L, 0L, 10.0d);
        clock.observePong(0L, 1_000L * MILLIS, 30.0d);

        assertEquals(30.0d, clock.estimate(1_000L * MILLIS), 1.0e-6d);
    }

    @Test
    void reportsNoEstimateBeforeAnyPacket() {
        ServerTimeEstimator clock = new ServerTimeEstimator();
        assertTrue(Double.isNaN(clock.estimate(123L)));
        assertFalse(clock.synchronizedClock());
        assertTrue(clock.roundTripMillis() < 0.0d);
    }

    @Test
    void neverAcceptsNegativeRoundTripSamples() {
        ServerTimeEstimator clock = new ServerTimeEstimator();
        clock.observePong(-5L, 0L, 1.0d);
        assertTrue(Double.isNaN(clock.estimate(0L)));
    }
}
