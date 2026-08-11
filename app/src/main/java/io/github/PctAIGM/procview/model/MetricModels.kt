package io.github.PctAIGM.procview.model

import kotlinx.serialization.Serializable

@Serializable
data class ProcessKey(
    val pid: Int,
    val startTimeTicks: Long,
) {
    init {
        require(pid > 0) { "PID must be positive" }
        require(startTimeTicks >= 0) { "startTimeTicks must not be negative" }
    }
}

data class RawProcessMetric(
    val key: ProcessKey,
    val cpuTicks: Long,
    val rssKb: Long?,
    val state: Char,
)

data class ProcessCatalogEntry(
    val key: ProcessKey,
    val parentPid: Int,
    val uid: Int?,
    val processName: String,
    val commandLine: String,
)

enum class MetricDataSource {
    PROCFS,
    PS_FALLBACK,
}

data class RawMetricFrame(
    val sequence: Long,
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
    val systemTotalCpuTicks: Long?,
    val systemIdleCpuTicks: Long?,
    val memoryTotalKb: Long?,
    val memoryAvailableKb: Long?,
    val collectionDurationMs: Long,
    val catalogRevision: Long,
    val source: MetricDataSource = MetricDataSource.PROCFS,
    val frameFlags: Int,
    val metrics: List<RawProcessMetric>,
)

data class ProcessMetric(
    val key: ProcessKey,
    val cpuPercentBasisPoints: Int?,
    val rssKb: Long?,
    val pssKb: Long?,
    val pssSampleElapsedRealtimeNanos: Long?,
    val state: Char,
)

data class MetricFrame(
    val sequence: Long,
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
    val systemCpuPercentBasisPoints: Int?,
    val memoryTotalKb: Long?,
    val memoryAvailableKb: Long?,
    val collectionDurationMs: Long,
    val catalogRevision: Long,
    val source: MetricDataSource = MetricDataSource.PROCFS,
    val frameFlags: Int,
    val metrics: List<ProcessMetric>,
)

object MetricFrameFlags {
    const val NONE = 0
    const val PROCESS_LIST_TRUNCATED = 1 shl 0
    const val SYSTEM_CPU_UNREADABLE = 1 shl 1
    const val SYSTEM_MEMORY_UNREADABLE = 1 shl 2
    const val PROCESS_DISAPPEARED_DURING_READ = 1 shl 3
    const val CPU_COUNTER_RESET = 1 shl 4
    const val PROCESS_COUNTER_RESET = 1 shl 5
    const val SAMPLER_SKIPPED_TICK = 1 shl 6
}
