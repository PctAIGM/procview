package io.github.PctAIGM.procview.model

import kotlinx.serialization.Serializable

@Serializable
enum class BackendMode {
    ADB,
    ROOT,
    UNKNOWN,
}

@Serializable
enum class CapabilityQuality {
    AVAILABLE,
    PARTIAL,
}

@Serializable
data class CapabilityReport(
    val probedAtWallTimeMs: Long,
    val shizukuApiVersion: Int,
    val shizukuUid: Int,
    val shizukuSelinuxContext: String,
    val serviceUid: Int,
    val servicePid: Int,
    val backendMode: BackendMode,
    val protocolVersion: Int,
    val bootId: String,
    val procStatReadable: Boolean,
    val procMeminfoReadable: Boolean,
    val procPidCount: Int,
    val psPidCount: Int,
    val statReadableCount: Int,
    val statusReadableCount: Int,
    val cmdlineReadableCount: Int,
    val rssReadableCount: Int,
    val cpuAndRssReadableCount: Int,
    val pid1StatReadable: Boolean,
    val psCommandAvailable: Boolean,
    val pssCommandAvailable: Boolean,
    val pssValueParsed: Boolean,
    val pssReadableCount: Int,
    val pssProbeKb: Long?,
    val pssProbeDurationMs: Long,
    val pssBatchProbeDurationMs: Long,
    val thermalZoneCount: Int,
    val thermalReadableCount: Int,
    val thermalSensorNames: List<String>,
    val mappedUidCount: Int,
    val sampledUidCount: Int,
    val packageCandidateCount: Int,
    val procScanDurationMs: Long,
    val totalDurationMs: Long,
    val processListTruncated: Boolean,
    val errorFlags: Int,
    val quality: CapabilityQuality,
    val psSnapshotAvailable: Boolean = false,
    val psSnapshotPidCount: Int = 0,
    val psSnapshotCpuAndRssReadableCount: Int = 0,
    val psSnapshotDurationMs: Long = 0L,
    val psFallbackSelected: Boolean = false,
) {
    val metricCoverageReferenceCount: Int
        get() = psPidCount.takeIf { psCommandAvailable && it > 0 } ?: 0

    val metricCoverage: Double
        get() = if (metricCoverageReferenceCount <= 0) {
            0.0
        } else {
            (cpuAndRssReadableCount.toDouble() / metricCoverageReferenceCount).coerceIn(0.0, 1.0)
        }
}

enum class ShizukuPhase {
    CHECKING,
    NOT_INSTALLED,
    NOT_RUNNING,
    INCOMPATIBLE,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    CONNECTING,
    PROBING,
    AVAILABLE,
    PARTIAL,
    ERROR,
}

enum class ShizukuFailure {
    NONE,
    API_TOO_OLD,
    BIND_FAILED,
    BIND_TIMEOUT,
    INVALID_BINDER,
    PROTOCOL_MISMATCH,
    PROBE_CANCELLED,
    PROBE_FAILED,
}

data class ShizukuUiState(
    val phase: ShizukuPhase,
    val shizukuApiVersion: Int? = null,
    val minimumApiVersion: Int = 13,
    val failure: ShizukuFailure = ShizukuFailure.NONE,
    val report: CapabilityReport? = null,
) {
    companion object {
        val Checking = ShizukuUiState(phase = ShizukuPhase.CHECKING)
    }
}
