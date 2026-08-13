package io.github.PctAIGM.procview.monitor

import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.ProcessKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Distinguishes an explicit notification/UI stop from an unexpected owner cancellation. */
internal class UserStopDuringStartCancellation :
    CancellationException("monitor start stopped by user")

data class HistorySessionStart(
    val sessionId: String,
    val name: String,
    val startedWallTimeMillis: Long,
    val startedElapsedRealtimeNanos: Long,
    val bootId: String,
    val preset: SamplingPreset,
    val capabilityReport: CapabilityReport,
)

data class HistoryFrameRecord(
    val sessionId: String,
    val sessionStartElapsedRealtimeNanos: Long,
    val timelineFrame: LiveTimelineFrame,
    val samplingIntervalMs: Long,
    val retentionReasons: Map<ProcessKey, Int>,
)

data class HistoryEventRecord(
    val sessionId: String,
    val sessionStartElapsedRealtimeNanos: Long,
    val event: MonitorRuntimeEvent,
    val payloadJson: String? = null,
)

data class HistoryStateRecord(
    val sessionId: String,
    val phase: MonitorPhase,
    val pauseReason: PauseReason?,
    val wallTimeMillis: Long,
    val elapsedOffsetMs: Long,
)

data class MonitorStorageFailure(
    val category: String,
)

interface MonitorSessionRecorder {
    val failures: Flow<MonitorStorageFailure>

    suspend fun startSession(start: HistorySessionStart): Boolean

    fun recordFrame(frame: HistoryFrameRecord): Boolean

    fun recordEvent(event: HistoryEventRecord): Boolean

    fun updateState(state: HistoryStateRecord): Boolean

    /** Persists the terminal event and terminal session state as one logical commit. */
    fun updateTerminalState(
        state: HistoryStateRecord,
        event: HistoryEventRecord,
    ): Boolean

    suspend fun recover(
        pausedState: HistoryStateRecord?,
        pausedEvent: HistoryEventRecord?,
    ): Boolean

    suspend fun finishSession(state: HistoryStateRecord): Boolean
}

object NoOpMonitorSessionRecorder : MonitorSessionRecorder {
    override val failures: Flow<MonitorStorageFailure> = emptyFlow()

    override suspend fun startSession(start: HistorySessionStart): Boolean = true

    override fun recordFrame(frame: HistoryFrameRecord): Boolean = true

    override fun recordEvent(event: HistoryEventRecord): Boolean = true

    override fun updateState(state: HistoryStateRecord): Boolean = true

    override fun updateTerminalState(
        state: HistoryStateRecord,
        event: HistoryEventRecord,
    ): Boolean = true

    override suspend fun recover(
        pausedState: HistoryStateRecord?,
        pausedEvent: HistoryEventRecord?,
    ): Boolean = true

    override suspend fun finishSession(state: HistoryStateRecord): Boolean = true
}
