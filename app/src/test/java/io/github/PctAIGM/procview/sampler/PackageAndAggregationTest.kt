package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.MetricFrame
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.ProcessMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageAndAggregationTest {
    @Test
    fun selectorUsesLongestExactAndroidProcessPrefix() {
        assertEquals(
            "com.example.long",
            PackageCandidateSelector.selectPrimary(
                processName = "com.example.long:worker",
                candidates = listOf("com.example", "com.example.long"),
            ),
        )
        assertNull(
            PackageCandidateSelector.selectPrimary(
                processName = "shared.worker",
                candidates = listOf("com.one", "com.two"),
            ),
        )
        assertEquals(
            "com.only",
            PackageCandidateSelector.selectPrimary("unusual-name", listOf("com.only")),
        )
    }

    @Test
    fun aggregatorSumsApplicationProcessesAndPreservesCompleteness() {
        val firstKey = ProcessKey(10, 100L)
        val secondKey = ProcessKey(11, 110L)
        val catalog = listOf(
            ProcessCatalogEntry(firstKey, 1, 10123, "com.example", "com.example"),
            ProcessCatalogEntry(secondKey, 10, 10123, "com.example:worker", "com.example:worker"),
        )
        val resolutions = catalog.map { entry ->
            ProcessPackageResolution(
                key = entry.key,
                packageCandidates = listOf("com.example"),
                primaryPackage = "com.example",
                displayName = "Example",
                isSystem = false,
                isNative = false,
                isSharedUid = false,
            )
        }
        val frame = frame(
            ProcessMetric(firstKey, 100, 200, 150, 1, 'R'),
            ProcessMetric(secondKey, 250, 300, null, null, 'S'),
        )

        val aggregate = ApplicationAggregator.aggregate(frame, catalog, resolutions).single()

        assertEquals("app:com.example", aggregate.stableId)
        assertEquals(350, aggregate.cpuPercentBasisPoints)
        assertTrue(aggregate.cpuComplete)
        assertEquals(500L, aggregate.rssKb)
        assertTrue(aggregate.rssComplete)
        assertEquals(150L, aggregate.pssKb)
        assertFalse(aggregate.pssComplete)
        assertEquals(listOf(secondKey, firstKey), aggregate.processes.map { it.key })
    }

    @Test
    fun unresolvedSharedUidProcessesAggregateWithoutInventingAPrimaryPackage() {
        val firstKey = ProcessKey(20, 200L)
        val secondKey = ProcessKey(21, 210L)
        val catalog = listOf(
            ProcessCatalogEntry(firstKey, 1, 1000, "shared.one", ""),
            ProcessCatalogEntry(secondKey, 1, 1000, "shared.two", ""),
        )
        val resolutions = catalog.map { entry ->
            ProcessPackageResolution(
                key = entry.key,
                packageCandidates = listOf("android", "com.vendor.shared"),
                primaryPackage = null,
                displayName = null,
                isSystem = true,
                isNative = false,
                isSharedUid = true,
            )
        }

        val aggregate = ApplicationAggregator.aggregate(
            frame(ProcessMetric(firstKey, null, 10, null, null, 'S'), ProcessMetric(secondKey, null, 20, null, null, 'S')),
            catalog,
            resolutions,
        ).single()

        assertEquals("uid:1000", aggregate.stableId)
        assertNull(aggregate.primaryPackage)
        assertTrue(aggregate.isSharedUid)
        assertTrue(aggregate.isSystem)
        assertNull(aggregate.cpuPercentBasisPoints)
        assertEquals(30L, aggregate.rssKb)
    }

    @Test
    fun missingResolutionIsNotMislabelledAsNative() {
        val key = ProcessKey(30, 300L)
        val aggregate = ApplicationAggregator.aggregate(
            frame(ProcessMetric(key, 10, 20, null, null, 'S')),
            catalog = emptyList(),
            resolutions = emptyList(),
        ).single()

        assertEquals("process:30:300", aggregate.stableId)
        assertFalse(aggregate.isNative)
        assertFalse(aggregate.isSystem)
    }

    private fun frame(vararg metrics: ProcessMetric) = MetricFrame(
        sequence = 1,
        elapsedRealtimeNanos = 1,
        wallTimeMillis = 1,
        systemCpuPercentBasisPoints = 100,
        memoryTotalKb = 1000,
        memoryAvailableKb = 500,
        collectionDurationMs = 1,
        catalogRevision = 1,
        frameFlags = 0,
        metrics = metrics.toList(),
    )
}
