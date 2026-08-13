package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.BackendMode
import io.github.PctAIGM.procview.model.CapabilityQuality
import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.RawMetricFrame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FakeMonitorBackendTest {
    @Test
    fun fakeBackendProvidesDeterministicFramesAndPss() = runTest {
        val key = ProcessKey(1, 10L)
        val frames = listOf(rawFrame(1), rawFrame(2))
        val backend = FakeMonitorBackend(
            capabilityReport = capabilityReport(),
            frameSequence = frames,
            pssValues = mapOf(key to 50L),
        )

        assertEquals(frames, backend.frames(SamplingConfig(1000, 15_000)).toList())
        assertEquals(mapOf(key to 50L), backend.readPss(listOf(key, key)).valuesKb)
        assertEquals(CapabilityQuality.AVAILABLE, backend.probe().quality)
    }

    @Test
    fun fakeBackendCanModelBinderDisconnection() {
        val backend = FakeMonitorBackend(
            capabilityReport = capabilityReport(),
            frameSequence = listOf(rawFrame(1)),
            failureAtFrameIndex = 0,
        )

        assertThrows(FakeBackendDisconnectedException::class.java) {
            runTest { backend.frames(SamplingConfig(1000, 15_000)).toList() }
        }
    }

    private fun rawFrame(sequence: Long) = RawMetricFrame(
        sequence = sequence,
        elapsedRealtimeNanos = sequence,
        wallTimeMillis = sequence,
        systemTotalCpuTicks = null,
        systemIdleCpuTicks = null,
        memoryTotalKb = null,
        memoryAvailableKb = null,
        collectionDurationMs = 0,
        catalogRevision = 0,
        frameFlags = 0,
        metrics = emptyList(),
    )

    private fun capabilityReport() = CapabilityReport(
        probedAtWallTimeMs = 1,
        shizukuApiVersion = 13,
        shizukuUid = 2000,
        shizukuSelinuxContext = "u:r:shell:s0",
        serviceUid = 2000,
        servicePid = 1,
        backendMode = BackendMode.ADB,
        protocolVersion = 4,
        bootId = "boot",
        procStatReadable = true,
        procMeminfoReadable = true,
        procPidCount = 1,
        psPidCount = 1,
        statReadableCount = 1,
        statusReadableCount = 1,
        cmdlineReadableCount = 1,
        rssReadableCount = 1,
        cpuAndRssReadableCount = 1,
        pid1StatReadable = true,
        psCommandAvailable = true,
        pssCommandAvailable = true,
        pssValueParsed = true,
        pssReadableCount = 1,
        pssProbeKb = 1,
        pssProbeDurationMs = 1,
        pssBatchProbeDurationMs = 1,
        thermalZoneCount = 1,
        thermalReadableCount = 1,
        thermalSensorNames = listOf("battery"),
        mappedUidCount = 1,
        sampledUidCount = 1,
        packageCandidateCount = 1,
        procScanDurationMs = 1,
        totalDurationMs = 1,
        processListTruncated = false,
        errorFlags = 0,
        quality = CapabilityQuality.AVAILABLE,
    )
}
