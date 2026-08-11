package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.RawMetricFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeMonitorBackend(
    private val capabilityReport: CapabilityReport,
    private val frameSequence: List<RawMetricFrame> = emptyList(),
    private val catalogs: Map<Long, List<ProcessCatalogEntry>> = emptyMap(),
    private val pssValues: Map<ProcessKey, Long> = emptyMap(),
    private val failureAtFrameIndex: Int? = null,
) : PrivilegedMonitorBackend {
    override suspend fun probe(): CapabilityReport = capabilityReport

    override fun frames(config: SamplingConfig): Flow<RawMetricFrame> = flow {
        frameSequence.forEachIndexed { index, frame ->
            if (index == failureAtFrameIndex) throw FakeBackendDisconnectedException()
            emit(frame)
        }
    }

    override suspend fun readCatalog(revision: Long): CatalogSnapshot =
        CatalogSnapshot(revision, catalogs[revision].orEmpty())

    override suspend fun readPss(keys: List<ProcessKey>): PssBatch = PssBatch(
        sampledAtElapsedRealtimeNanos = frameSequence.lastOrNull()?.elapsedRealtimeNanos ?: 0L,
        durationMs = 0L,
        valuesKb = keys.distinct().mapNotNull { key -> pssValues[key]?.let { key to it } }.toMap(),
        errorFlags = 0,
    )

    override suspend fun diagnostics(): DiagnosticBundle = DiagnosticBundle(
        backendName = "fake",
        protocolVersion = capabilityReport.protocolVersion,
        notes = emptyList(),
    )
}

class FakeBackendDisconnectedException : IllegalStateException("Fake backend disconnected")
