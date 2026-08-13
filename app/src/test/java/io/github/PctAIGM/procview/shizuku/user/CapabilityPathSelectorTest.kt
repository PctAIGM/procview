package io.github.PctAIGM.procview.shizuku.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPathSelectorTest {
    @Test
    fun keepsPreferredProcPathOnceItMeetsReleaseCoverage() {
        val decision = CapabilityPathSelector.select(
            procReadableCount = 95,
            fallbackReadableCount = 100,
            referenceCount = 100,
            procTruncated = false,
            fallbackAvailable = true,
            fallbackTruncated = false,
        )

        assertFalse(decision.useFallback)
        assertEquals(95, decision.effectiveReadableCount)
        assertTrue(decision.fullCoverage)
    }

    @Test
    fun selectsParsedFallbackWhenProcCoverageMissesTheGate() {
        val decision = CapabilityPathSelector.select(
            procReadableCount = 60,
            fallbackReadableCount = 98,
            referenceCount = 100,
            procTruncated = false,
            fallbackAvailable = true,
            fallbackTruncated = false,
        )

        assertTrue(decision.useFallback)
        assertEquals(98, decision.effectiveReadableCount)
        assertTrue(decision.fullCoverage)
    }

    @Test
    fun truncatedOrWorseFallbackCannotDisplaceDirectReads() {
        val truncated = CapabilityPathSelector.select(
            procReadableCount = 60,
            fallbackReadableCount = 100,
            referenceCount = 100,
            procTruncated = false,
            fallbackAvailable = true,
            fallbackTruncated = true,
        )
        val worse = CapabilityPathSelector.select(
            procReadableCount = 60,
            fallbackReadableCount = 40,
            referenceCount = 100,
            procTruncated = false,
            fallbackAvailable = true,
            fallbackTruncated = false,
        )

        assertFalse(truncated.useFallback)
        assertFalse(worse.useFallback)
        assertFalse(truncated.fullCoverage)
        assertFalse(worse.fullCoverage)
    }
}
