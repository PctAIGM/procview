package io.github.PctAIGM.procview.monitor

import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.ProcessMetric
import io.github.PctAIGM.procview.sampler.ApplicationAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricPeaksTest {
    @Test
    fun `metric peaks merge values independently and preserve prior maxima`() {
        val merged = MetricPeaks(
            cpuPercentBasisPoints = 750,
            rssKb = null,
            pssKb = 300,
        ).merge(
            cpu = 500,
            rss = 800,
            pss = null,
        )

        assertEquals(750, merged.cpuPercentBasisPoints)
        assertEquals(800L, merged.rssKb)
        assertEquals(300L, merged.pssKb)
        assertNull(MetricPeaks().merge(null, null, null).rssKb)
    }

    @Test
    fun `process peak merge retains exited processes and adds current processes`() {
        val exitedKey = ProcessKey(pid = 10, startTimeTicks = 100)
        val currentKey = ProcessKey(pid = 20, startTimeTicks = 200)
        val merged = mergeProcessPeaks(
            previous = mapOf(exitedKey to MetricPeaks(cpuPercentBasisPoints = 400)),
            metrics = listOf(
                ProcessMetric(
                    key = currentKey,
                    cpuPercentBasisPoints = 900,
                    rssKb = 1_024,
                    pssKb = null,
                    pssSampleElapsedRealtimeNanos = null,
                    state = 'R',
                ),
            ),
        )

        assertEquals(400, merged.getValue(exitedKey).cpuPercentBasisPoints)
        assertEquals(900, merged.getValue(currentKey).cpuPercentBasisPoints)
        assertEquals(1_024L, merged.getValue(currentKey).rssKb)

        val pruned = retainProcessPeaks(merged, setOf(currentKey))
        assertFalse(pruned.containsKey(exitedKey))
        assertEquals(900, pruned.getValue(currentKey).cpuPercentBasisPoints)
    }

    @Test
    fun `application peak merge uses stable application identity`() {
        val application = ApplicationAggregate(
            stableId = "app:example",
            primaryPackage = "example",
            packageCandidates = listOf("example"),
            displayName = "Example",
            uid = 10_000,
            isSystem = false,
            isNative = false,
            isSharedUid = false,
            cpuPercentBasisPoints = 600,
            cpuComplete = true,
            rssKb = 4_096,
            rssComplete = true,
            pssKb = null,
            pssComplete = false,
            processes = emptyList(),
        )
        val merged = mergeApplicationPeaks(
            previous = mapOf(
                application.stableId to MetricPeaks(
                    cpuPercentBasisPoints = 900,
                    pssKb = 2_048,
                ),
            ),
            applications = listOf(application),
        )

        assertEquals(900, merged.getValue(application.stableId).cpuPercentBasisPoints)
        assertEquals(4_096L, merged.getValue(application.stableId).rssKb)
        assertEquals(2_048L, merged.getValue(application.stableId).pssKb)

        val afterIncompleteFrame = mergeApplicationPeaks(
            previous = merged,
            applications = listOf(
                application.copy(
                    cpuPercentBasisPoints = 1_000,
                    cpuComplete = false,
                    rssKb = 8_192,
                    rssComplete = false,
                    pssKb = 4_096,
                    pssComplete = false,
                ),
            ),
        )
        assertEquals(900, afterIncompleteFrame.getValue(application.stableId).cpuPercentBasisPoints)
        assertEquals(4_096L, afterIncompleteFrame.getValue(application.stableId).rssKb)
        assertEquals(2_048L, afterIncompleteFrame.getValue(application.stableId).pssKb)

        val exitedId = "native:2000:exited"
        val withinBudget = retainApplicationPeaks(
            peaks = afterIncompleteFrame +
                (exitedId to MetricPeaks(cpuPercentBasisPoints = 100)),
            retainedIds = setOf(application.stableId),
        )
        assertTrue(withinBudget.containsKey(exitedId))

        val pruned = retainApplicationPeaks(
            peaks = afterIncompleteFrame +
                (exitedId to MetricPeaks(cpuPercentBasisPoints = 100)),
            retainedIds = setOf(application.stableId),
            maximumEntries = 1,
        )
        assertFalse(pruned.containsKey(exitedId))
        assertEquals(900, pruned.getValue(application.stableId).cpuPercentBasisPoints)
    }
}
