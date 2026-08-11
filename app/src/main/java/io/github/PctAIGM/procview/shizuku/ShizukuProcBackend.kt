package io.github.PctAIGM.procview.shizuku

import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.MetricDataSource
import io.github.PctAIGM.procview.model.MetricFrameFlags
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.RawMetricFrame
import io.github.PctAIGM.procview.model.RawProcessMetric
import io.github.PctAIGM.procview.model.ShizukuPhase
import io.github.PctAIGM.procview.sampler.CatalogSnapshot
import io.github.PctAIGM.procview.sampler.DiagnosticBundle
import io.github.PctAIGM.procview.sampler.FixedTargetSchedule
import io.github.PctAIGM.procview.sampler.PrivilegedMonitorBackend
import io.github.PctAIGM.procview.sampler.PssBatch
import io.github.PctAIGM.procview.sampler.SamplingConfig
import io.github.PctAIGM.procview.shizuku.ipc.IProcViewUserService
import io.github.PctAIGM.procview.shizuku.ipc.IpcCodes
import io.github.PctAIGM.procview.shizuku.ipc.ProcessCatalogChunkParcel
import io.github.PctAIGM.procview.shizuku.ipc.ProcessKeyParcel
import io.github.PctAIGM.procview.shizuku.ipc.RawMetricFrameParcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex

class ShizukuProcBackend(
    private val coordinator: ShizukuCoordinator,
    private val monotonicTimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) : PrivilegedMonitorBackend {
    private val frameCollectionMutex = Mutex()

    override suspend fun probe(): CapabilityReport {
        withContext(Dispatchers.Main.immediate) { coordinator.refresh() }
        val state = withTimeout(PROBE_TIMEOUT_MS) {
            coordinator.state.first { value -> value.phase in TERMINAL_PROBE_PHASES }
        }
        return state.report ?: throw BackendUnavailableException("Shizuku phase: ${state.phase}")
    }

    override fun frames(config: SamplingConfig): Flow<RawMetricFrame> = flow {
        if (!frameCollectionMutex.tryLock()) {
            throw IllegalStateException("Only one Shizuku frame collector is allowed")
        }
        try {
            var targetMs = monotonicTimeMs()
            while (currentCoroutineContext().isActive) {
                val parcel = withContext(Dispatchers.IO) { requireService().collectMetricFrame() }
                var frame = parcel.toDomain()
                val nowMs = monotonicTimeMs()
                val next = FixedTargetSchedule.next(targetMs, nowMs, config.intervalMs)
                if (next.skippedTicks > 0) {
                    frame = frame.copy(
                        frameFlags = frame.frameFlags or MetricFrameFlags.SAMPLER_SKIPPED_TICK,
                    )
                }
                emit(frame)
                targetMs = next.targetMs
                delay((targetMs - monotonicTimeMs()).coerceAtLeast(0L))
            }
        } finally {
            frameCollectionMutex.unlock()
        }
    }

    override suspend fun readCatalog(revision: Long): CatalogSnapshot = withContext(Dispatchers.IO) {
        require(revision >= 0) { "catalog revision must not be negative" }
        val service = requireService()
        var expectedRevision = revision
        var offset = 0
        var restarts = 0
        val entries = ArrayList<ProcessCatalogEntry>()

        while (true) {
            val chunk = service.getCatalogChunk(expectedRevision, offset, CATALOG_CHUNK_SIZE)
            if (chunk.restartRequired || (offset > 0 && chunk.revision != expectedRevision)) {
                if (++restarts > MAX_CATALOG_RESTARTS) {
                    throw BackendProtocolException("catalog changed repeatedly during transfer")
                }
                expectedRevision = chunk.revision
                offset = 0
                entries.clear()
                continue
            }
            if (offset == 0) expectedRevision = chunk.revision
            validateCatalogChunk(chunk, offset)
            entries += chunk.entries.orEmpty().mapNotNull { parcel ->
                val key = runCatching { ProcessKey(parcel.pid, parcel.startTimeTicks) }.getOrNull()
                    ?: return@mapNotNull null
                ProcessCatalogEntry(
                    key = key,
                    parentPid = parcel.parentPid,
                    uid = parcel.uid.takeIf { it >= 0 },
                    processName = parcel.processName.take(MAX_PROCESS_NAME_CHARS),
                    commandLine = parcel.commandLine.take(MAX_COMMAND_LINE_CHARS),
                )
            }
            if (entries.size > MAX_CATALOG_ENTRIES) {
                throw BackendProtocolException("catalog exceeds process limit")
            }
            if (chunk.nextOffset < 0) {
                if (entries.size != chunk.totalEntries) {
                    throw BackendProtocolException("incomplete catalog transfer")
                }
                return@withContext CatalogSnapshot(expectedRevision, entries)
            }
            offset = chunk.nextOffset
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    override suspend fun readPss(keys: List<ProcessKey>): PssBatch = withContext(Dispatchers.IO) {
        val uniqueKeys = keys.distinct()
        require(uniqueKeys.size <= MAX_PSS_KEYS) { "too many PSS keys" }
        val parcels = uniqueKeys.map { key ->
            ProcessKeyParcel().also { parcel ->
                parcel.pid = key.pid
                parcel.startTimeTicks = key.startTimeTicks
            }
        }.toTypedArray()
        val result = requireService().readPss(parcels)
        val expected = uniqueKeys.toSet()
        if (
            result.sampledAtElapsedRealtimeNanos < 0 ||
            result.durationMs < 0 ||
            result.values.orEmpty().size > uniqueKeys.size
        ) {
            throw BackendProtocolException("invalid PSS result bounds")
        }
        val values = result.values.orEmpty().mapNotNull { value ->
            val key = runCatching { ProcessKey(value.pid, value.startTimeTicks) }.getOrNull()
                ?: return@mapNotNull null
            if (key !in expected || value.pssKb < 0) return@mapNotNull null
            key to value.pssKb
        }.toMap()
        PssBatch(
            sampledAtElapsedRealtimeNanos = result.sampledAtElapsedRealtimeNanos,
            durationMs = result.durationMs,
            valuesKb = values,
            errorFlags = result.errorFlags,
        )
    }

    override suspend fun diagnostics(): DiagnosticBundle {
        val service = coordinator.connectedUserService()
        return DiagnosticBundle(
            backendName = "shizuku-procfs",
            protocolVersion = service?.let { runCatching { it.protocolVersion }.getOrNull() } ?: -1,
            notes = listOf("No arbitrary command IPC", "Catalog strings are chunked"),
        )
    }

    private fun requireService(): IProcViewUserService = coordinator.connectedUserService()
        ?: throw BackendUnavailableException("Shizuku UserService is not connected")

    private fun RawMetricFrameParcel.toDomain(): RawMetricFrame {
        if (sequence < 0 || elapsedRealtimeNanos < 0 || wallTimeMillis < 0 || catalogRevision < 0) {
            throw BackendProtocolException("metric frame contains invalid counters")
        }
        if (metrics.orEmpty().size > MAX_CATALOG_ENTRIES) {
            throw BackendProtocolException("metric frame exceeds process limit")
        }
        val mappedMetrics = metrics.orEmpty().map { metric ->
            val key = runCatching { ProcessKey(metric.pid, metric.startTimeTicks) }.getOrNull()
                ?: throw BackendProtocolException("metric frame contains an invalid process key")
            if (metric.cpuTicks < 0) {
                throw BackendProtocolException("metric frame contains negative process ticks")
            }
            RawProcessMetric(
                key = key,
                cpuTicks = metric.cpuTicks,
                rssKb = metric.rssKb.takeIf { it >= 0 },
                state = metric.stateCode.takeIf { it in Char.MIN_VALUE.code..Char.MAX_VALUE.code }
                    ?.toChar()
                    ?: '?',
            )
        }
        if (mappedMetrics.map(RawProcessMetric::key).toSet().size != mappedMetrics.size) {
            throw BackendProtocolException("metric frame contains duplicate process keys")
        }
        return RawMetricFrame(
            sequence = sequence,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            wallTimeMillis = wallTimeMillis,
            systemTotalCpuTicks = systemTotalCpuTicks.takeIf { it >= 0 },
            systemIdleCpuTicks = systemIdleCpuTicks.takeIf { it >= 0 },
            memoryTotalKb = memoryTotalKb.takeIf { it >= 0 },
            memoryAvailableKb = memoryAvailableKb.takeIf { it >= 0 },
            collectionDurationMs = collectionDurationMs.coerceAtLeast(0),
            catalogRevision = catalogRevision,
            source = when (sourceCode) {
                IpcCodes.SOURCE_PROCFS -> MetricDataSource.PROCFS
                IpcCodes.SOURCE_PS_FALLBACK -> MetricDataSource.PS_FALLBACK
                else -> throw BackendProtocolException("unknown metric data source")
            },
            frameFlags = frameFlags,
            metrics = mappedMetrics,
        )
    }

    private fun validateCatalogChunk(chunk: ProcessCatalogChunkParcel, expectedOffset: Int) {
        if (
            chunk.revision < 0 ||
            chunk.offset != expectedOffset ||
            chunk.totalEntries !in 0..MAX_CATALOG_ENTRIES
        ) {
            throw BackendProtocolException("invalid catalog chunk bounds")
        }
        if (chunk.entries.orEmpty().size > CATALOG_CHUNK_SIZE) {
            throw BackendProtocolException("catalog chunk exceeds limit")
        }
        if (chunk.nextOffset >= 0 && chunk.nextOffset <= expectedOffset) {
            throw BackendProtocolException("catalog chunk did not advance")
        }
        if (chunk.nextOffset > chunk.totalEntries) {
            throw BackendProtocolException("catalog chunk exceeds total entry count")
        }
        val expectedEnd = if (chunk.nextOffset >= 0) chunk.nextOffset else chunk.totalEntries
        if (chunk.entries.orEmpty().size != expectedEnd - expectedOffset) {
            throw BackendProtocolException("catalog chunk size does not match bounds")
        }
    }

    private companion object {
        val TERMINAL_PROBE_PHASES = setOf(
            ShizukuPhase.NOT_INSTALLED,
            ShizukuPhase.NOT_RUNNING,
            ShizukuPhase.INCOMPATIBLE,
            ShizukuPhase.PERMISSION_REQUIRED,
            ShizukuPhase.PERMISSION_DENIED,
            ShizukuPhase.AVAILABLE,
            ShizukuPhase.PARTIAL,
            ShizukuPhase.ERROR,
        )
        const val PROBE_TIMEOUT_MS = 15_000L
        const val CATALOG_CHUNK_SIZE = 32
        const val MAX_CATALOG_ENTRIES = 4096
        const val MAX_CATALOG_RESTARTS = 3
        const val MAX_PSS_KEYS = 128
        const val MAX_PROCESS_NAME_CHARS = 256
        const val MAX_COMMAND_LINE_CHARS = 4096
    }
}

class BackendUnavailableException(message: String) : IllegalStateException(message)
class BackendProtocolException(message: String) : IllegalStateException(message)
