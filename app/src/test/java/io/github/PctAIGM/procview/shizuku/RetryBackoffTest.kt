package io.github.PctAIGM.procview.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryBackoffTest {
    @Test
    fun doublesUntilCapAndResetRestoresInitialDelay() {
        val backoff = RetryBackoff(initialDelayMs = 1_000L, maximumDelayMs = 30_000L)

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            List(7) { backoff.takeNextDelayMs() },
        )
        backoff.reset()
        assertEquals(1_000L, backoff.takeNextDelayMs())
    }
}
