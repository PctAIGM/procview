package io.github.PctAIGM.procview.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import io.github.PctAIGM.procview.model.MetricDataSource
import io.github.PctAIGM.procview.model.ShizukuPhase
import io.github.PctAIGM.procview.sampler.AndroidPackageResolver
import io.github.PctAIGM.procview.sampler.ApplicationAggregator
import io.github.PctAIGM.procview.sampler.MetricFrameAssembler
import io.github.PctAIGM.procview.sampler.PrivilegedMonitorBackend
import io.github.PctAIGM.procview.sampler.RetentionPolicy
import io.github.PctAIGM.procview.sampler.SamplingConfig
import io.github.PctAIGM.procview.shizuku.ShizukuCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ShizukuProbeViewModel(
    private val coordinator: ShizukuCoordinator,
    private val backend: PrivilegedMonitorBackend,
    private val packageResolver: AndroidPackageResolver,
) : ViewModel() {
    val state = coordinator.state
    private val _samplingPreview = MutableStateFlow<SamplingPreviewState>(SamplingPreviewState.Idle)
    val samplingPreview = _samplingPreview.asStateFlow()
    private var previewJob: Job? = null

    fun performPrimaryAction() {
        when (state.value.phase) {
            ShizukuPhase.NOT_INSTALLED,
            ShizukuPhase.NOT_RUNNING,
            -> coordinator.openShizukuOrDownload()

            ShizukuPhase.PERMISSION_REQUIRED,
            ShizukuPhase.PERMISSION_DENIED,
            -> coordinator.requestPermission()

            ShizukuPhase.INCOMPATIBLE,
            ShizukuPhase.AVAILABLE,
            ShizukuPhase.PARTIAL,
            ShizukuPhase.ERROR,
            -> coordinator.refresh()

            ShizukuPhase.CHECKING,
            ShizukuPhase.CONNECTING,
            -> Unit

            ShizukuPhase.PROBING -> coordinator.cancelProbe()
        }
    }

    fun runSamplingPreview() {
        if (previewJob?.isActive == true) return
        if (state.value.phase !in PREVIEW_PHASES) return
        previewJob = viewModelScope.launch {
            _samplingPreview.value = SamplingPreviewState.Running
            try {
                val result = withTimeout(PREVIEW_TIMEOUT_MS) {
                    backend.probe()
                    val rawFrames = backend.frames(PREVIEW_CONFIG).take(PREVIEW_FRAME_COUNT).toList()
                    check(rawFrames.size == PREVIEW_FRAME_COUNT) { "sampling ended before two frames" }
                    val assembler = MetricFrameAssembler()
                    val first = assembler.assemble(rawFrames[0])
                    var current = assembler.assemble(rawFrames[1])
                    val catalog = backend.readCatalog(current.catalogRevision)
                    val report = state.value.report
                    if (report?.pssCommandAvailable == true) {
                        val targets = RetentionPolicy.selectPssTargets(
                            frame = current,
                            pinnedKeys = emptySet(),
                            detailKeys = emptySet(),
                        ).keys
                        if (targets.isNotEmpty()) {
                            val pss = try {
                                backend.readPss(targets)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                null
                            }
                            if (pss != null) {
                                current = current.copy(
                                    metrics = current.metrics.map { metric ->
                                        pss.valuesKb[metric.key]?.let { pssKb ->
                                            metric.copy(
                                                pssKb = pssKb,
                                                pssSampleElapsedRealtimeNanos =
                                                    pss.sampledAtElapsedRealtimeNanos,
                                            )
                                        } ?: metric
                                    },
                                )
                            }
                        }
                    }
                    val resolutions = packageResolver.resolve(catalog.entries)
                    val applications = ApplicationAggregator.aggregate(
                        frame = current,
                        catalog = catalog.entries,
                        resolutions = resolutions,
                    )
                    SamplingPreviewResult(
                        sequence = current.sequence,
                        intervalMs = ((current.elapsedRealtimeNanos - first.elapsedRealtimeNanos)
                            .coerceAtLeast(0L)) / NANOS_PER_MILLISECOND,
                        processCount = current.metrics.size,
                        catalogCount = catalog.entries.size,
                        applicationCount = applications.size,
                        cpuValueCount = current.metrics.count { it.cpuPercentBasisPoints != null },
                        rssValueCount = current.metrics.count { it.rssKb != null },
                        pssValueCount = current.metrics.count { it.pssKb != null },
                        systemCpuPercentBasisPoints = current.systemCpuPercentBasisPoints,
                        memoryTotalKb = current.memoryTotalKb,
                        memoryAvailableKb = current.memoryAvailableKb,
                        collectionDurationMs = current.collectionDurationMs,
                        catalogRevision = catalog.revision,
                        source = current.source,
                        frameFlags = current.frameFlags,
                    )
                }
                _samplingPreview.value = SamplingPreviewState.Ready(result)
            } catch (_: TimeoutCancellationException) {
                _samplingPreview.value = SamplingPreviewState.Failed(errorType = "Timeout")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _samplingPreview.value = SamplingPreviewState.Failed(
                    errorType = error::class.java.simpleName.ifBlank { "UnexpectedError" }
                        .take(MAX_ERROR_TYPE_CHARS),
                )
            }
        }
    }

    class Factory(
        private val coordinator: ShizukuCoordinator,
        private val backend: PrivilegedMonitorBackend,
        private val packageResolver: AndroidPackageResolver,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(ShizukuProbeViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ShizukuProbeViewModel(coordinator, backend, packageResolver) as T
        }
    }

    private companion object {
        val PREVIEW_PHASES = setOf(ShizukuPhase.AVAILABLE, ShizukuPhase.PARTIAL)
        val PREVIEW_CONFIG = SamplingConfig(intervalMs = 1_000L, pssIntervalMs = 15_000L)
        const val PREVIEW_FRAME_COUNT = 2
        const val PREVIEW_TIMEOUT_MS = 30_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_ERROR_TYPE_CHARS = 80
    }
}

sealed interface SamplingPreviewState {
    data object Idle : SamplingPreviewState
    data object Running : SamplingPreviewState
    data class Ready(val result: SamplingPreviewResult) : SamplingPreviewState
    data class Failed(val errorType: String) : SamplingPreviewState
}

data class SamplingPreviewResult(
    val sequence: Long,
    val intervalMs: Long,
    val processCount: Int,
    val catalogCount: Int,
    val applicationCount: Int,
    val cpuValueCount: Int,
    val rssValueCount: Int,
    val pssValueCount: Int,
    val systemCpuPercentBasisPoints: Int?,
    val memoryTotalKb: Long?,
    val memoryAvailableKb: Long?,
    val collectionDurationMs: Long,
    val catalogRevision: Long,
    val source: MetricDataSource,
    val frameFlags: Int,
)
