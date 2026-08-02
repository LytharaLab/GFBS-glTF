package org.lytharalab.gfbs.gltf.client.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTickClockTest {
    @Test
    void compensatesForFiveHundredMillisecondRoundTripTime() {
        ServerTickClock clock = new ServerTickClock();
        long send = 20_000_000_000L;
        long receive = send + 500_000_000L;

        clock.observePong(send, receive, 100L, 10_000_000_000L, 105.0d);

        assertTrue(clock.synchronizedClock());
        assertEquals(500.0d, clock.roundTripMillis(), 0.001d);
        assertEquals(105.0d, clock.estimate(receive, 0.0d), 0.001d);
        assertEquals(115.0d, clock.estimate(receive + 500_000_000L, 0.0d), 0.001d);
    }

    @Test
    void statePacketSeedsTheClockBeforeTheFirstProbeCompletes() {
        ServerTickClock clock = new ServerTickClock();
        clock.observeServerPacket(200L, 5_000_000_000L, 205.0d);

        assertEquals(205.0d, clock.estimate(5_000_000_000L, 0.0d), 0.001d);
    }

    @Test
    void followsTheServersActualLogicalTickRate() {
        ServerTickClock clock = new ServerTickClock();
        clock.observePong(0L, 100_000_000L, 100L, 1_000_000_000L, 101.0d);
        clock.observePong(2_000_000_000L, 2_100_000_000L, 120L, 3_000_000_000L, 121.0d);

        assertTrue(clock.ticksPerSecond() < 20.0d);
        assertTrue(clock.ticksPerSecond() > 10.0d);
    }
}
