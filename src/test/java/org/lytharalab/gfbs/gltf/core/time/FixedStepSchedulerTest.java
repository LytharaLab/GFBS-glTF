package org.lytharalab.gfbs.gltf.core.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedStepSchedulerTest {
    @Test
    void emitsFixedStepsAtTheRequestedRate() {
        FixedStepScheduler scheduler = new FixedStepScheduler(20.0d, 2, 0.25d);

        assertEquals(0, scheduler.advance(0.016d));
        assertEquals(1, scheduler.advance(0.034d));
        assertEquals(0, scheduler.advance(0.040d));
        assertEquals(1, scheduler.advance(0.011d));
        assertEquals(2L, scheduler.steps());
        assertEquals(0.02d, scheduler.alpha(), 1.0e-9d);
    }

    @Test
    void dropsDebtAfterAStall() {
        FixedStepScheduler scheduler = new FixedStepScheduler(20.0d, 2, 0.25d);

        assertEquals(0, scheduler.advance(2.0d));
        assertEquals(40L, scheduler.droppedSteps());
        assertEquals(1, scheduler.advance(0.060d));
    }

    @Test
    void capsCatchUpSteps() {
        FixedStepScheduler scheduler = new FixedStepScheduler(20.0d, 2, 0.25d);

        assertEquals(2, scheduler.advance(0.240d));
        assertEquals(2L, scheduler.droppedSteps());
        assertEquals(0, scheduler.advance(0.0d));
    }

    @Test
    void ignoresInvalidFrameDurations() {
        FixedStepScheduler scheduler = new FixedStepScheduler(20.0d, 2, 0.25d);

        assertEquals(0, scheduler.advance(Double.NaN));
        assertEquals(0, scheduler.advance(-1.0d));
        assertEquals(0L, scheduler.steps());
        assertTrue(scheduler.alpha() >= 0.0d);
    }

    @Test
    void resetClearsCounters() {
        FixedStepScheduler scheduler = new FixedStepScheduler(20.0d, 2, 0.25d);
        scheduler.advance(0.06d);
        scheduler.reset();

        assertEquals(0L, scheduler.steps());
        assertEquals(0L, scheduler.droppedSteps());
        assertEquals(0.0d, scheduler.alpha(), 1.0e-9d);
    }
}
