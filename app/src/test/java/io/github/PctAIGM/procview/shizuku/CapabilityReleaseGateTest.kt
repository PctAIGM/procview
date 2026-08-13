package io.github.PctAIGM.procview.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReleaseGateTest {
    @Test
    fun acceptsExactNinetyFivePercentDirectCoverage() {
        assertTrue(CapabilityReleaseGate.isAvailable(validInput()))
    }

    @Test
    fun acceptsAConsistentSelectedFallback() {
        assertTrue(
            CapabilityReleaseGate.isAvailable(
                validInput().copy(
                    psFallbackSelected = true,
                    psSnapshotAvailable = true,
                    psSnapshotPidCount = 98,
                    psSnapshotCpuAndRssReadableCount = 95,
                ),
            ),
        )
    }

    @Test
    fun rejectsSelfReferencedCoverageWhenIndependentPsEnumerationFailed() {
        assertFalse(
            CapabilityReleaseGate.isAvailable(
                validInput().copy(psCommandAvailable = false, psPidCount = 0),
            ),
        )
    }

    @Test
    fun rejectsOutOfBoundsOrInconsistentBinderCounts() {
        assertFalse(
            CapabilityReleaseGate.isAvailable(
                validInput().copy(effectiveCpuAndRssReadableCount = 101),
            ),
        )
        assertFalse(
            CapabilityReleaseGate.isAvailable(
                validInput().copy(
                    psFallbackSelected = true,
                    psSnapshotAvailable = true,
                    psSnapshotPidCount = 95,
                    psSnapshotCpuAndRssReadableCount = 94,
                ),
            ),
        )
        assertFalse(
            CapabilityReleaseGate.isAvailable(
                validInput().copy(selectedProcessListTruncated = true),
            ),
        )
    }

    private fun validInput() = CapabilityGateInput(
        protocolVersion = 4,
        expectedProtocolVersion = 4,
        procStatReadable = true,
        procMeminfoReadable = true,
        bootIdReadable = true,
        bootId = "boot",
        psCommandAvailable = true,
        psPidCount = 100,
        effectiveCpuAndRssReadableCount = 95,
        pssCommandAvailable = true,
        pssValueParsed = true,
        pssReadableCount = 90,
        selectedProcessListTruncated = false,
        psFallbackSelected = false,
        psSnapshotAvailable = false,
        psSnapshotPidCount = 0,
        psSnapshotCpuAndRssReadableCount = 0,
    )
}
