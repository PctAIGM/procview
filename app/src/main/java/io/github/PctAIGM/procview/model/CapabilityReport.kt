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
    val pssProbeKb: Long?,
    val pssProbeDurationMs: Long,
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
) {
    val metricCoverage: Double
        get() = if (procPidCount == 0) 0.0 else cpuAndRssReadableCount.toDouble() / procPidCount
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
