package io.github.PctAIGM.procview.monitor

import io.github.PctAIGM.procview.model.MetricFrame
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.ProcessMetric
import io.github.PctAIGM.procview.sampler.ApplicationAggregate
import io.github.PctAIGM.procview.sampler.ProcessPackageResolution
import java.util.LinkedHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MonitorFailure {
    NONE,
    BACKEND_UNAVAILABLE,
    BACKEND_PROTOCOL,
    FOREGROUND_SERVICE,
    STORAGE,
}

enum class MonitorRuntimeEventType {
    SESSION_STARTED,
    FIRST_FRAME,
    USER_PAUSED,
    USER_RESUMED,
    DATA_GAP_START,
    DATA_GAP_END,
    STORAGE_PAUSED,
    STORAGE_RESUMED,
    SCREEN_CHANGED,
    BATTERY_CHANGED,
    THERMAL_CHANGED,
    INTERVAL_CHANGED,
    DATA_SOURCE_CHANGED,
    SESSION_COMPLETED,
    SESSION_INTERRUPTED,
}

data class MonitorRuntimeEvent(
    val sequence: Long,
    val type: MonitorRuntimeEventType,
    val wallTimeMillis: Long,
    val elapsedRealtimeNanos: Long,
)

data class LiveTimelineFrame(
    val frame: MetricFrame,
    val environment: MonitorEnvironment,
    val applications: List<ApplicationAggregate>,
    val catalog: List<ProcessCatalogEntry>,
    val packageResolutions: List<ProcessPackageResolution>,
)

data class MetricPeaks(
    val cpuPercentBasisPoints: Int? = null,
    val rssKb: Long? = null,
    val pssKb: Long? = null,
) {
    fun merge(cpu: Int?, rss: Long?, pss: Long?): MetricPeaks = MetricPeaks(
        cpuPercentBasisPoints = maxNullable(cpuPercentBasisPoints, cpu),
        rssKb = maxNullable(rssKb, rss),
        pssKb = maxNullable(pssKb, pss),
    )

    private fun <T : Comparable<T>> maxNullable(first: T?, second: T?): T? = when {
        first == null -> second
        second == null -> first
        first >= second -> first
        else -> second
    }
}

internal fun mergeProcessPeaks(
    previous: Map<ProcessKey, MetricPeaks>,
    metrics: List<ProcessMetric>,
): Map<ProcessKey, MetricPeaks> = previous.toMutableMap().apply {
    metrics.forEach { metric ->
        this[metric.key] = this[metric.key].orEmpty().merge(
            metric.cpuPercentBasisPoints,
            metric.rssKb,
            metric.pssKb,
        )
    }
}

internal fun retainProcessPeaks(
    peaks: Map<ProcessKey, MetricPeaks>,
    retainedKeys: Set<ProcessKey>,
): Map<ProcessKey, MetricPeaks> = when {
    peaks.isEmpty() || retainedKeys.isEmpty() -> emptyMap()
    peaks.keys.all(retainedKeys::contains) -> peaks
    else -> peaks.filterKeys(retainedKeys::contains)
}

internal fun mergeApplicationPeaks(
    previous: Map<String, MetricPeaks>,
    applications: List<ApplicationAggregate>,
): Map<String, MetricPeaks> = LinkedHashMap<String, MetricPeaks>(
    maxOf(16, previous.size + applications.size),
    0.75f,
    true,
).apply {
    putAll(previous)
    applications.forEach { application ->
        this[application.stableId] = this[application.stableId].orEmpty().merge(
            application.cpuPercentBasisPoints.takeIf { application.cpuComplete },
            application.rssKb.takeIf { application.rssComplete },
            application.pssKb.takeIf { application.pssComplete },
        )
    }
}

internal fun retainApplicationPeaks(
    peaks: Map<String, MetricPeaks>,
    retainedIds: Set<String>,
    maximumEntries: Int = MAX_APPLICATION_PEAK_ENTRIES,
): Map<String, MetricPeaks> {
    require(maximumEntries > 0) { "maximumEntries must be positive" }
    if (peaks.isEmpty()) return emptyMap()
    val protectedIds = retainedIds.filterTo(linkedSetOf(), peaks::containsKey)
    val capacity = maxOf(maximumEntries, protectedIds.size)
    if (peaks.size <= capacity) return peaks

    val keep = protectedIds.toMutableSet()
    peaks.keys.toList().asReversed().forEach { id ->
        if (keep.size < capacity) keep += id
    }
    return LinkedHashMap<String, MetricPeaks>(capacity).apply {
        peaks.forEach { (id, value) -> if (id in keep) put(id, value) }
    }
}

private const val MAX_APPLICATION_PEAK_ENTRIES = 4_096

private fun MetricPeaks?.orEmpty(): MetricPeaks = this ?: MetricPeaks()

data class MonitorRuntimeSnapshot(
    val machineState: SessionMachineState = SessionMachineState.initial(),
    val sessionId: String? = null,
    val sessionName: String? = null,
    val sessionBootId: String? = null,
    val startedWallTimeMillis: Long? = null,
    val startedElapsedRealtimeNanos: Long? = null,
    val preset: SamplingPreset = SamplingPreset.BALANCED,
    val environment: MonitorEnvironment = MonitorEnvironment(
        appForeground = true,
        screenInteractive = true,
    ),
    val effectiveIntervalMs: Long = SamplingPreset.BALANCED.foregroundIntervalMs,
    val wakeLockHeld: Boolean = false,
    val frameCount: Long = 0,
    val catalogEntryCount: Int = 0,
    val lastFrame: MetricFrame? = null,
    val applications: List<ApplicationAggregate> = emptyList(),
    val catalog: List<ProcessCatalogEntry> = emptyList(),
    val packageResolutions: List<ProcessPackageResolution> = emptyList(),
    val recentFrames: List<LiveTimelineFrame> = emptyList(),
    val processPeaks: Map<ProcessKey, MetricPeaks> = emptyMap(),
    val applicationPeaks: Map<String, MetricPeaks> = emptyMap(),
    val detailProcessKeys: Set<ProcessKey> = emptySet(),
    val failure: MonitorFailure = MonitorFailure.NONE,
    val eventCount: Long = 0,
    val recentEvents: List<MonitorRuntimeEvent> = emptyList(),
)

class MonitorRuntimeStore {
    private val mutableState = MutableStateFlow(MonitorRuntimeSnapshot())
    val state = mutableState.asStateFlow()

    fun update(transform: (MonitorRuntimeSnapshot) -> MonitorRuntimeSnapshot) {
        mutableState.update(transform)
    }

    @Synchronized
    fun appendEvent(
        type: MonitorRuntimeEventType,
        wallTimeMillis: Long,
        elapsedRealtimeNanos: Long,
    ): MonitorRuntimeEvent {
        lateinit var appended: MonitorRuntimeEvent
        mutableState.update { previous ->
            val nextSequence = if (previous.eventCount == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                previous.eventCount + 1L
            }
            appended = MonitorRuntimeEvent(
                sequence = nextSequence,
                type = type,
                wallTimeMillis = wallTimeMillis,
                elapsedRealtimeNanos = elapsedRealtimeNanos,
            )
            previous.copy(
                eventCount = nextSequence,
                recentEvents = (previous.recentEvents + appended).takeLast(MAX_RECENT_EVENTS),
            )
        }
        return appended
    }

    fun setDetailProcessKeys(keys: Set<ProcessKey>) {
        mutableState.update { previous ->
            if (previous.detailProcessKeys == keys) previous
            else previous.copy(detailProcessKeys = keys)
        }
    }

    private companion object {
        const val MAX_RECENT_EVENTS = 256
    }
}
