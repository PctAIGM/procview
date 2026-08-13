package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.RawMetricFrame
import kotlinx.coroutines.flow.Flow

data class SamplingConfig(
    val intervalMs: Long,
    val pssIntervalMs: Long,
) {
    init {
        require(intervalMs >= MIN_INTERVAL_MS) { "Sampling below 1 second is not supported" }
        require(pssIntervalMs >= intervalMs) { "PSS interval must not be shorter than frame interval" }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1_000L
    }
}

data class PssBatch(
    val sampledAtElapsedRealtimeNanos: Long,
    val durationMs: Long,
    val valuesKb: Map<ProcessKey, Long>,
    val errorFlags: Int,
)

data class CatalogSnapshot(
    val revision: Long,
    val entries: List<ProcessCatalogEntry>,
)

data class DiagnosticBundle(
    val backendName: String,
    val protocolVersion: Int,
    val notes: List<String>,
)

interface PrivilegedMonitorBackend {
    suspend fun probe(): CapabilityReport
    suspend fun verifyBootId(): String = probe().bootId
    fun frames(config: SamplingConfig): Flow<RawMetricFrame>
    suspend fun readCatalog(revision: Long): CatalogSnapshot
    suspend fun readPss(keys: List<ProcessKey>): PssBatch
    suspend fun diagnostics(): DiagnosticBundle
}
