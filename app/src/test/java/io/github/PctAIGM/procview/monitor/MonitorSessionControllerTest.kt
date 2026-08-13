package io.github.PctAIGM.procview.monitor

import io.github.PctAIGM.procview.model.BackendMode
import io.github.PctAIGM.procview.model.CapabilityQuality
import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.MetricDataSource
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.RawMetricFrame
import io.github.PctAIGM.procview.model.RawProcessMetric
import io.github.PctAIGM.procview.sampler.CatalogSnapshot
import io.github.PctAIGM.procview.sampler.DiagnosticBundle
import io.github.PctAIGM.procview.sampler.FakeMonitorBackend
import io.github.PctAIGM.procview.sampler.PrivilegedMonitorBackend
import io.github.PctAIGM.procview.sampler.ProcessPackageResolver
import io.github.PctAIGM.procview.sampler.PssBatch
import io.github.PctAIGM.procview.sampler.SamplingConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MonitorSessionControllerTest {
    private val key = ProcessKey(42, 420L)
    private val report = capabilityReport()
    private val resolver = ProcessPackageResolver { emptyList() }

    @Test
    fun acceptedSessionReachesRunningAndPublishesTypedFrames() = runTest {
        val backend = openEnded(FakeMonitorBackend(
            capabilityReport = report,
            frameSequence = listOf(rawFrame(1, 100, 10), rawFrame(2, 200, 20)),
            catalogs = mapOf(1L to listOf(catalogEntry())),
        ))
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope)
        controller.backendAvailable(report)

        assertTrue(controller.startSession("Test", SamplingPreset.BALANCED))
        runCurrent()

        val snapshot = store.state.value
        assertEquals(MonitorPhase.RUNNING, snapshot.machineState.phase)
        assertEquals(2L, snapshot.frameCount)
        assertEquals(1, snapshot.catalogEntryCount)
        assertEquals(1, snapshot.applications.size)
        assertEquals(2, snapshot.recentFrames.size)
        assertEquals(1, snapshot.recentFrames.last().catalog.size)
        assertEquals(1_000, snapshot.lastFrame?.metrics?.single()?.cpuPercentBasisPoints)
    }

    @Test
    fun stableCatalogRefreshesPackageResolutionOnTheBoundedCadence() = runTest {
        val backend = openEnded(FakeMonitorBackend(
            capabilityReport = report,
            frameSequence = listOf(
                rawFrame(1, 100, 10),
                rawFrame(31, 200, 20),
                rawFrame(32, 300, 30),
            ),
            catalogs = mapOf(1L to listOf(catalogEntry())),
        ))
        var resolutionCount = 0
        val countingResolver = ProcessPackageResolver {
            resolutionCount += 1
            emptyList()
        }
        val store = MonitorRuntimeStore()
        val controller = controller(
            backend = backend,
            store = store,
            scope = backgroundScope,
            packageResolver = countingResolver,
        )
        controller.backendAvailable(report)

        assertTrue(controller.startSession("Package refresh", SamplingPreset.BALANCED))
        runCurrent()

        assertEquals(2, resolutionCount)
    }

    @Test
    fun backendFailurePausesForShizukuAndRequestsRefresh() = runTest {
        val backend = openEnded(FakeMonitorBackend(
            capabilityReport = report,
            frameSequence = listOf(rawFrame(1, 100, 10), rawFrame(2, 200, 20)),
            catalogs = mapOf(1L to listOf(catalogEntry())),
            failureAtFrameIndex = 1,
        ))
        val store = MonitorRuntimeStore()
        var refreshCount = 0
        val controller = controller(
            backend,
            store,
            backgroundScope,
            onBackendFailure = { refreshCount++ },
        )
        controller.backendAvailable(report)

        controller.startSession("Failure", SamplingPreset.BALANCED)
        runCurrent()

        assertEquals(MonitorPhase.PAUSED, store.state.value.machineState.phase)
        assertEquals(PauseReason.SHIZUKU, store.state.value.machineState.pauseReason)
        assertEquals(MonitorFailure.BACKEND_UNAVAILABLE, store.state.value.failure)
        assertEquals(1, refreshCount)
    }

    @Test
    fun pssFailureDoesNotStopCpuAndRssFrames() = runTest {
        val base = openEnded(FakeMonitorBackend(
            capabilityReport = report,
            frameSequence = listOf(rawFrame(1, 100, 10), rawFrame(2, 200, 20)),
            catalogs = mapOf(1L to listOf(catalogEntry())),
        ))
        val backend = object : PrivilegedMonitorBackend by base {
            override suspend fun readPss(keys: List<ProcessKey>): PssBatch {
                throw IllegalStateException("PSS unavailable")
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope)
        controller.backendAvailable(report)

        controller.startSession("PSS", SamplingPreset.BALANCED)
        runCurrent()

        assertEquals(MonitorPhase.RUNNING, store.state.value.machineState.phase)
        assertEquals(2L, store.state.value.frameCount)
    }

    @Test
    fun fallbackSourceIsVisibleAndRecordedAsTimelineEvent() = runTest {
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(
                    rawFrame(1, 100, 10),
                    rawFrame(2, 200, 20).copy(source = MetricDataSource.PS_FALLBACK),
                ),
                catalogs = mapOf(1L to listOf(catalogEntry())),
            ),
        )
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope)
        controller.backendAvailable(report)

        controller.startSession("Fallback", SamplingPreset.BALANCED)
        runCurrent()

        assertEquals(MetricDataSource.PS_FALLBACK, store.state.value.lastFrame?.source)
        assertEquals(
            1,
            store.state.value.recentEvents.count {
                it.type == MonitorRuntimeEventType.DATA_SOURCE_CHANGED
            },
        )
    }

    @Test
    fun environmentChangeRestartsAtBackgroundInterval() = runTest {
        val observedConfigs = mutableListOf<SamplingConfig>()
        val backend = object : PrivilegedMonitorBackend {
            override suspend fun probe(): CapabilityReport = report

            override fun frames(config: SamplingConfig): Flow<RawMetricFrame> = flow {
                observedConfigs += config
                emit(rawFrame(observedConfigs.size.toLong(), observedConfigs.size * 100L, 10))
                awaitCancellation()
            }

            override suspend fun readCatalog(revision: Long) = CatalogSnapshot(0, emptyList())

            override suspend fun readPss(keys: List<ProcessKey>) = PssBatch(0, 0, emptyMap(), 0)

            override suspend fun diagnostics() = DiagnosticBundle("recording", 2, emptyList())
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope)
        controller.backendAvailable(report)
        controller.startSession("Cadence", SamplingPreset.BALANCED)
        runCurrent()

        controller.updateEnvironment(MonitorEnvironment(appForeground = false, screenInteractive = true))
        runCurrent()

        assertEquals(listOf(1_000L, 5_000L), observedConfigs.map { it.intervalMs })
        controller.close()
    }

    @Test
    fun shizukuDisconnectAndSameBootRecoveryRecordOneGapPair() = runTest {
        val backend = openEnded(FakeMonitorBackend(
            capabilityReport = report,
            frameSequence = listOf(rawFrame(1, 100, 10)),
            catalogs = mapOf(1L to listOf(catalogEntry())),
        ))
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope)
        controller.backendAvailable(report)
        controller.startSession("Gap", SamplingPreset.BALANCED)
        runCurrent()

        controller.backendUnavailable()
        controller.backendUnavailable()
        controller.backendAvailable(report)
        controller.backendAvailable(report)
        runCurrent()

        val types = store.state.value.recentEvents.map(MonitorRuntimeEvent::type)
        assertEquals(1, types.count { it == MonitorRuntimeEventType.DATA_GAP_START })
        assertEquals(1, types.count { it == MonitorRuntimeEventType.DATA_GAP_END })
        assertEquals(MonitorPhase.RUNNING, store.state.value.machineState.phase)
        assertEquals(listOf(1L, 2L), store.state.value.recentFrames.map { it.frame.sequence })
    }

    @Test
    fun partialCapabilityIsRejectedByDefault() = runTest {
        val partialReport = report.copy(quality = CapabilityQuality.PARTIAL)
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = partialReport,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope)

        controller.backendAvailable(partialReport)

        assertEquals(MonitorPhase.NOT_READY, store.state.value.machineState.phase)
        assertEquals(MonitorFailure.BACKEND_UNAVAILABLE, store.state.value.failure)
        assertTrue(!controller.startSession("Partial", SamplingPreset.BALANCED))
    }

    @Test
    fun partialCapabilityCanBeEnabledForInternalBuilds() = runTest {
        val partialReport = report.copy(quality = CapabilityQuality.PARTIAL)
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = partialReport,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val store = MonitorRuntimeStore()
        val controller = controller(
            backend = backend,
            store = store,
            scope = backgroundScope,
            allowPartialCapability = true,
        )

        controller.backendAvailable(partialReport)
        assertTrue(controller.startSession("Partial", SamplingPreset.BALANCED))
        runCurrent()

        assertEquals(MonitorPhase.RUNNING, store.state.value.machineState.phase)
    }

    @Test
    fun rejectedDurableSessionStartLeavesControllerReadyWithoutPublishingSession() = runTest {
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override suspend fun startSession(start: HistorySessionStart): Boolean = false
        }
        val store = MonitorRuntimeStore()
        val controller = controller(
            backend = backend,
            store = store,
            scope = backgroundScope,
            recorder = recorder,
        )
        controller.backendAvailable(report)

        assertFalse(controller.startSession("Rejected start", SamplingPreset.BALANCED))

        assertEquals(MonitorPhase.READY, store.state.value.machineState.phase)
        assertEquals(null, store.state.value.sessionId)
        assertEquals(MonitorFailure.STORAGE, store.state.value.failure)
    }

    @Test
    fun staleReadyStateWithoutAControllerCapabilityReportForcesPreflight() = runTest {
        var probeCount = 0
        val delegate = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val backend = object : PrivilegedMonitorBackend by delegate {
            override suspend fun probe(): CapabilityReport {
                probeCount += 1
                return report
            }
        }
        val store = MonitorRuntimeStore().also { runtimeStore ->
            runtimeStore.update { previous ->
                previous.copy(machineState = SessionMachineState.initial(backendReady = true))
            }
        }
        val controller = controller(backend, store, backgroundScope)

        assertTrue(controller.startSession("Fresh preflight", SamplingPreset.BALANCED))
        runCurrent()

        assertEquals(1, probeCount)
        assertEquals(MonitorPhase.RUNNING, store.state.value.machineState.phase)
    }

    @Test
    fun overlappingRestartRequestsNeverCollectTwoBackendFlowsAtOnce() = runTest {
        var sequence = 0L
        var activeCollectors = 0
        var maximumCollectors = 0
        val cancellationEntered = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        val backend = object : PrivilegedMonitorBackend {
            override suspend fun probe(): CapabilityReport = report

            override suspend fun verifyBootId(): String = report.bootId

            override fun frames(config: SamplingConfig): Flow<RawMetricFrame> = flow {
                activeCollectors += 1
                maximumCollectors = maxOf(maximumCollectors, activeCollectors)
                try {
                    sequence += 1
                    emit(rawFrame(sequence, sequence * 100L, sequence * 10L))
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cancellationEntered.complete(Unit)
                        releaseCancellation.await()
                    }
                    activeCollectors -= 1
                }
            }

            override suspend fun readCatalog(revision: Long) = CatalogSnapshot(
                revision,
                listOf(catalogEntry()),
            )

            override suspend fun readPss(keys: List<ProcessKey>) =
                PssBatch(0, 0, emptyMap(), 0)

            override suspend fun diagnostics() = DiagnosticBundle("serialized", 4, emptyList())
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope)
        controller.backendAvailable(report)
        assertTrue(controller.startSession("Restart race", SamplingPreset.BALANCED))
        runCurrent()

        val intervalRestart = async {
            controller.updateEnvironment(
                MonitorEnvironment(appForeground = false, screenInteractive = true),
            )
        }
        cancellationEntered.await()
        val backendRestart = async { controller.backendAvailable(report) }
        runCurrent()

        assertEquals(1, maximumCollectors)
        releaseCancellation.complete(Unit)
        intervalRestart.await()
        backendRestart.await()
        runCurrent()

        assertEquals(1, maximumCollectors)
        assertEquals(1, activeCollectors)
    }

    @Test
    fun backendLossDuringDurableStartProducesAnActiveShizukuPause() = runTest {
        val delegate = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val backend = object : PrivilegedMonitorBackend by delegate {
            override suspend fun probe(): CapabilityReport {
                throw IllegalStateException("backend disconnected")
            }

            override suspend fun verifyBootId(): String {
                throw IllegalStateException("backend disconnected")
            }
        }
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override suspend fun startSession(start: HistorySessionStart): Boolean {
                startEntered.complete(Unit)
                releaseStart.await()
                return true
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope, recorder = recorder)
        controller.backendAvailable(report)

        val start = async { controller.startSession("Race", SamplingPreset.BALANCED) }
        startEntered.await()
        controller.backendUnavailable()
        assertEquals(MonitorPhase.READY, store.state.value.machineState.phase)
        releaseStart.complete(Unit)

        assertTrue(start.await())
        assertEquals(MonitorPhase.PAUSED, store.state.value.machineState.phase)
        assertEquals(PauseReason.SHIZUKU, store.state.value.machineState.pauseReason)
        assertTrue(store.state.value.sessionId != null)
    }

    @Test
    fun cancellationAfterDurableStartClosesTheSessionAsInterrupted() = runTest {
        val verifyEntered = CompletableDeferred<Unit>()
        val delegate = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val backend = object : PrivilegedMonitorBackend by delegate {
            override suspend fun verifyBootId(): String {
                verifyEntered.complete(Unit)
                awaitCancellation()
            }
        }
        val terminalStates = mutableListOf<HistoryStateRecord>()
        val terminalEvents = mutableListOf<HistoryEventRecord>()
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override fun updateTerminalState(
                state: HistoryStateRecord,
                event: HistoryEventRecord,
            ): Boolean {
                terminalStates += state
                terminalEvents += event
                return true
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope, recorder = recorder)
        controller.backendAvailable(report)

        val start = async { controller.startSession("Cancelled start", SamplingPreset.BALANCED) }
        verifyEntered.await()
        start.cancelAndJoin()

        assertEquals(MonitorPhase.INTERRUPTED, store.state.value.machineState.phase)
        assertEquals(listOf(MonitorPhase.INTERRUPTED), terminalStates.map(HistoryStateRecord::phase))
        assertEquals(
            listOf(MonitorRuntimeEventType.SESSION_INTERRUPTED),
            terminalEvents.map { it.event.type },
        )
    }

    @Test
    fun explicitStopDuringDurableStartCompletesAndFlushesTheSession() = runTest {
        val verifyEntered = CompletableDeferred<Unit>()
        val delegate = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val backend = object : PrivilegedMonitorBackend by delegate {
            override suspend fun verifyBootId(): String {
                verifyEntered.complete(Unit)
                awaitCancellation()
            }
        }
        val terminalStates = mutableListOf<HistoryStateRecord>()
        val terminalEvents = mutableListOf<HistoryEventRecord>()
        val finishedStates = mutableListOf<HistoryStateRecord>()
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override fun updateTerminalState(
                state: HistoryStateRecord,
                event: HistoryEventRecord,
            ): Boolean {
                terminalStates += state
                terminalEvents += event
                return true
            }

            override suspend fun finishSession(state: HistoryStateRecord): Boolean {
                finishedStates += state
                return true
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope, recorder = recorder)
        controller.backendAvailable(report)

        val start = async { controller.startSession("Stopped start", SamplingPreset.BALANCED) }
        verifyEntered.await()
        start.cancel(UserStopDuringStartCancellation())
        start.join()

        assertEquals(MonitorPhase.COMPLETED, store.state.value.machineState.phase)
        assertEquals(listOf(MonitorPhase.COMPLETED), terminalStates.map(HistoryStateRecord::phase))
        assertEquals(
            listOf(MonitorRuntimeEventType.SESSION_COMPLETED),
            terminalEvents.map { it.event.type },
        )
        assertEquals(listOf(MonitorPhase.COMPLETED), finishedStates.map(HistoryStateRecord::phase))
    }

    @Test
    fun bootChangeDetectedAfterDurableStartInterruptsBeforeSampling() = runTest {
        val delegate = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val backend = object : PrivilegedMonitorBackend by delegate {
            override suspend fun probe(): CapabilityReport = report.copy(bootId = "new-boot")
            override suspend fun verifyBootId(): String = "new-boot"
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope)
        controller.backendAvailable(report)

        assertTrue(controller.startSession("Boot change", SamplingPreset.BALANCED))
        runCurrent()

        assertEquals(MonitorPhase.INTERRUPTED, store.state.value.machineState.phase)
        assertEquals(0L, store.state.value.frameCount)
        assertEquals(
            1,
            store.state.value.recentEvents.count {
                it.type == MonitorRuntimeEventType.SESSION_INTERRUPTED
            },
        )
    }

    @Test
    fun failedBootInterruptionTerminalWriteRetriesAsInterrupted() = runTest {
        val delegate = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
            ),
        )
        val backend = object : PrivilegedMonitorBackend by delegate {
            override suspend fun verifyBootId(): String = "new-boot"
        }
        var terminalAttempts = 0
        var recoveries = 0
        var finishes = 0
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override fun updateTerminalState(
                state: HistoryStateRecord,
                event: HistoryEventRecord,
            ): Boolean {
                terminalAttempts += 1
                return terminalAttempts > 1
            }

            override suspend fun recover(
                pausedState: HistoryStateRecord?,
                pausedEvent: HistoryEventRecord?,
            ): Boolean {
                recoveries += 1
                return true
            }

            override suspend fun finishSession(state: HistoryStateRecord): Boolean {
                finishes += 1
                return true
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope, recorder = recorder)
        controller.backendAvailable(report)

        assertTrue(controller.startSession("Boot storage retry", SamplingPreset.BALANCED))

        assertEquals(MonitorPhase.PAUSED, store.state.value.machineState.phase)
        assertEquals(PauseReason.STORAGE, store.state.value.machineState.pauseReason)
        assertEquals(1, terminalAttempts)

        controller.storageRecovered()

        assertEquals(MonitorPhase.INTERRUPTED, store.state.value.machineState.phase)
        assertEquals(2, terminalAttempts)
        assertEquals(1, recoveries)
        assertEquals(1, finishes)
        assertEquals(
            1,
            store.state.value.recentEvents.count {
                it.type == MonitorRuntimeEventType.SESSION_INTERRUPTED
            },
        )
    }

    @Test
    fun rejectedHistoryFramePausesWithoutReportingBackendFailure() = runTest {
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
                catalogs = mapOf(1L to listOf(catalogEntry())),
            ),
        )
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override val failures = emptyFlow<MonitorStorageFailure>()
            override fun recordFrame(frame: HistoryFrameRecord): Boolean = false
        }
        val store = MonitorRuntimeStore()
        var backendRefreshCount = 0
        val controller = controller(
            backend = backend,
            store = store,
            scope = backgroundScope,
            onBackendFailure = { backendRefreshCount++ },
            recorder = recorder,
        )
        controller.backendAvailable(report)

        controller.startSession("Storage", SamplingPreset.BALANCED)
        runCurrent()

        assertEquals(MonitorPhase.PAUSED, store.state.value.machineState.phase)
        assertEquals(PauseReason.STORAGE, store.state.value.machineState.pauseReason)
        assertEquals(MonitorFailure.STORAGE, store.state.value.failure)
        assertEquals(0L, store.state.value.frameCount)
        assertEquals(0, backendRefreshCount)
    }

    @Test
    fun storageAndPreFirstFrameRecoveryRemainStartingUntilAFrameArrives() = runTest {
        val frame = rawFrame(1, 100, 10)
        val delegate = FakeMonitorBackend(
            capabilityReport = report,
            frameSequence = listOf(frame),
            catalogs = mapOf(1L to listOf(catalogEntry())),
        )
        var subscriptionCount = 0
        val backend = object : PrivilegedMonitorBackend by delegate {
            override fun frames(config: SamplingConfig): Flow<RawMetricFrame> = flow {
                subscriptionCount++
                if (subscriptionCount == 1) emit(frame)
                awaitCancellation()
            }
        }
        var rejectNextFrame = true
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override val failures = emptyFlow<MonitorStorageFailure>()
            override fun recordFrame(frame: HistoryFrameRecord): Boolean {
                if (!rejectNextFrame) return true
                rejectNextFrame = false
                return false
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(backend, store, backgroundScope, recorder = recorder)
        controller.backendAvailable(report)
        controller.startSession("Starting recovery", SamplingPreset.BALANCED)
        runCurrent()

        controller.storageRecovered()
        runCurrent()

        assertEquals(2, subscriptionCount)
        assertEquals(MonitorPhase.STARTING, store.state.value.machineState.phase)
        assertEquals(null, store.state.value.machineState.pauseReason)

        controller.pauseByUser()
        assertEquals(MonitorPhase.PAUSED, store.state.value.machineState.phase)
        controller.resumeByUser()
        assertEquals(MonitorPhase.STARTING, store.state.value.machineState.phase)

        controller.backendUnavailable()
        assertEquals(PauseReason.SHIZUKU, store.state.value.machineState.pauseReason)
        controller.backendAvailable(report)
        assertEquals(MonitorPhase.STARTING, store.state.value.machineState.phase)
    }

    @Test
    fun storageRecoveryReplaysTheOriginalPauseBeforeResuming() = runTest {
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
                catalogs = mapOf(1L to listOf(catalogEntry())),
            ),
        )
        var rejectNextFrame = true
        var recoveredState: HistoryStateRecord? = null
        var recoveredEvent: HistoryEventRecord? = null
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override val failures = emptyFlow<MonitorStorageFailure>()
            override fun recordFrame(frame: HistoryFrameRecord): Boolean {
                if (!rejectNextFrame) return true
                rejectNextFrame = false
                return false
            }

            override suspend fun recover(
                pausedState: HistoryStateRecord?,
                pausedEvent: HistoryEventRecord?,
            ): Boolean {
                recoveredState = pausedState
                recoveredEvent = pausedEvent
                return true
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(
            backend = backend,
            store = store,
            scope = backgroundScope,
            recorder = recorder,
        )
        controller.backendAvailable(report)
        controller.startSession("Storage recovery", SamplingPreset.BALANCED)
        runCurrent()

        controller.storageRecovered()
        runCurrent()

        assertEquals(MonitorPhase.PAUSED, recoveredState?.phase)
        assertEquals(PauseReason.STORAGE, recoveredState?.pauseReason)
        assertEquals(MonitorRuntimeEventType.STORAGE_PAUSED, recoveredEvent?.event?.type)
        val eventTypes = store.state.value.recentEvents.map(MonitorRuntimeEvent::type)
        assertEquals(1, eventTypes.count { it == MonitorRuntimeEventType.STORAGE_PAUSED })
        assertEquals(1, eventTypes.count { it == MonitorRuntimeEventType.STORAGE_RESUMED })
    }

    @Test
    fun stoppingFromStoragePauseRecoversPauseRecordBeforeTerminalState() = runTest {
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
                catalogs = mapOf(1L to listOf(catalogEntry())),
            ),
        )
        var recoveredState: HistoryStateRecord? = null
        var recoveredEvent: HistoryEventRecord? = null
        var terminalState: HistoryStateRecord? = null
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override val failures = emptyFlow<MonitorStorageFailure>()
            override fun recordFrame(frame: HistoryFrameRecord): Boolean = false

            override suspend fun recover(
                pausedState: HistoryStateRecord?,
                pausedEvent: HistoryEventRecord?,
            ): Boolean {
                recoveredState = pausedState
                recoveredEvent = pausedEvent
                return true
            }

            override suspend fun finishSession(state: HistoryStateRecord): Boolean {
                terminalState = state
                return true
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(
            backend = backend,
            store = store,
            scope = backgroundScope,
            recorder = recorder,
        )
        controller.backendAvailable(report)
        controller.startSession("Storage stop", SamplingPreset.BALANCED)
        runCurrent()

        assertTrue(controller.stopByUser())

        assertEquals(MonitorPhase.PAUSED, recoveredState?.phase)
        assertEquals(PauseReason.STORAGE, recoveredState?.pauseReason)
        assertEquals(MonitorRuntimeEventType.STORAGE_PAUSED, recoveredEvent?.event?.type)
        assertEquals(MonitorPhase.COMPLETED, terminalState?.phase)
    }

    @Test
    fun terminalWriteFailureBecomesRetryableStoragePause() = runTest {
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
                catalogs = mapOf(1L to listOf(catalogEntry())),
            ),
        )
        var finishAttempts = 0
        var terminalAttempts = 0
        var recoveryCount = 0
        val terminalEvents = mutableListOf<HistoryEventRecord>()
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override val failures = emptyFlow<MonitorStorageFailure>()

            override fun updateTerminalState(
                state: HistoryStateRecord,
                event: HistoryEventRecord,
            ): Boolean {
                terminalAttempts++
                terminalEvents += event
                return terminalAttempts > 1
            }

            override suspend fun recover(
                pausedState: HistoryStateRecord?,
                pausedEvent: HistoryEventRecord?,
            ): Boolean {
                recoveryCount++
                return true
            }

            override suspend fun finishSession(state: HistoryStateRecord): Boolean {
                finishAttempts++
                return finishAttempts > 1
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(
            backend = backend,
            store = store,
            scope = backgroundScope,
            recorder = recorder,
        )
        controller.backendAvailable(report)
        controller.startSession("Retry terminal", SamplingPreset.BALANCED)
        runCurrent()

        assertFalse(controller.stopByUser())
        assertEquals(MonitorPhase.PAUSED, store.state.value.machineState.phase)
        assertEquals(PauseReason.STORAGE, store.state.value.machineState.pauseReason)

        assertTrue(controller.stopByUser())
        assertEquals(1, recoveryCount)
        assertEquals(2, finishAttempts)
        assertEquals(2, terminalAttempts)
        assertEquals(1, terminalEvents.map { it.event.sequence }.distinct().size)
        assertEquals(
            1,
            store.state.value.recentEvents.count {
                it.type == MonitorRuntimeEventType.SESSION_COMPLETED
            },
        )
        assertEquals(MonitorPhase.COMPLETED, store.state.value.machineState.phase)
    }

    @Test
    fun storageRecoveryRetriesPendingCompletedTerminalInsteadOfResumingSampling() = runTest {
        val backend = openEnded(
            FakeMonitorBackend(
                capabilityReport = report,
                frameSequence = listOf(rawFrame(1, 100, 10)),
                catalogs = mapOf(1L to listOf(catalogEntry())),
            ),
        )
        var finishAttempts = 0
        var terminalAttempts = 0
        var recoveryCount = 0
        val terminalPhases = mutableListOf<MonitorPhase>()
        val recorder = object : MonitorSessionRecorder by NoOpMonitorSessionRecorder {
            override val failures = emptyFlow<MonitorStorageFailure>()

            override fun updateTerminalState(
                state: HistoryStateRecord,
                event: HistoryEventRecord,
            ): Boolean {
                terminalAttempts++
                terminalPhases += state.phase
                return terminalAttempts > 1
            }

            override suspend fun recover(
                pausedState: HistoryStateRecord?,
                pausedEvent: HistoryEventRecord?,
            ): Boolean {
                recoveryCount++
                return true
            }

            override suspend fun finishSession(state: HistoryStateRecord): Boolean {
                finishAttempts++
                return finishAttempts > 1
            }
        }
        val store = MonitorRuntimeStore()
        val controller = controller(
            backend = backend,
            store = store,
            scope = backgroundScope,
            recorder = recorder,
        )
        controller.backendAvailable(report)
        controller.startSession("Recover terminal", SamplingPreset.BALANCED)
        runCurrent()

        assertFalse(controller.stopByUser())
        assertEquals(MonitorPhase.PAUSED, store.state.value.machineState.phase)
        assertEquals(PauseReason.STORAGE, store.state.value.machineState.pauseReason)

        controller.storageRecovered()
        runCurrent()

        assertEquals(1, recoveryCount)
        assertEquals(2, finishAttempts)
        assertEquals(2, terminalAttempts)
        assertEquals(listOf(MonitorPhase.COMPLETED, MonitorPhase.COMPLETED), terminalPhases)
        assertEquals(MonitorPhase.COMPLETED, store.state.value.machineState.phase)
        assertEquals(
            1,
            store.state.value.recentEvents.count {
                it.type == MonitorRuntimeEventType.SESSION_COMPLETED
            },
        )
    }

    private fun controller(
        backend: PrivilegedMonitorBackend,
        store: MonitorRuntimeStore,
        scope: kotlinx.coroutines.CoroutineScope,
        onBackendFailure: () -> Unit = {},
        allowPartialCapability: Boolean = false,
        recorder: MonitorSessionRecorder = NoOpMonitorSessionRecorder,
        packageResolver: ProcessPackageResolver = resolver,
    ) = MonitorSessionController(
        backend = backend,
        packageResolver = packageResolver,
        store = store,
        scope = scope,
        onBackendFailure = onBackendFailure,
        allowPartialCapability = allowPartialCapability,
        recorder = recorder,
        wallTimeMillis = { 1_000L },
        elapsedRealtimeNanos = { 2_000L },
    )

    private fun openEnded(delegate: PrivilegedMonitorBackend): PrivilegedMonitorBackend =
        object : PrivilegedMonitorBackend by delegate {
            override fun frames(config: SamplingConfig): Flow<RawMetricFrame> = flow {
                delegate.frames(config).collect { emit(it) }
                awaitCancellation()
            }
        }

    private fun rawFrame(sequence: Long, totalTicks: Long, processTicks: Long) = RawMetricFrame(
        sequence = sequence,
        elapsedRealtimeNanos = sequence * 1_000_000_000L,
        wallTimeMillis = sequence * 1_000L,
        systemTotalCpuTicks = totalTicks,
        systemIdleCpuTicks = totalTicks / 2,
        memoryTotalKb = 8_000,
        memoryAvailableKb = 4_000,
        collectionDurationMs = 5,
        catalogRevision = 1,
        frameFlags = 0,
        metrics = listOf(RawProcessMetric(key, processTicks, 100, 'R')),
    )

    private fun catalogEntry() = ProcessCatalogEntry(
        key = key,
        parentPid = 1,
        uid = 10_123,
        processName = "com.example",
        commandLine = "com.example",
    )

    private fun capabilityReport() = CapabilityReport(
        probedAtWallTimeMs = 1,
        shizukuApiVersion = 13,
        shizukuUid = 2_000,
        shizukuSelinuxContext = "u:r:shell:s0",
        serviceUid = 2_000,
        servicePid = 1,
        backendMode = BackendMode.ADB,
        protocolVersion = 4,
        bootId = "boot",
        procStatReadable = true,
        procMeminfoReadable = true,
        procPidCount = 1,
        psPidCount = 1,
        statReadableCount = 1,
        statusReadableCount = 1,
        cmdlineReadableCount = 1,
        rssReadableCount = 1,
        cpuAndRssReadableCount = 1,
        pid1StatReadable = true,
        psCommandAvailable = true,
        pssCommandAvailable = true,
        pssValueParsed = true,
        pssReadableCount = 1,
        pssProbeKb = 1,
        pssProbeDurationMs = 1,
        pssBatchProbeDurationMs = 1,
        thermalZoneCount = 1,
        thermalReadableCount = 1,
        thermalSensorNames = listOf("battery"),
        mappedUidCount = 1,
        sampledUidCount = 1,
        packageCandidateCount = 1,
        procScanDurationMs = 1,
        totalDurationMs = 1,
        processListTruncated = false,
        errorFlags = 0,
        quality = CapabilityQuality.AVAILABLE,
    )
}
