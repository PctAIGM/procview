package io.github.PctAIGM.procview.shizuku.user

internal data class CapabilityPathDecision(
    val useFallback: Boolean,
    val effectiveReadableCount: Int,
    val referenceCount: Int,
    val selectedPathTruncated: Boolean,
    val fullCoverage: Boolean,
)

/** Chooses the bounded source that sessions will actually use after the probe. */
internal object CapabilityPathSelector {
    fun select(
        procReadableCount: Int,
        fallbackReadableCount: Int,
        referenceCount: Int,
        procTruncated: Boolean,
        fallbackAvailable: Boolean,
        fallbackTruncated: Boolean,
    ): CapabilityPathDecision {
        require(procReadableCount >= 0) { "procReadableCount must not be negative" }
        require(fallbackReadableCount >= 0) { "fallbackReadableCount must not be negative" }
        require(referenceCount >= 0) { "referenceCount must not be negative" }

        val procReleaseReady = !procTruncated && meetsCoverage(
            procReadableCount,
            referenceCount,
        )
        val fallbackUsable = fallbackAvailable &&
            !fallbackTruncated &&
            fallbackReadableCount > 0
        val fallbackReleaseReady = fallbackUsable && meetsCoverage(
            fallbackReadableCount,
            referenceCount,
        )
        val useFallback = when {
            procReleaseReady -> false
            fallbackReleaseReady -> true
            fallbackUsable -> fallbackReadableCount > procReadableCount
            else -> false
        }
        val effectiveCount = if (useFallback) fallbackReadableCount else procReadableCount
        val selectedTruncated = if (useFallback) fallbackTruncated else procTruncated
        return CapabilityPathDecision(
            useFallback = useFallback,
            effectiveReadableCount = effectiveCount,
            referenceCount = referenceCount,
            selectedPathTruncated = selectedTruncated,
            fullCoverage = !selectedTruncated && meetsCoverage(effectiveCount, referenceCount),
        )
    }

    private fun meetsCoverage(readableCount: Int, referenceCount: Int): Boolean =
        referenceCount > 0 &&
            readableCount.toLong() * PERCENT_SCALE >=
            referenceCount.toLong() * FULL_COVERAGE_PERCENT

    private const val PERCENT_SCALE = 100L
    private const val FULL_COVERAGE_PERCENT = 95L
}
