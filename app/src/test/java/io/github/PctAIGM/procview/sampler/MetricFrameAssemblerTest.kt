package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.MetricFrameFlags
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.RawMetricFrame
import io.github.PctAIGM.procview.model.RawProcessMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricFrameAssemblerTest {
    private val key = ProcessKey(pid = 42, startTimeTicks = 1000L)

    @Test
    fun firstFrameHasNoCpuAndSecondFrameUsesWholeMachineNormalization() {
        val assembler = MetricFrameAssembler()
        val first = assembler.assemble(raw(sequence = 1, total = 100, idle = 60, processTicks = 10))
        val second = assembler.assemble(raw(sequence = 2, total = 200, idle = 100, processTicks = 30))

        assertNull(first.systemCpuPercentBasisPoints)
        assertNull(first.metrics.single().cpuPercentBasisPoints)
        assertEquals(6000, second.systemCpuPercentBasisPoints)
        assertEquals(2000, second.metrics.single().cpuPercentBasisPoints)
    }

    @Test
    fun pidReuseStartsANewCpuBaseline() {
        val assembler = MetricFrameAssembler()
        assembler.assemble(raw(sequence = 1, total = 100, idle = 50, processTicks = 80))
        val reused = ProcessKey(pid = 42, startTimeTicks = 2000L)
        val frame = assembler.assemble(
            raw(
                sequence = 2,
                total = 200,
                idle = 100,
                processTicks = 5,
                processKey = reused,
            ),
        )

        assertNull(frame.metrics.single().cpuPercentBasisPoints)
        assertEquals(reused, frame.metrics.single().key)
    }

    @Test
    fun counterRegressionIsFlaggedAndDoesNotEmitFalseZero() {
        val assembler = MetricFrameAssembler()
        assembler.assemble(raw(sequence = 1, total = 100, idle = 50, processTicks = 80))
        val processReset = assembler.assemble(raw(sequence = 2, total = 200, idle = 100, processTicks = 70))
        val systemReset = assembler.assemble(raw(sequence = 3, total = 50, idle = 20, processTicks = 75))

        assertNull(processReset.metrics.single().cpuPercentBasisPoints)
        assertTrue(processReset.frameFlags and MetricFrameFlags.PROCESS_COUNTER_RESET != 0)
        assertNull(systemReset.systemCpuPercentBasisPoints)
        assertNull(systemReset.metrics.single().cpuPercentBasisPoints)
        assertTrue(systemReset.frameFlags and MetricFrameFlags.CPU_COUNTER_RESET != 0)
    }

    @Test
    fun pssCacheCarriesTimestampAndDropsExitedKeys() {
        val assembler = MetricFrameAssembler()
        assembler.recordPss(mapOf(key to 1234L), sampledAtElapsedRealtimeNanos = 99L)
        val withPss = assembler.assemble(raw(sequence = 1, total = 100, idle = 50, processTicks = 10))
        val otherKey = ProcessKey(7, 77L)
        assembler.assemble(
            raw(sequence = 2, total = 200, idle = 100, processTicks = 10, processKey = otherKey),
        )
        val returned = assembler.assemble(raw(sequence = 3, total = 300, idle = 150, processTicks = 20))

        assertEquals(1234L, withPss.metrics.single().pssKb)
        assertEquals(99L, withPss.metrics.single().pssSampleElapsedRealtimeNanos)
        assertNull(returned.metrics.single().pssKb)
    }

    private fun raw(
        sequence: Long,
        total: Long?,
        idle: Long?,
        processTicks: Long,
        processKey: ProcessKey = key,
    ) = RawMetricFrame(
        sequence = sequence,
        elapsedRealtimeNanos = sequence * 1_000_000_000L,
        wallTimeMillis = sequence * 1000L,
        systemTotalCpuTicks = total,
        systemIdleCpuTicks = idle,
        memoryTotalKb = 8000,
        memoryAvailableKb = 4000,
        collectionDurationMs = 10,
        catalogRevision = 1,
        frameFlags = MetricFrameFlags.NONE,
        metrics = listOf(
            RawProcessMetric(
                key = processKey,
                cpuTicks = processTicks,
                rssKb = 100,
                state = 'R',
            ),
        ),
    )
}
