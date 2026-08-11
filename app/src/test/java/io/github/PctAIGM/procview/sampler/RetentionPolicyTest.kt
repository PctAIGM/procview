package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.MetricFrame
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.ProcessMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {
    @Test
    fun retentionIsUnionOfCpuRssPinnedAndDetailWithReasonBits() {
        val frame = frameWithOpposingRankings(40)
        val pinned = ProcessKey(1, 10L)
        val detail = ProcessKey(40, 400L)

        val reasons = RetentionPolicy.reasons(
            frame = frame,
            pinnedKeys = setOf(pinned),
            detailKeys = setOf(detail),
        )

        assertEquals(40, reasons.size)
        assertTrue(reasons.getValue(pinned) and RetentionReason.TOP_RSS != 0)
        assertTrue(reasons.getValue(pinned) and RetentionReason.PINNED != 0)
        assertTrue(reasons.getValue(detail) and RetentionReason.TOP_CPU != 0)
        assertTrue(reasons.getValue(detail) and RetentionReason.DETAIL != 0)
    }

    @Test
    fun pssSelectionPrioritizesDetailAndPinsWhenBounded() {
        val frame = frameWithOpposingRankings(40)
        val detail = ProcessKey(40, 400L)
        val pinned = ProcessKey(1, 10L)
        val selection = RetentionPolicy.selectPssTargets(
            frame = frame,
            pinnedKeys = setOf(pinned),
            detailKeys = setOf(detail),
            maxTargets = 2,
        )

        assertEquals(listOf(detail, pinned), selection.keys)
        assertTrue(selection.truncated)
    }

    @Test
    fun unknownMetricsAreNotInventedAsTopRankings() {
        val key = ProcessKey(3, 30L)
        val frame = MetricFrame(
            sequence = 1,
            elapsedRealtimeNanos = 1,
            wallTimeMillis = 1,
            systemCpuPercentBasisPoints = null,
            memoryTotalKb = null,
            memoryAvailableKb = null,
            collectionDurationMs = 1,
            catalogRevision = 1,
            frameFlags = 0,
            metrics = listOf(ProcessMetric(key, null, null, null, null, 'S')),
        )

        val reasons = RetentionPolicy.reasons(frame, emptySet(), emptySet())
        assertFalse(reasons.containsKey(key))
    }

    private fun frameWithOpposingRankings(count: Int): MetricFrame {
        val metrics = (1..count).map { index ->
            ProcessMetric(
                key = ProcessKey(index, index * 10L),
                cpuPercentBasisPoints = index,
                rssKb = (count - index + 1) * 100L,
                pssKb = null,
                pssSampleElapsedRealtimeNanos = null,
                state = 'S',
            )
        }
        return MetricFrame(
            sequence = 1,
            elapsedRealtimeNanos = 1,
            wallTimeMillis = 1,
            systemCpuPercentBasisPoints = 100,
            memoryTotalKb = 1000,
            memoryAvailableKb = 500,
            collectionDurationMs = 1,
            catalogRevision = 1,
            frameFlags = 0,
            metrics = metrics,
        )
    }
}
