package io.github.PctAIGM.procview.sampler.procfs

import io.github.PctAIGM.procview.model.MetricFrameFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcSnapshotReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun snapshotReadsTypedMetricsCatalogAndSystemValues() {
        val proc = temporaryFolder.newFolder("proc")
        proc.resolve("stat").writeText("cpu 10 0 5 80 5 0 0 0\n")
        proc.resolve("meminfo").writeText("MemTotal: 8192 kB\nMemAvailable: 4096 kB\n")
        val process = proc.resolve("42").apply { mkdir() }
        process.resolve("stat").writeText(
            "42 (worker (render)) R 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 190 20",
        )
        process.resolve("status").writeText(
            "State: R\nPPid: 1\nUid: 10234 10234 10234 10234\nVmRSS: 2048 kB\n",
        )
        process.resolve("cmdline").writeBytes("com.example\u0000--worker\u0000".toByteArray())
        val clockValues = ArrayDeque(listOf(1_000_000L, 5_000_000L))
        val reader = ProcSnapshotReader(
            procRoot = proc,
            elapsedRealtimeNanos = { clockValues.removeFirst() },
            wallTimeMillis = { 1234L },
            pageSizeKb = 4L,
        )

        val snapshot = reader.read()

        assertEquals(100L, snapshot.systemCpu?.totalTicks)
        assertEquals(85L, snapshot.systemCpu?.idleTicks)
        assertEquals(8192L, snapshot.systemMemory?.totalKb)
        assertEquals(4096L, snapshot.systemMemory?.availableKb)
        assertEquals(4L, snapshot.collectionDurationMs)
        assertEquals(1, snapshot.metrics.size)
        assertEquals(42, snapshot.metrics.single().key.pid)
        assertEquals(190L, snapshot.metrics.single().key.startTimeTicks)
        assertEquals(23L, snapshot.metrics.single().cpuTicks)
        assertEquals(2048L, snapshot.metrics.single().rssKb)
        assertEquals("worker (render)", snapshot.catalog.single().processName)
        assertEquals("com.example --worker", snapshot.catalog.single().commandLine)
        assertFalse(snapshot.frameFlags and MetricFrameFlags.PROCESS_LIST_TRUNCATED != 0)
    }

    @Test
    fun snapshotFallsBackToStatmAndFlagsMissingSystemSources() {
        val proc = temporaryFolder.newFolder("proc-fallback")
        val process = proc.resolve("7").apply { mkdir() }
        process.resolve("stat").writeText(
            "7 (native) S 1 2 3 4 5 6 7 8 9 10 3 4 13 14 15 16 17 18 70 20",
        )
        process.resolve("status").writeText("State: S\nPPid: 1\nUid: 2000 2000 2000 2000\n")
        process.resolve("statm").writeText("100 25 0 0 0 0 0")
        process.resolve("cmdline").writeText("")
        val clockValues = ArrayDeque(listOf(0L, 1_000_000L))
        val snapshot = ProcSnapshotReader(
            procRoot = proc,
            elapsedRealtimeNanos = { clockValues.removeFirst() },
            wallTimeMillis = { 1L },
            pageSizeKb = 4L,
        ).read()

        assertEquals(100L, snapshot.metrics.single().rssKb)
        assertTrue(snapshot.frameFlags and MetricFrameFlags.SYSTEM_CPU_UNREADABLE != 0)
        assertTrue(snapshot.frameFlags and MetricFrameFlags.SYSTEM_MEMORY_UNREADABLE != 0)
    }

    @Test
    fun snapshotRejectsStatWhosePidDoesNotMatchItsDirectory() {
        val proc = temporaryFolder.newFolder("proc-pid-mismatch")
        proc.resolve("stat").writeText("cpu 10 0 5 80 5 0 0 0\n")
        proc.resolve("meminfo").writeText("MemTotal: 8192 kB\nMemAvailable: 4096 kB\n")
        val process = proc.resolve("7").apply { mkdir() }
        process.resolve("stat").writeText(
            "8 (wrong) S 1 2 3 4 5 6 7 8 9 10 3 4 13 14 15 16 17 18 70 20",
        )
        val clockValues = ArrayDeque(listOf(0L, 1_000_000L))

        val snapshot = ProcSnapshotReader(
            procRoot = proc,
            elapsedRealtimeNanos = { clockValues.removeFirst() },
            wallTimeMillis = { 1L },
            pageSizeKb = 4L,
        ).read()

        assertTrue(snapshot.metrics.isEmpty())
        assertTrue(snapshot.catalog.isEmpty())
    }
}
