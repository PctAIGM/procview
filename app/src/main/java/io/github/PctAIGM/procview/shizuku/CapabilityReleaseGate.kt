package io.github.PctAIGM.procview.shizuku

internal data class CapabilityGateInput(
    val protocolVersion: Int,
    val expectedProtocolVersion: Int,
    val procStatReadable: Boolean,
    val procMeminfoReadable: Boolean,
    val bootIdReadable: Boolean,
    val bootId: String,
    val psCommandAvailable: Boolean,
    val psPidCount: Int,
    val effectiveCpuAndRssReadableCount: Int,
    val pssCommandAvailable: Boolean,
    val pssValueParsed: Boolean,
    val pssReadableCount: Int,
    val selectedProcessListTruncated: Boolean,
    val psFallbackSelected: Boolean,
    val psSnapshotAvailable: Boolean,
    val psSnapshotPidCount: Int,
    val psSnapshotCpuAndRssReadableCount: Int,
)

/** Exact, overflow-safe release gate for the capability report received over Binder. */
internal object CapabilityReleaseGate {
    fun isAvailable(input: CapabilityGateInput): Boolean {
        val referenceCount = input.psPidCount
        if (
            input.protocolVersion != input.expectedProtocolVersion ||
            !input.procStatReadable ||
            !input.procMeminfoReadable ||
            !input.bootIdReadable ||
            input.bootId.isBlank() ||
            !input.psCommandAvailable ||
            referenceCount <= 0 ||
            input.effectiveCpuAndRssReadableCount !in 1..referenceCount ||
            !input.pssCommandAvailable ||
            !input.pssValueParsed ||
            input.pssReadableCount !in 1..referenceCount ||
            input.selectedProcessListTruncated
        ) {
            return false
        }

        if (input.psFallbackSelected) {
            val fallbackIsConsistent = input.psSnapshotAvailable &&
                input.psSnapshotPidCount in 1..referenceCount &&
                input.psSnapshotCpuAndRssReadableCount in 1..input.psSnapshotPidCount &&
                input.psSnapshotCpuAndRssReadableCount ==
                input.effectiveCpuAndRssReadableCount
            if (!fallbackIsConsistent) return false
        }

        return input.effectiveCpuAndRssReadableCount.toLong() * PERCENT_SCALE >=
            referenceCount.toLong() * FULL_COVERAGE_PERCENT
    }

    private const val PERCENT_SCALE = 100L
    private const val FULL_COVERAGE_PERCENT = 95L
}
