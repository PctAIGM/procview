package io.github.PctAIGM.procview.sampler

import org.junit.Assert.assertEquals
import org.junit.Test

class FixedTargetScheduleTest {
    @Test
    fun onTimeCollectionKeepsTheNextAbsoluteTarget() {
        assertEquals(
            ScheduledTick(targetMs = 2000L, skippedTicks = 0L),
            FixedTargetSchedule.next(previousTargetMs = 1000L, nowMs = 1500L, intervalMs = 1000L),
        )
        assertEquals(
            ScheduledTick(targetMs = 2000L, skippedTicks = 0L),
            FixedTargetSchedule.next(previousTargetMs = 1000L, nowMs = 2000L, intervalMs = 1000L),
        )
    }

    @Test
    fun overdueCollectionSkipsExpiredTicksWithoutBurstCatchup() {
        assertEquals(
            ScheduledTick(targetMs = 3000L, skippedTicks = 1L),
            FixedTargetSchedule.next(previousTargetMs = 1000L, nowMs = 2001L, intervalMs = 1000L),
        )
        assertEquals(
            ScheduledTick(targetMs = 5000L, skippedTicks = 3L),
            FixedTargetSchedule.next(previousTargetMs = 1000L, nowMs = 4500L, intervalMs = 1000L),
        )
    }

    @Test
    fun targetArithmeticSaturatesInsteadOfOverflowing() {
        assertEquals(
            ScheduledTick(targetMs = Long.MAX_VALUE, skippedTicks = 0L),
            FixedTargetSchedule.next(Long.MAX_VALUE - 5, Long.MAX_VALUE, 10),
        )
    }
}
