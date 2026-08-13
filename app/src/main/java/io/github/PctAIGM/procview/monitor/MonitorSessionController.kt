package io.github.PctAIGM.procview.monitor

import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.CapabilityQuality
import io.github.PctAIGM.procview.model.MetricDataSource
import io.github.PctAIGM.procview.model.MetricFrame
import io.github.PctAIGM.procview.model.PinnedTarget
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.sampler.ApplicationAggregator
import io.github.PctAIGM.procview.sampler.CatalogSnapshot
import io.github.PctAIGM.procview.sampler.MetricFrameAssembler
import io.github.PctAIGM.procview.sampler.PinnedTargetMatcher
import io.github.PctAIGM.procview.sampler.PrivilegedMonitorBackend
import io.github.PctAIGM.procview.sampler.ProcessPackageResolver
import io.github.PctAIGM.procview.sampler.PssCadence
import io.github.PctAIGM.procview.sampler.RetentionPolicy
import io.github.PctAIGM.procview.sampler.SamplingConfig
import io.github.PctAIGM.procview.shizuku.BackendProtocolException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MonitorSessionController(
    private val backend: PrivilegedMonitorBackend,
    private val packageResolver: ProcessPackageResolver,
    private val store: MonitorRuntimeStore,
    private val scope: CoroutineScope,
    private val onBackendFailure: () -> Unit,
    private val allowPartialCapability: Boolean = false,
    private val pinnedTargets: () -> Set<PinnedTarget> = { emptySet() },
    private val recorder: MonitorSessionRecorder = NoOpMonitorSessionRecorder,
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeNanos: () -> Long = android.os.SystemClock::elapsedRealtimeNanos,
) {
    private var machine = store.state.value.machineState
    private var preset = store.state.value.preset
    private var environment = store.state.value.environment
    private var sessionBootId: String? = null
    private var lastCapabilityReport: CapabilityReport? = null
    private var initializingSession = false
    private var deferredBackendSignal: DeferredBackendSignal? = null
    private var shizukuGapOpen = false
    private var pendingStoragePauseEvent: MonitorRuntimeEvent? = null
    private var pendingTerminalEvent: MonitorRuntimeEvent? = null
    private var stateBeforeStorageFailure: SessionMachineState? = null
    private var lastDataSource: MetricDataSource? = null
    private var sampleJob: Job? = null
    private var pssJob: Job? = null
    private var assembler = MetricFrameAssembler()
    private var pssCadence = PssCadence(preset.pssIntervalMs)
    private var catalog = CatalogSnapshot(revision = 0L, entries = emptyList())
    private var catalogResolutions = emptyList<io.github.PctAIGM.procview.sampler.ProcessPackageResolution>()
    private var nextPackageResolutionRefreshNanos = 0L
    private var nextSessionFrameSequence = 1L
    private val samplingMutex = Mutex()

    suspend fun startSession(
        requestedName: String,
        requestedPreset: SamplingPreset,
    ): Boolean {
        if (machine.hasActiveSession || initializingSession) return false
        deferredBackendSignal = null
        initializingSession = true
        val started = try {
            startSessionWhileInitializing(requestedName, requestedPreset)
        } finally {
            initializingSession = false
        }
        applyDeferredBackendSignal()
        if (started && machine.samplesBackend && sampleJob == null) {
            restartSampling(resetBaseline = true, cancelPss = true)
        }
        return started
    }

    private suspend fun startSessionWhileInitializing(
        requestedName: String,
        requestedPreset: SamplingPreset,
    ): Boolean {
        if (machine.phase == MonitorPhase.COMPLETED || machine.phase == MonitorPhase.INTERRUPTED) {
            applyEvent(SessionEvent.Reset)
        }
        // A READY value can survive in the application-scoped runtime store after the
        // previous service instance has unbound its UserService. A new controller has no
        // capability report in that case and must perform a real bind/probe before it creates
        // a durable session.
        if (machine.phase != MonitorPhase.READY || lastCapabilityReport == null) {
            val preflight = try {
                backend.probe()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                deferredBackendSignal = null
                applyEvent(SessionEvent.BackendUnavailable, MonitorFailure.BACKEND_UNAVAILABLE)
                onBackendFailure()
                return false
            }
            deferredBackendSignal = null
            applyBackendAvailable(preflight)
        }
        if (machine.phase != MonitorPhase.READY) {
            return false
        }

        preset = requestedPreset
        sessionBootId = lastCapabilityReport?.bootId
        assembler = MetricFrameAssembler()
        pssCadence = PssCadence(preset.pssIntervalMs)
        catalog = CatalogSnapshot(revision = 0L, entries = emptyList())
        catalogResolutions = emptyList()
        nextPackageResolutionRefreshNanos = 0L
        nextSessionFrameSequence = 1L
        val startedWall = wallTimeMillis()
        val startedElapsed = elapsedRealtimeNanos()
        val sessionId = UUID.randomUUID().toString()
        val sessionName = requestedName.trim().take(MAX_SESSION_NAME_CHARS)
            .ifBlank { DEFAULT_SESSION_NAME }
        val capabilityReport = lastCapabilityReport
        if (capabilityReport == null || !recorder.startSession(
                HistorySessionStart(
                    sessionId = sessionId,
                    name = sessionName,
                    startedWallTimeMillis = startedWall,
                    startedElapsedRealtimeNanos = startedElapsed,
                    bootId = sessionBootId.orEmpty(),
                    preset = preset,
                    capabilityReport = capabilityReport,
                ),
            )
        ) {
            store.update { previous -> previous.copy(failure = MonitorFailure.STORAGE) }
            return false
        }
        store.update { previous ->
            previous.copy(
                machineState = machine,
                sessionId = sessionId,
                sessionName = sessionName,
                sessionBootId = sessionBootId,
                startedWallTimeMillis = startedWall,
                startedElapsedRealtimeNanos = startedElapsed,
                preset = preset,
                effectiveIntervalMs = effectiveIntervalMs(),
                frameCount = 0,
                catalogEntryCount = 0,
                lastFrame = null,
                applications = emptyList(),
                catalog = emptyList(),
                packageResolutions = emptyList(),
                recentFrames = emptyList(),
                processPeaks = emptyMap(),
                applicationPeaks = emptyMap(),
                detailProcessKeys = emptySet(),
                failure = MonitorFailure.NONE,
                eventCount = 0,
                recentEvents = emptyList(),
            )
        }
        shizukuGapOpen = false
        pendingStoragePauseEvent = null
        pendingTerminalEvent = null
        stateBeforeStorageFailure = null
        lastDataSource = null
        applyEvent(SessionEvent.StartRequested)
        val verifiedBootId = try {
            backend.verifyBootId()
        } catch (cancellation: CancellationException) {
            // The durable START already exists. If the owning service disappears while the
            // final boot check is suspended, close the history session before propagating
            // cancellation so startup recovery is not the only path that can repair it.
            val stoppedByUser = cancellation is UserStopDuringStartCancellation
            if (stoppedByUser) {
                stateBeforeStorageFailure = machine
                applyEvent(SessionEvent.StopRequested)
                val finalized = withContext(NonCancellable) { finishRecording() }
                if (finalized) {
                    pendingTerminalEvent = null
                    stateBeforeStorageFailure = null
                } else {
                    applyEvent(SessionEvent.StorageFailed, MonitorFailure.STORAGE)
                }
            } else {
                applyEvent(SessionEvent.Interrupted)
            }
            throw cancellation
        } catch (_: Exception) {
            deferredBackendSignal = null
            applyBackendUnavailable()
            onBackendFailure()
            return true
        }
        deferredBackendSignal = null
        applyBackendAvailable(capabilityReport.copy(bootId = verifiedBootId))
        return true
    }

    suspend fun pauseByUser() {
        val wasSampling = machine.samplesBackend
        applyEvent(SessionEvent.UserPauseRequested)
        if (wasSampling && !machine.samplesBackend) {
            stopSampling(resetBaseline = true)
        }
    }

    suspend fun resumeByUser() {
        val wasSampling = machine.samplesBackend
        applyEvent(
            SessionEvent.UserResumeRequested(
                waitingForFirstFrame = store.state.value.frameCount == 0L,
            ),
        )
        if (!wasSampling && machine.samplesBackend) {
            restartSampling(resetBaseline = true, cancelPss = true)
        }
    }

    suspend fun stopByUser(): Boolean {
        if (
            machine.pauseReason == PauseReason.STORAGE &&
            pendingTerminalEvent != null
        ) {
            return retryPendingTerminal()
        }
        if (machine.pauseReason == PauseReason.STORAGE && !recoverStorageRecorder()) {
            return false
        }
        val stateToRestoreOnFailure = stateBeforeStorageFailure ?: machine
        stateBeforeStorageFailure = stateToRestoreOnFailure
        applyEvent(SessionEvent.StopRequested)
        stopSampling(resetBaseline = true)
        if (finishRecording()) {
            pendingTerminalEvent = null
            stateBeforeStorageFailure = null
            return true
        }
        applyEvent(SessionEvent.StorageFailed, MonitorFailure.STORAGE)
        return false
    }

    suspend fun storageFailed() {
        val wasSampling = machine.samplesBackend
        enterStoragePause()
        if (wasSampling && !machine.samplesBackend) stopSampling(resetBaseline = true)
    }

    suspend fun storageRecovered() {
        if (
            machine.pauseReason == PauseReason.STORAGE &&
            pendingTerminalEvent != null
        ) {
            retryPendingTerminal()
            return
        }
        if (machine.pauseReason != PauseReason.STORAGE || !recoverStorageRecorder()) return
        val wasSampling = machine.samplesBackend
        val previousState = stateBeforeStorageFailure
        applyEvent(
            SessionEvent.StorageRecovered(
                previousPhase = previousState?.phase ?: MonitorPhase.RUNNING,
                previousPauseReason = previousState?.pauseReason,
            ),
            MonitorFailure.NONE,
        )
        stateBeforeStorageFailure = null
        if (!wasSampling && machine.samplesBackend) {
            restartSampling(resetBaseline = true, cancelPss = true)
        }
    }

    private suspend fun retryPendingTerminal(): Boolean {
        val terminalEvent = when (pendingTerminalEvent?.type) {
            MonitorRuntimeEventType.SESSION_COMPLETED -> SessionEvent.StopRequested
            MonitorRuntimeEventType.SESSION_INTERRUPTED -> SessionEvent.Interrupted
            else -> return false
        }
        if (machine.pauseReason != PauseReason.STORAGE || !recoverStorageRecorder()) return false
        val previousState = stateBeforeStorageFailure
        applyEvent(
            SessionEvent.StorageRecovered(
                previousPhase = previousState?.phase ?: MonitorPhase.RUNNING,
                previousPauseReason = previousState?.pauseReason,
            ),
            MonitorFailure.NONE,
        )
        stateBeforeStorageFailure = machine
        applyEvent(terminalEvent)
        stopSampling(resetBaseline = true)
        if (finishRecording()) {
            pendingTerminalEvent = null
            stateBeforeStorageFailure = null
            return true
        }
        applyEvent(SessionEvent.StorageFailed, MonitorFailure.STORAGE)
        return false
    }

    private suspend fun recoverStorageRecorder(): Boolean {
        val runtime = store.state.value
        val pauseEvent = pendingStoragePauseEvent
        val pausedState = pauseEvent?.let {
            val sessionId = runtime.sessionId
            val started = runtime.startedElapsedRealtimeNanos
            if (sessionId != null && started != null) {
                HistoryStateRecord(
                    sessionId = sessionId,
                    phase = MonitorPhase.PAUSED,
                    pauseReason = PauseReason.STORAGE,
                    wallTimeMillis = it.wallTimeMillis,
                    elapsedOffsetMs = elapsedOffsetMs(
                        value = it.elapsedRealtimeNanos,
                        start = started,
                    ),
                )
            } else {
                null
            }
        }
        val historyPauseEvent = pauseEvent?.let {
            val sessionId = runtime.sessionId
            val started = runtime.startedElapsedRealtimeNanos
            if (sessionId != null && started != null) {
                HistoryEventRecord(
                    sessionId = sessionId,
                    sessionStartElapsedRealtimeNanos = started,
                    event = it,
                )
            } else {
                null
            }
        }
        if (!recorder.recover(pausedState, historyPauseEvent)) {
            store.update { previous -> previous.copy(failure = MonitorFailure.STORAGE) }
            return false
        }
        pendingStoragePauseEvent = null
        return true
    }

    suspend fun backendAvailable(report: CapabilityReport) {
        if (initializingSession) {
            deferredBackendSignal = DeferredBackendSignal.Available(report)
            return
        }
        applyBackendAvailable(report)
    }

    private suspend fun applyBackendAvailable(report: CapabilityReport) {
        if (report.quality != CapabilityQuality.AVAILABLE && !allowPartialCapability) {
            applyBackendUnavailable()
            return
        }
        lastCapabilityReport = report
        val previousState = machine
        val wasSampling = previousState.samplesBackend
        val sameBoot = sessionBootId == null || sessionBootId == report.bootId
        val interruptedByBootChange = previousState.hasActiveSession && !sameBoot
        applyEvent(
            SessionEvent.BackendAvailable(
                sameBoot = sameBoot,
                waitingForFirstFrame = store.state.value.frameCount == 0L,
            ),
        )
        if (sessionBootId == null && machine.hasActiveSession) {
            sessionBootId = report.bootId
            store.update { previous -> previous.copy(sessionBootId = sessionBootId) }
        }
        when {
            interruptedByBootChange -> {
                stopSampling(resetBaseline = true)
                if (machine.phase == MonitorPhase.INTERRUPTED) {
                    if (finishRecording()) {
                        pendingTerminalEvent = null
                        stateBeforeStorageFailure = null
                    } else {
                        stateBeforeStorageFailure = previousState
                        applyEvent(SessionEvent.StorageFailed, MonitorFailure.STORAGE)
                    }
                }
            }
            machine.phase == MonitorPhase.INTERRUPTED -> stopSampling(resetBaseline = true)
            (!wasSampling || sampleJob == null) && machine.samplesBackend && !initializingSession ->
                restartSampling(resetBaseline = true, cancelPss = true)
        }
    }

    suspend fun backendUnavailable() {
        if (initializingSession) {
            deferredBackendSignal = DeferredBackendSignal.Unavailable
            return
        }
        applyBackendUnavailable()
    }

    private suspend fun applyBackendUnavailable() {
        val wasSampling = machine.samplesBackend
        applyEvent(SessionEvent.BackendUnavailable, MonitorFailure.BACKEND_UNAVAILABLE)
        if (wasSampling && !machine.samplesBackend) {
            stopSampling(resetBaseline = true)
        }
    }

    private suspend fun applyDeferredBackendSignal() {
        val signal = deferredBackendSignal ?: return
        deferredBackendSignal = null
        when (signal) {
            is DeferredBackendSignal.Available -> applyBackendAvailable(signal.report)
            DeferredBackendSignal.Unavailable -> applyBackendUnavailable()
        }
    }

    suspend fun updateEnvironment(value: MonitorEnvironment) {
        val previousInterval = effectiveIntervalMs()
        val previousEnvironment = environment
        environment = value
        val currentInterval = effectiveIntervalMs()
        store.update { previous ->
            previous.copy(
                environment = environment,
                effectiveIntervalMs = currentInterval,
            )
        }
        if (sampleJob != null && machine.samplesBackend && previousInterval != currentInterval) {
            appendEvent(
                MonitorRuntimeEventType.INTERVAL_CHANGED,
                buildJsonObject {
                    put("previousIntervalMs", previousInterval)
                    put("currentIntervalMs", currentInterval)
                }.toString(),
            )
            restartSampling(resetBaseline = false, cancelPss = false)
        }
        if (machine.hasActiveSession) {
            if (previousEnvironment.screenInteractive != value.screenInteractive) {
                appendEvent(
                    MonitorRuntimeEventType.SCREEN_CHANGED,
                    buildJsonObject { put("interactive", value.screenInteractive) }.toString(),
                )
            }
            if (
                previousEnvironment.batteryLevelPercent != value.batteryLevelPercent ||
                previousEnvironment.batteryTemperatureDeciC != value.batteryTemperatureDeciC ||
                previousEnvironment.chargingState != value.chargingState
            ) {
                appendEvent(
                    MonitorRuntimeEventType.BATTERY_CHANGED,
                    buildJsonObject {
                        value.batteryLevelPercent?.let { put("levelPercent", it) }
                        value.batteryTemperatureDeciC?.let { put("temperatureDeciC", it) }
                        put("chargingState", value.chargingState.name)
                    }.toString(),
                )
            }
            if (previousEnvironment.thermalStatus != value.thermalStatus) {
                appendEvent(
                    MonitorRuntimeEventType.THERMAL_CHANGED,
                    buildJsonObject {
                        value.thermalStatus?.let { put("thermalStatus", it) }
                    }.toString(),
                )
            }
        }
    }

    fun interruptNow() {
        applyEvent(SessionEvent.Interrupted)
        sampleJob?.cancel()
        sampleJob = null
        pssJob?.cancel()
        pssJob = null
        assembler.reset()
    }

    fun close() {
        sampleJob?.cancel()
        sampleJob = null
        pssJob?.cancel()
        pssJob = null
        assembler.reset()
    }

    private suspend fun restartSampling(
        resetBaseline: Boolean,
        cancelPss: Boolean,
    ) = samplingMutex.withLock {
        val previousJob = sampleJob
        sampleJob = null
        previousJob?.cancelAndJoin()
        if (cancelPss) {
            val previousPssJob = pssJob
            pssJob = null
            previousPssJob?.cancelAndJoin()
        }
        if (resetBaseline) resetSamplingBaseline()
        if (!machine.samplesBackend || initializingSession) return@withLock

        val config = SamplingConfig(
            intervalMs = effectiveIntervalMs(),
            pssIntervalMs = preset.pssIntervalMs,
        )
        val launched = scope.launch {
            try {
                backend.frames(config).collect { raw ->
                    val frame = assembler.assemble(raw).copy(
                        sequence = nextSessionFrameSequence,
                    )
                    val previousSource = lastDataSource
                    if (previousSource != null && previousSource != frame.source) {
                        pssJob?.cancel()
                        pssCadence.reset()
                    }
                    if (
                        previousSource != frame.source &&
                        (previousSource != null || frame.source == MetricDataSource.PS_FALLBACK)
                    ) {
                        appendEvent(
                            MonitorRuntimeEventType.DATA_SOURCE_CHANGED,
                            buildJsonObject {
                                previousSource?.let { put("previousSource", it.name) }
                                put("source", frame.source.name)
                            }.toString(),
                        )
                    }
                    lastDataSource = frame.source
                    val catalogChanged = catalog.revision != frame.catalogRevision
                    if (catalogChanged) {
                        catalog = backend.readCatalog(frame.catalogRevision)
                    }
                    if (
                        catalogChanged ||
                        frame.elapsedRealtimeNanos >= nextPackageResolutionRefreshNanos
                    ) {
                        catalogResolutions = packageResolver.resolve(catalog.entries)
                        nextPackageResolutionRefreshNanos = saturatedAdd(
                            frame.elapsedRealtimeNanos,
                            PACKAGE_RESOLUTION_REFRESH_NANOS,
                        )
                    }
                    val applications = ApplicationAggregator.aggregate(
                        frame = frame,
                        catalog = catalog.entries,
                        resolutions = catalogResolutions,
                    )
                    val liveFrame = LiveTimelineFrame(
                        frame = frame,
                        environment = environment,
                        applications = applications,
                        catalog = catalog.entries,
                        packageResolutions = catalogResolutions,
                    )
                    val currentPinnedKeys = PinnedTargetMatcher.matchingKeys(
                        targets = pinnedTargets(),
                        catalog = catalog.entries,
                        resolutions = catalogResolutions,
                    )
                    val retentionReasons = RetentionPolicy.reasons(
                        frame = frame,
                        pinnedKeys = currentPinnedKeys,
                        detailKeys = store.state.value.detailProcessKeys,
                    )
                    val runtime = store.state.value
                    val persisted = runtime.sessionId != null &&
                        runtime.startedElapsedRealtimeNanos != null &&
                        recorder.recordFrame(
                            HistoryFrameRecord(
                                sessionId = runtime.sessionId,
                                sessionStartElapsedRealtimeNanos =
                                    runtime.startedElapsedRealtimeNanos,
                                timelineFrame = liveFrame,
                                samplingIntervalMs = effectiveIntervalMs(),
                                retentionReasons = retentionReasons,
                            ),
                        )
                    if (!persisted) {
                        enterStoragePause()
                        throw StoragePausedException()
                    }
                    nextSessionFrameSequence = incrementSessionSequence(nextSessionFrameSequence)
                    if (machine.phase == MonitorPhase.STARTING) {
                        applyEvent(SessionEvent.FirstFrameReceived)
                    }
                    store.update { previous ->
                        val recentFrames = retainRecentFrames(previous.recentFrames, liveFrame)
                        val recentProcessKeys = recentFrames.asSequence()
                            .flatMap { recent -> recent.frame.metrics.asSequence() }
                            .map { metric -> metric.key }
                            .toSet()
                        val currentApplicationIds = applications.asSequence()
                            .map { application -> application.stableId }
                            .toSet()
                        previous.copy(
                            machineState = machine,
                            frameCount = saturatedIncrement(previous.frameCount),
                            catalogEntryCount = catalog.entries.size,
                            lastFrame = frame,
                            applications = applications,
                            catalog = catalog.entries,
                            packageResolutions = catalogResolutions,
                            recentFrames = recentFrames,
                            processPeaks = retainProcessPeaks(
                                peaks = mergeProcessPeaks(previous.processPeaks, frame.metrics),
                                retainedKeys = recentProcessKeys,
                            ),
                            applicationPeaks = retainApplicationPeaks(
                                peaks = mergeApplicationPeaks(
                                    previous.applicationPeaks,
                                    applications,
                                ),
                                retainedIds = currentApplicationIds,
                            ),
                            failure = MonitorFailure.NONE,
                        )
                    }
                    schedulePss(frame, currentPinnedKeys)
                }
                throw IllegalStateException("backend frame stream completed unexpectedly")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: StoragePausedException) {
                assembler.reset()
                pssJob?.cancel()
                pssJob = null
            } catch (error: Exception) {
                val failure = if (error is BackendProtocolException) {
                    MonitorFailure.BACKEND_PROTOCOL
                } else {
                    MonitorFailure.BACKEND_UNAVAILABLE
                }
                applyEvent(SessionEvent.BackendUnavailable, failure)
                assembler.reset()
                pssJob?.cancel()
                pssJob = null
                onBackendFailure()
            }
        }
        sampleJob = launched
    }

    private fun schedulePss(
        frame: MetricFrame,
        currentPinnedKeys: Set<ProcessKey>,
    ) {
        if (pssJob?.isCompleted == false || !pssCadence.isDue(frame.elapsedRealtimeNanos)) return
        val targets = RetentionPolicy.selectPssTargets(
            frame = frame,
            pinnedKeys = currentPinnedKeys,
            detailKeys = store.state.value.detailProcessKeys,
        ).keys
        if (targets.isEmpty()) return
        pssJob = scope.launch {
            try {
                val result = backend.readPss(targets)
                assembler.recordPss(
                    valuesKb = result.valuesKb,
                    sampledAtElapsedRealtimeNanos = result.sampledAtElapsedRealtimeNanos,
                    source = frame.source,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // PSS is optional and must never stop the CPU/RSS frame loop.
            }
        }
    }

    private suspend fun stopSampling(resetBaseline: Boolean) = samplingMutex.withLock {
        val previousJob = sampleJob
        sampleJob = null
        previousJob?.cancelAndJoin()
        val previousPssJob = pssJob
        pssJob = null
        previousPssJob?.cancelAndJoin()
        if (resetBaseline) resetSamplingBaseline()
    }

    private fun applyEvent(
        event: SessionEvent,
        failure: MonitorFailure? = null,
    ) {
        val previousMachine = machine
        var terminalHistoryEvent: MonitorRuntimeEvent? = null
        machine = SessionStateMachine.reduce(previousMachine, event)
        store.update { previous ->
            previous.copy(
                machineState = machine,
                failure = failure ?: previous.failure,
            )
        }
        when (event) {
            SessionEvent.StartRequested -> if (
                previousMachine.phase != machine.phase && machine.phase == MonitorPhase.STARTING
            ) {
                appendEvent(MonitorRuntimeEventType.SESSION_STARTED)
            }
            SessionEvent.FirstFrameReceived -> if (
                previousMachine.phase == MonitorPhase.STARTING && machine.phase == MonitorPhase.RUNNING
            ) {
                appendEvent(MonitorRuntimeEventType.FIRST_FRAME)
            }
            SessionEvent.UserPauseRequested -> if (
                previousMachine.pauseReason != PauseReason.USER && machine.pauseReason == PauseReason.USER
            ) {
                appendEvent(MonitorRuntimeEventType.USER_PAUSED)
            }
            is SessionEvent.UserResumeRequested -> if (
                previousMachine.pauseReason == PauseReason.USER &&
                machine.pauseReason != PauseReason.USER
            ) {
                appendEvent(MonitorRuntimeEventType.USER_RESUMED)
            }
            SessionEvent.BackendUnavailable -> if (
                previousMachine.hasActiveSession &&
                previousMachine.backendReady &&
                !machine.backendReady &&
                !shizukuGapOpen
            ) {
                shizukuGapOpen = true
                appendEvent(MonitorRuntimeEventType.DATA_GAP_START)
            }
            is SessionEvent.BackendAvailable -> {
                if (shizukuGapOpen && machine.backendReady && event.sameBoot) {
                    shizukuGapOpen = false
                    appendEvent(MonitorRuntimeEventType.DATA_GAP_END)
                }
                if (
                    previousMachine.hasActiveSession &&
                    machine.phase == MonitorPhase.INTERRUPTED
                ) {
                    shizukuGapOpen = false
                    terminalHistoryEvent = pendingOrNewTerminalEvent(
                        MonitorRuntimeEventType.SESSION_INTERRUPTED,
                    )
                }
            }
            SessionEvent.StorageFailed -> if (
                previousMachine.pauseReason != PauseReason.STORAGE &&
                machine.pauseReason == PauseReason.STORAGE
            ) {
                pendingStoragePauseEvent = appendEvent(MonitorRuntimeEventType.STORAGE_PAUSED)
            }
            is SessionEvent.StorageRecovered -> if (
                previousMachine.pauseReason == PauseReason.STORAGE &&
                machine.pauseReason != PauseReason.STORAGE
            ) {
                appendEvent(MonitorRuntimeEventType.STORAGE_RESUMED)
            }
            SessionEvent.StopRequested -> if (
                previousMachine.hasActiveSession && machine.phase == MonitorPhase.COMPLETED
            ) {
                shizukuGapOpen = false
                terminalHistoryEvent = pendingOrNewTerminalEvent(
                    MonitorRuntimeEventType.SESSION_COMPLETED,
                )
            }
            SessionEvent.Interrupted -> if (
                previousMachine.hasActiveSession && machine.phase == MonitorPhase.INTERRUPTED
            ) {
                shizukuGapOpen = false
                terminalHistoryEvent = pendingOrNewTerminalEvent(
                    MonitorRuntimeEventType.SESSION_INTERRUPTED,
                )
            }
            SessionEvent.Reset -> Unit
        }
        if (previousMachine != machine) {
            if (terminalHistoryEvent == null) {
                recordState()
            } else {
                val accepted = recordTerminalState(terminalHistoryEvent)
                if (!accepted) {
                    if (stateBeforeStorageFailure == null) {
                        stateBeforeStorageFailure = previousMachine
                    }
                    applyEvent(SessionEvent.StorageFailed, MonitorFailure.STORAGE)
                }
            }
        }
    }

    private fun enterStoragePause() {
        if (machine.hasActiveSession && machine.pauseReason != PauseReason.STORAGE) {
            stateBeforeStorageFailure = machine
        }
        applyEvent(SessionEvent.StorageFailed, MonitorFailure.STORAGE)
    }

    private fun appendEvent(
        type: MonitorRuntimeEventType,
        payloadJson: String? = null,
        persist: Boolean = true,
    ): MonitorRuntimeEvent {
        val event = store.appendEvent(
            type = type,
            wallTimeMillis = wallTimeMillis(),
            elapsedRealtimeNanos = elapsedRealtimeNanos(),
        )
        if (!persist) return event
        recordHistoryEvent(event, payloadJson)
        return event
    }

    private fun pendingOrNewTerminalEvent(type: MonitorRuntimeEventType): MonitorRuntimeEvent {
        val existing = pendingTerminalEvent?.takeIf { it.type == type }
        if (existing != null) {
            // Keep one logical runtime marker, but timestamp the durable history row at the
            // successful retry rather than before the intervening storage-pause evidence.
            return existing.copy(
                wallTimeMillis = wallTimeMillis(),
                elapsedRealtimeNanos = elapsedRealtimeNanos(),
            )
        }
        return appendEvent(type, persist = false).also { pendingTerminalEvent = it }
    }

    private fun recordHistoryEvent(
        event: MonitorRuntimeEvent,
        payloadJson: String? = null,
    ): Boolean {
        val runtime = store.state.value
        val sessionId = runtime.sessionId ?: return false
        val started = runtime.startedElapsedRealtimeNanos ?: return false
        return recorder.recordEvent(
            HistoryEventRecord(sessionId, started, event, payloadJson),
        )
    }

    private fun recordState() {
        if (machine.phase == MonitorPhase.NOT_READY || machine.phase == MonitorPhase.READY) return
        val state = currentHistoryState() ?: return
        recorder.updateState(state)
    }

    private fun recordTerminalState(event: MonitorRuntimeEvent): Boolean {
        val state = currentHistoryState() ?: return false
        val runtime = store.state.value
        val started = runtime.startedElapsedRealtimeNanos ?: return false
        val accepted = recorder.updateTerminalState(
            state = state,
            event = HistoryEventRecord(state.sessionId, started, event),
        )
        if (!accepted) {
            store.update { previous -> previous.copy(failure = MonitorFailure.STORAGE) }
        }
        return accepted
    }

    private fun currentHistoryState(): HistoryStateRecord? {
        val sessionId = store.state.value.sessionId ?: return null
        return HistoryStateRecord(
            sessionId = sessionId,
            phase = machine.phase,
            pauseReason = machine.pauseReason,
            wallTimeMillis = wallTimeMillis(),
            elapsedOffsetMs = currentElapsedOffsetMs(),
        )
    }

    private suspend fun finishRecording(): Boolean {
        val state = currentHistoryState() ?: return false
        val stored = recorder.finishSession(state)
        if (!stored) store.update { previous -> previous.copy(failure = MonitorFailure.STORAGE) }
        return stored
    }

    private fun effectiveIntervalMs(): Long = preset.effectiveIntervalMs(
        appForeground = environment.appForeground,
        screenInteractive = environment.screenInteractive,
    )

    private fun saturatedIncrement(value: Long): Long =
        if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

    private fun incrementSessionSequence(value: Long): Long {
        check(value < Long.MAX_VALUE) { "session frame sequence exhausted" }
        return value + 1L
    }

    private fun resetSamplingBaseline() {
        assembler.reset()
        catalog = CatalogSnapshot(revision = 0L, entries = emptyList())
        catalogResolutions = emptyList()
        nextPackageResolutionRefreshNanos = 0L
    }

    private fun saturatedAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private fun currentElapsedOffsetMs(): Long {
        val started = store.state.value.startedElapsedRealtimeNanos ?: return 0L
        return elapsedOffsetMs(elapsedRealtimeNanos(), started)
    }

    private fun elapsedOffsetMs(value: Long, start: Long): Long =
        if (value <= start) 0L else (value - start) / NANOS_PER_MILLISECOND

    private fun retainRecentFrames(
        previous: List<LiveTimelineFrame>,
        current: LiveTimelineFrame,
    ): List<LiveTimelineFrame> {
        val cutoff = (current.frame.elapsedRealtimeNanos - LIVE_WINDOW_NANOS).coerceAtLeast(0L)
        return previous.asSequence()
            .filter { it.frame.elapsedRealtimeNanos >= cutoff }
            .plus(current)
            .toList()
            .takeLast(MAX_LIVE_FRAMES)
    }

    private companion object {
        const val MAX_SESSION_NAME_CHARS = 80
        const val DEFAULT_SESSION_NAME = "ProcView session"
        const val LIVE_WINDOW_NANOS = 60_000_000_000L
        const val PACKAGE_RESOLUTION_REFRESH_NANOS = 30_000_000_000L
        const val MAX_LIVE_FRAMES = 128
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private class StoragePausedException : IllegalStateException("history recorder rejected a frame")

private sealed interface DeferredBackendSignal {
    data class Available(val report: CapabilityReport) : DeferredBackendSignal
    data object Unavailable : DeferredBackendSignal
}
