package io.github.PctAIGM.procview.data

import android.os.Build
import androidx.room.withTransaction
import io.github.PctAIGM.procview.BuildConfig
import io.github.PctAIGM.procview.data.db.CapabilityReportEntity
import io.github.PctAIGM.procview.data.db.ProcessIdentityEntity
import io.github.PctAIGM.procview.data.db.ProcessPackageCandidateEntity
import io.github.PctAIGM.procview.data.db.ProcessSampleEntity
import io.github.PctAIGM.procview.data.db.ProcViewDatabase
import io.github.PctAIGM.procview.data.db.SessionEntity
import io.github.PctAIGM.procview.data.db.SessionEventEntity
import io.github.PctAIGM.procview.data.db.SystemSampleEntity
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.ProcessMetric
import io.github.PctAIGM.procview.monitor.HistoryEventRecord
import io.github.PctAIGM.procview.monitor.HistoryFrameRecord
import io.github.PctAIGM.procview.monitor.HistorySessionStart
import io.github.PctAIGM.procview.monitor.HistoryStateRecord
import io.github.PctAIGM.procview.monitor.MonitorPhase
import io.github.PctAIGM.procview.monitor.MonitorRuntimeEventType
import io.github.PctAIGM.procview.monitor.MonitorSessionRecorder
import io.github.PctAIGM.procview.monitor.MonitorStorageFailure
import io.github.PctAIGM.procview.monitor.UserStopDuringStartCancellation
import io.github.PctAIGM.procview.sampler.ProcessPackageResolution
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomMonitorSessionRecorder(
    private val database: ProcViewDatabase,
    private val maxBatchAgeMs: Long = MAX_BATCH_AGE_MS,
) : MonitorSessionRecorder, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<RecorderCommand>(capacity = COMMAND_CAPACITY)
    private val summaryRequests = Channel<String>(capacity = Channel.UNLIMITED)
    private val mutableFailures = MutableSharedFlow<MonitorStorageFailure>(extraBufferCapacity = 8)
    private val failed = AtomicBoolean(false)
    private val json = Json { encodeDefaults = true }
    private val pendingFrames = mutableListOf<HistoryFrameRecord>()
    private val pendingEvents = mutableListOf<HistoryEventRecord>()
    private val identityIds = mutableMapOf<ProcessKey, Long>()
    private val identityCandidateStates = mutableMapOf<ProcessKey, PackageCandidateState>()
    private var activeSessionId: String? = null
    private var durableTerminalSessionId: String? = null
    private var summaryRequestedTerminalSessionId: String? = null
    private var flushTimerJob: Job? = null

    override val failures: Flow<MonitorStorageFailure> = mutableFailures.asSharedFlow()

    init {
        require(maxBatchAgeMs > 0L) { "maxBatchAgeMs must be positive" }
        scope.launch { processCommands() }
        scope.launch { processSummaryRequests() }
    }

    override fun close() {
        flushTimerJob?.cancel()
        flushTimerJob = null
        commands.close()
        summaryRequests.close()
        scope.cancel()
    }

    override suspend fun startSession(start: HistorySessionStart): Boolean {
        val acknowledgement = CompletableDeferred<Boolean>()
        val acceptance = CompletableDeferred<StartAcceptance>()
        return try {
            commands.send(RecorderCommand.Start(start, acknowledgement, acceptance))
            val accepted = acknowledgement.await()
            acceptance.complete(StartAcceptance.OWNED)
            accepted
        } catch (cancellation: CancellationException) {
            // The recorder owns a separate application-lifetime scope. Tell its actor that
            // the service-side caller disappeared so a queued/committing START cannot become
            // an orphaned open session after the controller coroutine is gone.
            acceptance.complete(
                if (cancellation is UserStopDuringStartCancellation) {
                    StartAcceptance.USER_STOPPED
                } else {
                    StartAcceptance.CALLER_CANCELLED
                },
            )
            acknowledgement.cancel(cancellation)
            throw cancellation
        } catch (_: Exception) {
            acceptance.complete(StartAcceptance.CALLER_CANCELLED)
            acknowledgement.cancel()
            signalFailure("start_queue")
            false
        }
    }

    override fun recordFrame(frame: HistoryFrameRecord): Boolean = submit(
        RecorderCommand.Frame(frame),
    )

    override fun recordEvent(event: HistoryEventRecord): Boolean = submit(
        RecorderCommand.Event(event),
    )

    override fun updateState(state: HistoryStateRecord): Boolean {
        if (state.phase == MonitorPhase.COMPLETED || state.phase == MonitorPhase.INTERRUPTED) {
            signalFailure("non_atomic_terminal")
            return false
        }
        return submit(RecorderCommand.State(state))
    }

    override fun updateTerminalState(
        state: HistoryStateRecord,
        event: HistoryEventRecord,
    ): Boolean {
        val expectedEvent = when (state.phase) {
            MonitorPhase.COMPLETED -> MonitorRuntimeEventType.SESSION_COMPLETED
            MonitorPhase.INTERRUPTED -> MonitorRuntimeEventType.SESSION_INTERRUPTED
            else -> null
        }
        if (
            state.sessionId != event.sessionId ||
            event.event.type != expectedEvent
        ) {
            signalFailure("invalid_terminal")
            return false
        }
        return submit(RecorderCommand.Terminal(state, event))
    }

    override suspend fun recover(
        pausedState: HistoryStateRecord?,
        pausedEvent: HistoryEventRecord?,
    ): Boolean {
        val acknowledgement = CompletableDeferred<Boolean>()
        return try {
            commands.send(
                RecorderCommand.Recover(
                    pausedState = pausedState,
                    pausedEvent = pausedEvent,
                    acknowledgement = acknowledgement,
                ),
            )
            acknowledgement.await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun finishSession(state: HistoryStateRecord): Boolean {
        if (state.phase != MonitorPhase.COMPLETED && state.phase != MonitorPhase.INTERRUPTED) {
            return false
        }
        if (failed.get()) return false
        val acknowledgement = CompletableDeferred<Boolean>()
        return try {
            commands.send(RecorderCommand.Finish(state, acknowledgement))
            acknowledgement.await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            signalFailure("finish_queue")
            false
        }
    }

    private fun submit(command: RecorderCommand): Boolean {
        if (failed.get()) return false
        val accepted = commands.trySend(command).isSuccess
        if (!accepted) signalFailure("queue_full")
        return accepted
    }

    private suspend fun processCommands() {
        var dropCommandsUntilRecovery = false
        for (command in commands) {
            val canRetryFailedStart = command is RecorderCommand.Start &&
                command.acknowledgement.isActive &&
                activeSessionId == null
            if (
                dropCommandsUntilRecovery &&
                command !is RecorderCommand.Recover &&
                !canRetryFailedStart
            ) {
                (command as? RecorderCommand.Start)?.acknowledgement?.complete(false)
                (command as? RecorderCommand.Finish)?.acknowledgement?.complete(false)
                continue
            }
            if (canRetryFailedStart) {
                dropCommandsUntilRecovery = false
                failed.set(false)
            }
            try {
                when (command) {
                    is RecorderCommand.Start -> {
                        if (!command.acknowledgement.isActive) {
                            Unit
                        } else if (activeSessionId != null) {
                            command.acknowledgement.complete(false)
                        } else {
                            persistStart(command.value)
                            command.acknowledgement.complete(true)
                            val acceptance = withTimeoutOrNull(
                                START_ACCEPTANCE_TIMEOUT_MS,
                            ) {
                                command.acceptance.await()
                            } ?: StartAcceptance.CALLER_CANCELLED
                            if (acceptance != StartAcceptance.OWNED) {
                                // The independent Room transaction committed after the service
                                // coroutine was cancelled. Preserve an honest terminal record
                                // instead of leaving a STARTING row with no live controller.
                                persistAbandonedStart(command.value, acceptance)
                            }
                        }
                    }
                    is RecorderCommand.Frame -> {
                        val wasEmpty = pendingFrames.isEmpty()
                        pendingFrames += command.value
                        if (shouldFlushFrames()) flushPending()
                        else if (wasEmpty) schedulePendingFlush()
                    }
                    is RecorderCommand.Event -> {
                        pendingEvents += command.value
                        // Events are low frequency and can arrive while sampling is paused or a
                        // Binder call is stalled, so they cannot rely on a future frame to drain.
                        // Any pending frames still share this same transaction.
                        flushPending()
                    }
                    is RecorderCommand.State -> {
                        flushPending()
                        persistState(command.value)
                    }
                    RecorderCommand.Flush -> flushPending()
                    is RecorderCommand.Terminal -> {
                        check(activeSessionId == command.state.sessionId) {
                            "terminal state belongs to an inactive session"
                        }
                        flushPending()
                        persistTerminal(command.state, command.event)
                        durableTerminalSessionId = command.state.sessionId
                        activeSessionId = null
                        clearIdentityCaches()
                        requestSummaryRepair(command.state.sessionId)
                        checkpoint()
                    }
                    is RecorderCommand.Recover -> {
                        recoverDatabase(command)
                        if (!failed.get()) dropCommandsUntilRecovery = false
                    }
                    is RecorderCommand.Finish -> {
                        val active = activeSessionId
                        if (active != null) {
                            command.acknowledgement.complete(false)
                        } else {
                            val terminalOwned = durableTerminalSessionId == command.value.sessionId
                            if (terminalOwned) {
                                // The preceding terminal command committed in this recorder. Do not
                                // make its idempotent Finish depend on another database read: a
                                // transient read failure cannot invalidate an already durable
                                // terminal transition.
                                command.acknowledgement.complete(true)
                                durableTerminalSessionId = null
                                checkpoint()
                            } else {
                                val stored = database.sessionDao().session(command.value.sessionId)
                                val terminalStored = stored?.status == MonitorPhase.COMPLETED.name ||
                                    stored?.status == MonitorPhase.INTERRUPTED.name
                                if (!terminalStored) {
                                    command.acknowledgement.complete(false)
                                } else {
                                    // Duplicate Finish after recorder ownership was released is
                                    // idempotent only when Room still confirms a terminal session.
                                    command.acknowledgement.complete(true)
                                    requestSummaryRepair(command.value.sessionId)
                                    checkpoint()
                                }
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A transaction can roll back identities that were already cached in memory.
                // Keep the accepted frame/event batch for recovery, but re-resolve identities
                // from Room instead of reusing row ids created by the rolled-back transaction.
                clearIdentityCaches()
                dropCommandsUntilRecovery = true
                failed.set(true)
                (command as? RecorderCommand.Start)?.acknowledgement?.complete(false)
                (command as? RecorderCommand.Finish)?.acknowledgement?.complete(false)
                (command as? RecorderCommand.Recover)?.acknowledgement?.complete(false)
                signalFailure("database_write")
            }
        }
    }

    private suspend fun persistStart(start: HistorySessionStart) {
        flushPending()
        clearIdentityCaches()
        database.withTransaction {
            val report = start.capabilityReport
            val capabilityId = database.sessionDao().insertCapabilityReport(
                CapabilityReportEntity(
                    probedAtWallTimeMs = report.probedAtWallTimeMs,
                    quality = report.quality.name,
                    metricCoverage = report.metricCoverage,
                    backendMode = report.backendMode.name,
                    reportJson = json.encodeToString(report),
                ),
            )
            database.sessionDao().insertSession(
                SessionEntity(
                    id = start.sessionId,
                    name = start.name,
                    status = MonitorPhase.STARTING.name,
                    startWallTimeMs = start.startedWallTimeMillis,
                    startElapsedRealtimeNanos = start.startedElapsedRealtimeNanos,
                    bootId = start.bootId,
                    samplingProfile = start.preset.name,
                    deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
                        .filter(String::isNotBlank)
                        .joinToString(" "),
                    androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    romDisplay = Build.DISPLAY.orEmpty(),
                    procViewVersion = BuildConfig.VERSION_NAME,
                    shizukuVersion = "API ${report.shizukuApiVersion}",
                    backendMode = report.backendMode.name,
                    capabilityReportId = capabilityId,
                    lastHeartbeatWallTimeMs = start.startedWallTimeMillis,
                ),
            )
        }
        activeSessionId = start.sessionId
        durableTerminalSessionId = null
        summaryRequestedTerminalSessionId = null
    }

    private suspend fun persistAbandonedStart(
        start: HistorySessionStart,
        acceptance: StartAcceptance,
    ) {
        val interruptedAt = System.currentTimeMillis()
        val stoppedByUser = acceptance == StartAcceptance.USER_STOPPED
        val terminalPhase = if (stoppedByUser) {
            MonitorPhase.COMPLETED
        } else {
            MonitorPhase.INTERRUPTED
        }
        val terminalEvent = if (stoppedByUser) {
            MonitorRuntimeEventType.SESSION_COMPLETED
        } else {
            MonitorRuntimeEventType.SESSION_INTERRUPTED
        }
        val terminalReason = if (stoppedByUser) {
            "user_stop_during_start"
        } else {
            "start_caller_cancelled"
        }
        database.withTransaction {
            database.sampleDao().insertEvents(
                listOf(
                    SessionEventEntity(
                        sessionId = start.sessionId,
                        elapsedOffsetMs = 0L,
                        wallTimeMs = interruptedAt,
                        type = terminalEvent.name,
                        payloadJson = "{\"reason\":\"$terminalReason\"}",
                    ),
                ),
            )
            database.sessionDao().updateSessionState(
                sessionId = start.sessionId,
                status = terminalPhase.name,
                pauseReason = null,
                wallTimeMs = interruptedAt,
                elapsedOffsetMs = 0L,
                terminal = true,
            )
        }
        activeSessionId = null
        durableTerminalSessionId = null
        clearIdentityCaches()
        checkpoint()
    }

    private fun shouldFlushFrames(): Boolean {
        if (pendingFrames.size >= MAX_BATCH_FRAMES) return true
        val first = pendingFrames.firstOrNull()?.timelineFrame?.frame?.elapsedRealtimeNanos
            ?: return false
        val last = pendingFrames.last().timelineFrame.frame.elapsedRealtimeNanos
        return last - first >= MAX_BATCH_DURATION_NANOS
    }

    private suspend fun flushPending() {
        if (pendingFrames.isEmpty() && pendingEvents.isEmpty()) return
        val frames = pendingFrames.toList()
        val events = pendingEvents.toList()
        database.withTransaction {
            for (frame in frames) persistFrameInTransaction(frame)
            if (events.isNotEmpty()) {
                database.sampleDao().insertEvents(events.map(::eventEntity))
            }
        }
        // Only release an accepted batch after Room has committed it. A transaction failure
        // leaves the snapshots in place so RecorderCommand.Recover can retry them.
        pendingFrames.clear()
        pendingEvents.clear()
        flushTimerJob?.cancel()
        flushTimerJob = null
    }

    private fun schedulePendingFlush() {
        if (flushTimerJob?.isActive == true) return
        flushTimerJob = scope.launch {
            delay(maxBatchAgeMs)
            try {
                commands.send(RecorderCommand.Flush)
            } catch (_: ClosedSendChannelException) {
                // close() intentionally prevents a delayed timer from reviving the actor.
            }
        }
    }

    private suspend fun persistFrameInTransaction(record: HistoryFrameRecord) {
        check(record.sessionId == activeSessionId) { "frame belongs to an inactive session" }
        val live = record.timelineFrame
        val frame = live.frame
        val offsetMs = elapsedOffsetMs(
            frame.elapsedRealtimeNanos,
            record.sessionStartElapsedRealtimeNanos,
        )
        database.sampleDao().insertSystemSamples(
            listOf(
                SystemSampleEntity(
                    sessionId = record.sessionId,
                    sequence = frame.sequence,
                    elapsedOffsetMs = offsetMs,
                    wallTimeMs = frame.wallTimeMillis,
                    cpuPercentBasisPoints = frame.systemCpuPercentBasisPoints,
                    memoryTotalKb = frame.memoryTotalKb,
                    memoryAvailableKb = frame.memoryAvailableKb,
                    batteryLevelPercent = live.environment.batteryLevelPercent,
                    batteryTemperatureDeciC = live.environment.batteryTemperatureDeciC,
                    chargingState = live.environment.chargingState.name,
                    thermalStatus = live.environment.thermalStatus,
                    screenInteractive = live.environment.screenInteractive,
                    samplingIntervalMs = record.samplingIntervalMs,
                    collectionDurationMs = frame.collectionDurationMs,
                    dataSource = frame.source.name,
                    frameFlags = frame.frameFlags,
                ),
            ),
        )
        val catalogByKey = live.catalog.associateBy(ProcessCatalogEntry::key)
        val resolutionByKey = live.packageResolutions.associateBy(ProcessPackageResolution::key)
        val metricByKey = frame.metrics.associateBy(ProcessMetric::key)
        val rankByKey = frame.metrics.asSequence()
            .filter { it.cpuPercentBasisPoints != null }
            .sortedWith(
                compareByDescending<ProcessMetric> { it.cpuPercentBasisPoints ?: -1 }
                    .thenByDescending { it.rssKb ?: -1L }
                    .thenBy { it.key.pid }
                    .thenBy { it.key.startTimeTicks },
            )
            .mapIndexed { index, metric -> metric.key to index + 1 }
            .toMap()
        val processSamples = record.retentionReasons.entries
            .sortedWith(compareBy({ it.key.pid }, { it.key.startTimeTicks }))
            .mapNotNull { (key, reasons) ->
                val metric = metricByKey[key] ?: return@mapNotNull null
                val catalogEntry = catalogByKey[key] ?: return@mapNotNull null
                val resolution = resolutionByKey[key]
                val identityId = identityId(
                    sessionId = record.sessionId,
                    offsetMs = offsetMs,
                    entry = catalogEntry,
                    resolution = resolution,
                )
                ProcessSampleEntity(
                    sessionId = record.sessionId,
                    systemSampleSequence = frame.sequence,
                    processIdentityId = identityId,
                    cpuPercentBasisPoints = metric.cpuPercentBasisPoints,
                    rssKb = metric.rssKb,
                    pssKb = metric.pssKb,
                    pssSampleElapsedRealtimeNanos = metric.pssSampleElapsedRealtimeNanos,
                    processState = metric.state.toString(),
                    rank = rankByKey[key],
                    reasonKept = reasons,
                )
            }
        if (processSamples.isNotEmpty()) database.sampleDao().insertProcessSamples(processSamples)
        database.sessionDao().updateHeartbeatAndSummary(
            sessionId = record.sessionId,
            wallTimeMs = frame.wallTimeMillis,
            elapsedOffsetMs = offsetMs,
            sequence = frame.sequence,
            cpuBasisPoints = frame.systemCpuPercentBasisPoints,
            memoryAvailableKb = frame.memoryAvailableKb,
            batteryTemperatureDeciC = live.environment.batteryTemperatureDeciC,
            thermalStatus = live.environment.thermalStatus,
        )
    }

    private suspend fun identityId(
        sessionId: String,
        offsetMs: Long,
        entry: ProcessCatalogEntry,
        resolution: ProcessPackageResolution?,
    ): Long {
        val existing = identityIds[entry.key]
        val displayName = resolution?.displayName
            ?: resolution?.primaryPackage
            ?: entry.processName
        val identityId = existing ?: run {
            val inserted = database.sampleDao().insertIdentity(
                ProcessIdentityEntity(
                    sessionId = sessionId,
                    pid = entry.key.pid,
                    startTimeTicks = entry.key.startTimeTicks,
                    parentPid = entry.parentPid,
                    uid = entry.uid,
                    packageName = resolution?.primaryPackage,
                    processName = entry.processName,
                    displayName = displayName,
                    commandLine = entry.commandLine,
                    isSystem = resolution?.isSystem == true,
                    isNative = resolution?.isNative == true,
                    firstSeenOffsetMs = offsetMs,
                    lastSeenOffsetMs = offsetMs,
                ),
            )
            val resolved = if (inserted >= 0L) inserted else {
                database.sampleDao().identityId(
                    sessionId,
                    entry.key.pid,
                    entry.key.startTimeTicks,
                ) ?: error("identity insert did not produce an id")
            }
            identityIds[entry.key] = resolved
            resolved
        }
        database.sampleDao().updateIdentity(
            identityId = identityId,
            lastSeenOffsetMs = offsetMs,
            parentPid = entry.parentPid,
            uid = entry.uid,
            packageName = resolution?.primaryPackage,
            packageCandidatesAvailable = resolution?.packageCandidates?.isNotEmpty() == true,
            processName = entry.processName,
            displayName = displayName,
            commandLine = entry.commandLine,
            isSystem = resolution?.isSystem == true,
            isNative = resolution?.isNative == true,
        )
        val candidateState = resolution?.packageCandidates
            ?.takeIf { it.isNotEmpty() }
            ?.let { packages ->
                PackageCandidateState(
                    packages = packages,
                    primaryPackage = resolution.primaryPackage,
                )
            }
        if (candidateState != null && identityCandidateStates[entry.key] != candidateState) {
            val candidates = candidateState.packages.map { packageName ->
                ProcessPackageCandidateEntity(
                    processIdentityId = identityId,
                    packageName = packageName,
                    isPrimary = packageName == candidateState.primaryPackage,
                )
            }
            database.sampleDao().clearPackageCandidatePrimaries(identityId)
            database.sampleDao().insertPackageCandidates(candidates)
            identityCandidateStates[entry.key] = candidateState
        }
        return identityId
    }

    private suspend fun persistState(state: HistoryStateRecord) {
        database.withTransaction {
            database.sessionDao().updateSessionState(
                sessionId = state.sessionId,
                status = state.phase.name,
                pauseReason = state.pauseReason?.name,
                wallTimeMs = state.wallTimeMillis,
                elapsedOffsetMs = state.elapsedOffsetMs,
                terminal = state.phase == MonitorPhase.COMPLETED ||
                    state.phase == MonitorPhase.INTERRUPTED,
            )
        }
    }

    private suspend fun persistTerminal(
        state: HistoryStateRecord,
        event: HistoryEventRecord,
    ) {
        database.withTransaction {
            database.sampleDao().insertEvents(listOf(eventEntity(event)))
            database.sessionDao().updateSessionState(
                sessionId = state.sessionId,
                status = state.phase.name,
                pauseReason = state.pauseReason?.name,
                wallTimeMs = state.wallTimeMillis,
                elapsedOffsetMs = state.elapsedOffsetMs,
                terminal = true,
            )
        }
    }

    private suspend fun rebuildSummaries(sessionId: String) {
        database.withTransaction {
            database.sampleDao().deleteSummaries(sessionId)
            database.sampleDao().rebuildSummaries(sessionId)
        }
    }

    private fun requestSummaryRepair(sessionId: String) {
        if (summaryRequestedTerminalSessionId == sessionId) return
        summaryRequestedTerminalSessionId = sessionId
        // This unbounded channel receives at most one request per completed session. A closed
        // process will run the same repair from ProcViewApplication on the next startup.
        summaryRequests.trySend(sessionId)
    }

    private suspend fun processSummaryRequests() {
        for (sessionId in summaryRequests) {
            try {
                rebuildSummaries(sessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Terminal state and samples are already durable. Startup repair owns retries.
            }
        }
    }

    private suspend fun recoverDatabase(command: RecorderCommand.Recover) {
        val sessionId = activeSessionId
        if (sessionId == null) {
            command.acknowledgement.complete(false)
            return
        }
        // Queue saturation can reject a later frame while an earlier accepted batch is
        // still buffered. Persist that batch before the durable STORAGE pause so the
        // session heartbeat never moves backwards after recovery.
        flushPending()
        val session = database.sessionDao().session(sessionId)
        if (session == null) {
            command.acknowledgement.complete(false)
            return
        }
        database.withTransaction {
            val pausedState = command.pausedState
            database.sessionDao().updateSessionState(
                sessionId = session.id,
                status = pausedState?.phase?.name ?: session.status,
                pauseReason = pausedState?.pauseReason?.name ?: session.pauseReason,
                wallTimeMs = pausedState?.wallTimeMillis ?: System.currentTimeMillis(),
                elapsedOffsetMs = pausedState?.elapsedOffsetMs ?: session.elapsedDurationMs,
                terminal = false,
            )
            command.pausedEvent?.let { event ->
                database.sampleDao().insertEvents(listOf(eventEntity(event)))
            }
        }
        clearIdentityCaches()
        failed.set(false)
        command.acknowledgement.complete(true)
    }

    private fun clearIdentityCaches() {
        identityIds.clear()
        identityCandidateStates.clear()
    }

    private fun eventEntity(record: HistoryEventRecord): SessionEventEntity = SessionEventEntity(
        sessionId = record.sessionId,
        elapsedOffsetMs = elapsedOffsetMs(
            record.event.elapsedRealtimeNanos,
            record.sessionStartElapsedRealtimeNanos,
        ),
        wallTimeMs = record.event.wallTimeMillis,
        type = record.event.type.name,
        payloadJson = record.payloadJson,
    )

    private fun checkpoint() {
        runCatching {
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(PASSIVE)").close()
        }
    }

    private fun signalFailure(category: String) {
        failed.set(true)
        mutableFailures.tryEmit(MonitorStorageFailure(category))
    }

    private fun elapsedOffsetMs(value: Long, start: Long): Long =
        if (value <= start) 0L else (value - start) / NANOS_PER_MILLISECOND

    private sealed interface RecorderCommand {
        data class Start(
            val value: HistorySessionStart,
            val acknowledgement: CompletableDeferred<Boolean>,
            val acceptance: CompletableDeferred<StartAcceptance>,
        ) : RecorderCommand
        data class Frame(val value: HistoryFrameRecord) : RecorderCommand
        data class Event(val value: HistoryEventRecord) : RecorderCommand
        data class State(val value: HistoryStateRecord) : RecorderCommand
        data object Flush : RecorderCommand
        data class Terminal(
            val state: HistoryStateRecord,
            val event: HistoryEventRecord,
        ) : RecorderCommand
        data class Recover(
            val pausedState: HistoryStateRecord?,
            val pausedEvent: HistoryEventRecord?,
            val acknowledgement: CompletableDeferred<Boolean>,
        ) : RecorderCommand
        data class Finish(
            val value: HistoryStateRecord,
            val acknowledgement: CompletableDeferred<Boolean>,
        ) : RecorderCommand
    }

    private data class PackageCandidateState(
        val packages: List<String>,
        val primaryPackage: String?,
    )

    private enum class StartAcceptance {
        OWNED,
        USER_STOPPED,
        CALLER_CANCELLED,
    }

    private companion object {
        const val COMMAND_CAPACITY = 64
        const val MAX_BATCH_FRAMES = 32
        const val MAX_BATCH_DURATION_NANOS = 5_000_000_000L
        const val MAX_BATCH_AGE_MS = 5_000L
        const val START_ACCEPTANCE_TIMEOUT_MS = 5_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
