package io.github.PctAIGM.procview.sampler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PssCadenceTest {
    @Test
    fun firstFrameIsDueAndLaterFramesFollowAbsoluteCadence() {
        val cadence = PssCadence(intervalMs = 15_000)

        assertTrue(cadence.isDue(1_000_000_000L))
        assertFalse(cadence.isDue(15_999_999_999L))
        assertTrue(cadence.isDue(16_000_000_000L))
        assertFalse(cadence.isDue(20_000_000_000L))
    }

    @Test
    fun overdueCadenceCollectsOnceWithoutBurstCatchup() {
        val cadence = PssCadence(intervalMs = 10_000)
        assertTrue(cadence.isDue(0L))
        assertTrue(cadence.isDue(35_000_000_000L))
        assertFalse(cadence.isDue(35_000_000_001L))
        assertTrue(cadence.isDue(40_000_000_000L))
    }
}
