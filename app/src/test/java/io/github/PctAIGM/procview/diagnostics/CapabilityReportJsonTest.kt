package io.github.PctAIGM.procview.diagnostics

import io.github.PctAIGM.procview.model.BackendMode
import io.github.PctAIGM.procview.model.CapabilityQuality
import io.github.PctAIGM.procview.model.CapabilityReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReportJsonTest {
    @Test
    fun reportEnvelopeUsesVersionedTypedJson() {
        val envelope = CapabilityReportEnvelope(
            generatedAtWallTimeMs = 1234L,
            applicationId = "io.github.PctAIGM.procview.debug",
            procViewVersion = "1.0.0-debug",
            device = CapabilityDeviceSnapshot(
                manufacturer = "Example",
                model = "Device",
                androidRelease = "16",
                androidSdk = 36,
                romDisplay = "ROM",
            ),
            capability = sampleReport(),
        )

        val encoded = CapabilityReportJson.encode(envelope)

        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertTrue(encoded.contains("\"bootId\": \"boot-test\""))
        assertTrue(encoded.contains("\"cpuAndRssReadableCount\": 99"))
        assertTrue(encoded.contains("\"thermalSensorNames\": ["))
        assertFalse(encoded.contains("metricCoverage"))
    }

    private fun sampleReport() = CapabilityReport(
        probedAtWallTimeMs = 1000L,
        shizukuApiVersion = 13,
        shizukuUid = 2000,
        shizukuSelinuxContext = "u:r:shell:s0",
        serviceUid = 2000,
        servicePid = 321,
        backendMode = BackendMode.ADB,
            protocolVersion = 2,
        bootId = "boot-test",
        procStatReadable = true,
        procMeminfoReadable = true,
        procPidCount = 100,
        psPidCount = 100,
        statReadableCount = 100,
        statusReadableCount = 100,
        cmdlineReadableCount = 100,
        rssReadableCount = 99,
        cpuAndRssReadableCount = 99,
        pid1StatReadable = true,
        psCommandAvailable = true,
        pssCommandAvailable = true,
        pssValueParsed = true,
        pssProbeKb = 12_345L,
        pssProbeDurationMs = 15L,
        thermalZoneCount = 2,
        thermalReadableCount = 1,
        thermalSensorNames = listOf("battery"),
        mappedUidCount = 40,
        sampledUidCount = 41,
        packageCandidateCount = 43,
        procScanDurationMs = 25L,
        totalDurationMs = 50L,
        processListTruncated = false,
        errorFlags = 0,
        quality = CapabilityQuality.AVAILABLE,
    )
}
