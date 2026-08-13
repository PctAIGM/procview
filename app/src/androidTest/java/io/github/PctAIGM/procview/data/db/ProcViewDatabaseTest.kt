package io.github.PctAIGM.procview.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.PctAIGM.procview.data.RoomMonitorSessionRecorder
import io.github.PctAIGM.procview.model.BackendMode
import io.github.PctAIGM.procview.model.CapabilityQuality
import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.MetricDataSource
import io.github.PctAIGM.procview.model.MetricFrame
import io.github.PctAIGM.procview.monitor.HistoryEventRecord
import io.github.PctAIGM.procview.monitor.HistoryFrameRecord
import io.github.PctAIGM.procview.monitor.HistorySessionStart
import io.github.PctAIGM.procview.monitor.HistoryStateRecord
import io.github.PctAIGM.procview.monitor.LiveTimelineFrame
import io.github.PctAIGM.procview.monitor.MonitorEnvironment
import io.github.PctAIGM.procview.monitor.MonitorPhase
import io.github.PctAIGM.procview.monitor.MonitorRuntimeEvent
import io.github.PctAIGM.procview.monitor.MonitorRuntimeEventType
import io.github.PctAIGM.procview.monitor.SamplingPreset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcViewDatabaseTest {
    private lateinit var database: ProcViewDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ProcViewDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun terminalSessionCascadesAndLeavesNoCapabilityOrphan() = runBlocking {
        val capabilityId = database.sessionDao().insertCapabilityReport(capability())
        database.sessionDao().insertSession(session("finished", "COMPLETED", capabilityId))
        database.sampleDao().insertSystemSamples(listOf(systemSample("finished")))
        val identityId = database.sampleDao().insertIdentity(identity("finished"))
        database.sampleDao().insertProcessSamples(listOf(processSample("finished", identityId)))
        database.sampleDao().insertEvents(listOf(event("finished")))

        assertEquals(1, database.sessionDao().deleteSession("finished"))
        assertEquals(1, database.sessionDao().deleteCapabilityReportIfOrphaned(capabilityId))
        val counts = database.sessionDao().storageCounts("finished")
        assertEquals(0L, counts.systemSampleCount)
        assertEquals(0L, counts.processSampleCount)
        assertEquals(0L, counts.identityCount)
        assertEquals(0L, counts.eventCount)
    }

    @Test
    fun activeSessionCannotBeDeletedAndMissingSummaryIsRepairable() = runBlocking {
        val activeCapability = database.sessionDao().insertCapabilityReport(capability())
        database.sessionDao().insertSession(session("active", "RUNNING", activeCapability))
        assertEquals(0, database.sessionDao().deleteSession("active"))

        val capabilityId = database.sessionDao().insertCapabilityReport(capability())
        database.sessionDao().insertSession(session("repair", "COMPLETED", capabilityId))
        database.sampleDao().insertSystemSamples(listOf(systemSample("repair")))
        val identityId = database.sampleDao().insertIdentity(identity("repair"))
        database.sampleDao().insertProcessSamples(listOf(processSample("repair", identityId)))

        assertTrue("repair" in database.sessionDao().sessionsNeedingSummaryRepair())
        val repairedSummary = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000L) {
                database.sampleDao().topSummaries("repair", 1).first { it.isNotEmpty() }
            }
        }
        database.sampleDao().rebuildSummaries("repair")
        assertEquals(
            "Example",
            repairedSummary.await().single().displayName,
        )
    }

    @Test
    fun historicalPackageTargetMatchesSecondaryPackageCandidateWithoutDoubleCounting() = runBlocking {
        val capabilityId = database.sessionDao().insertCapabilityReport(capability())
        database.sessionDao().insertSession(session("shared-uid", "COMPLETED", capabilityId))
        val firstSystemSample = systemSample("shared-uid")
        database.sampleDao().insertSystemSamples(
            listOf(
                firstSystemSample,
                firstSystemSample.copy(sequence = 2L, elapsedOffsetMs = 1_000L),
            ),
        )
        val identityId = database.sampleDao().insertIdentity(identity("shared-uid"))
        database.sampleDao().insertPackageCandidates(
            listOf(
                ProcessPackageCandidateEntity(identityId, "com.example", isPrimary = true),
                ProcessPackageCandidateEntity(identityId, "com.secondary", isPrimary = false),
            ),
        )
        database.sampleDao().insertProcessSamples(
            listOf(
                processSample("shared-uid", identityId),
                processSample("shared-uid", identityId).copy(
                    systemSampleSequence = 2L,
                    reasonKept = 1,
                ),
            ),
        )

        val secondary = database.sampleDao().targetSamples(
            sessionId = "shared-uid",
            kind = "PACKAGE",
            packageName = "com.secondary",
            processName = null,
            uid = null,
        ).single()
        val primary = database.sampleDao().targetSamples(
            sessionId = "shared-uid",
            kind = "PACKAGE",
            packageName = "com.example",
            processName = null,
            uid = null,
        ).single()
        val uidAggregate = database.sampleDao().targetSamples(
            sessionId = "shared-uid",
            kind = "UID",
            packageName = null,
            processName = null,
            uid = 10_123,
        ).single()

        assertEquals(1_000, secondary.cpuPercentBasisPoints)
        assertEquals(secondary.cpuPercentBasisPoints, primary.cpuPercentBasisPoints)
        assertEquals(primary.cpuPercentBasisPoints, uidAggregate.cpuPercentBasisPoints)
        assertEquals(
            "com.example|com.secondary",
            database.sampleDao().processRowsAt("shared-uid", 1L).single().packageCandidates,
        )

        database.sampleDao().updateIdentity(
            identityId = identityId,
            lastSeenOffsetMs = 1_000L,
            parentPid = 2,
            uid = null,
            packageName = null,
            packageCandidatesAvailable = false,
            processName = "",
            displayName = "",
            commandLine = "",
            isSystem = true,
            isNative = true,
        )
        val preserved = database.sampleDao().identities("shared-uid").single()
        assertEquals(10_123, preserved.uid)
        assertEquals("com.example", preserved.packageName)
        assertEquals("com.example", preserved.processName)
        assertEquals("Example", preserved.displayName)
        assertEquals("com.example", preserved.commandLine)
        assertEquals(false, preserved.isSystem)
        assertEquals(false, preserved.isNative)
        assertEquals(1_000L, preserved.lastSeenOffsetMs)

        database.sampleDao().updateIdentity(
            identityId = identityId,
            lastSeenOffsetMs = 1_500L,
            parentPid = 2,
            uid = 10_123,
            packageName = null,
            packageCandidatesAvailable = false,
            processName = "com.example",
            displayName = "com.example",
            commandLine = "com.example",
            isSystem = false,
            isNative = true,
        )
        val packageLookupMissing = database.sampleDao().identities("shared-uid").single()
        assertEquals("com.example", packageLookupMissing.packageName)
        assertEquals("Example", packageLookupMissing.displayName)
        assertEquals(false, packageLookupMissing.isNative)

        database.sampleDao().updateIdentity(
            identityId = identityId,
            lastSeenOffsetMs = 2_000L,
            parentPid = 2,
            uid = 10_123,
            packageName = null,
            packageCandidatesAvailable = true,
            processName = "shared.process",
            displayName = "shared.process",
            commandLine = "shared.process",
            isSystem = false,
            isNative = false,
        )
        val ambiguous = database.sampleDao().identities("shared-uid").single()
        assertNull(ambiguous.packageName)
        assertEquals("shared.process", ambiguous.displayName)
        assertEquals(false, ambiguous.isNative)
        assertEquals(2_000L, ambiguous.lastSeenOffsetMs)
    }

    @Test
    fun historicalTargetAggregateDoesNotPresentPartialMetricsAsComplete() = runBlocking {
        val capabilityId = database.sessionDao().insertCapabilityReport(capability())
        database.sessionDao().insertSession(session("partial-target", "COMPLETED", capabilityId))
        database.sampleDao().insertSystemSamples(listOf(systemSample("partial-target")))
        val firstIdentity = database.sampleDao().insertIdentity(identity("partial-target"))
        val secondIdentity = database.sampleDao().insertIdentity(
            identity("partial-target").copy(
                pid = 43,
                startTimeTicks = 430L,
                processName = "com.example:remote",
            ),
        )
        database.sampleDao().insertProcessSamples(
            listOf(
                processSample("partial-target", firstIdentity),
                processSample("partial-target", secondIdentity).copy(
                    cpuPercentBasisPoints = null,
                    rssKb = null,
                    pssKb = null,
                    rank = null,
                ),
            ),
        )

        val aggregate = database.sampleDao().targetSamples(
            sessionId = "partial-target",
            kind = "PACKAGE",
            packageName = "com.example",
            processName = null,
            uid = null,
        ).single()

        assertNull(aggregate.cpuPercentBasisPoints)
        assertNull(aggregate.rssKb)
        assertNull(aggregate.pssKb)
    }

    @Test
    fun elapsedDurationUsesMonotonicOffsetsAndNeverRegressesWithWallClockChanges() = runBlocking {
        val capabilityId = database.sessionDao().insertCapabilityReport(capability())
        database.sessionDao().insertSession(session("clock", "RUNNING", capabilityId))

        database.sessionDao().updateHeartbeatAndSummary(
            sessionId = "clock",
            wallTimeMs = 900L,
            elapsedOffsetMs = 5_000L,
            sequence = 1L,
            cpuBasisPoints = null,
            memoryAvailableKb = null,
            batteryTemperatureDeciC = null,
            thermalStatus = null,
        )
        database.sessionDao().updateSessionState(
            sessionId = "clock",
            status = "PAUSED",
            pauseReason = "USER",
            wallTimeMs = 800L,
            elapsedOffsetMs = 3_000L,
            terminal = false,
        )
        assertEquals(5_000L, database.sessionDao().session("clock")?.elapsedDurationMs)

        database.sessionDao().updateSessionState(
            sessionId = "clock",
            status = "COMPLETED",
            pauseReason = null,
            wallTimeMs = 700L,
            elapsedOffsetMs = 7_000L,
            terminal = true,
        )
        val completed = database.sessionDao().session("clock")
        assertEquals(7_000L, completed?.elapsedDurationMs)
        assertEquals(700L, completed?.endWallTimeMs)
    }

    @Test
    fun latestTimelineOffsetAdvancesRecoveryPastEventsRecordedWhilePaused() = runBlocking {
        val capabilityId = database.sessionDao().insertCapabilityReport(capability())
        database.sessionDao().insertSession(session("paused-timeline", "PAUSED", capabilityId))
        database.sampleDao().insertSystemSamples(
            listOf(systemSample("paused-timeline").copy(elapsedOffsetMs = 1_000L)),
        )
        database.sampleDao().insertEvents(
            listOf(event("paused-timeline").copy(elapsedOffsetMs = 5_000L)),
        )

        val terminalOffsetMs = database.sampleDao().latestElapsedOffsetMs("paused-timeline")
            ?: 0L
        database.sessionDao().updateSessionState(
            sessionId = "paused-timeline",
            status = "INTERRUPTED",
            pauseReason = null,
            wallTimeMs = 6_000L,
            elapsedOffsetMs = terminalOffsetMs,
            terminal = true,
        )

        val recovered = database.sessionDao().session("paused-timeline")
        assertEquals(5_000L, terminalOffsetMs)
        assertEquals(5_000L, recovered?.elapsedDurationMs)
        assertEquals(6_000L, recovered?.endWallTimeMs)
    }

    @Test
    fun terminalRecorderRollsBackEventWhenTerminalStateUpdateFails() = runBlocking {
        val recorder = RoomMonitorSessionRecorder(database)
        try {
            val start = HistorySessionStart(
                sessionId = "atomic-terminal",
                name = "Atomic terminal",
                startedWallTimeMillis = 1_000L,
                startedElapsedRealtimeNanos = 1_000_000_000L,
                bootId = "boot",
                preset = SamplingPreset.BALANCED,
                capabilityReport = capabilityReport(),
            )
            assertTrue(recorder.startSession(start))
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER reject_terminal_state
                BEFORE UPDATE OF status ON sessions
                WHEN NEW.status IN ('COMPLETED', 'INTERRUPTED')
                BEGIN
                    SELECT RAISE(ABORT, 'forced terminal rollback');
                END
                """.trimIndent(),
            )
            val terminalState = HistoryStateRecord(
                sessionId = start.sessionId,
                phase = MonitorPhase.COMPLETED,
                pauseReason = null,
                wallTimeMillis = 2_000L,
                elapsedOffsetMs = 1_000L,
            )
            val terminalEvent = HistoryEventRecord(
                sessionId = start.sessionId,
                sessionStartElapsedRealtimeNanos = start.startedElapsedRealtimeNanos,
                event = MonitorRuntimeEvent(
                    sequence = 1L,
                    type = MonitorRuntimeEventType.SESSION_COMPLETED,
                    wallTimeMillis = 2_000L,
                    elapsedRealtimeNanos = 2_000_000_000L,
                ),
            )

            assertTrue(recorder.updateTerminalState(terminalState, terminalEvent))
            assertTrue(!recorder.finishSession(terminalState))

            assertEquals(
                MonitorPhase.STARTING.name,
                database.sessionDao().session(start.sessionId)?.status,
            )
            assertTrue(
                database.sampleDao().observeEvents(start.sessionId).first().none {
                    it.type == MonitorRuntimeEventType.SESSION_COMPLETED.name
                },
            )
        } finally {
            recorder.close()
        }
    }

    @Test
    fun lonePendingFrameFlushesWithoutWaitingForAnotherSample() = runBlocking {
        val recorder = RoomMonitorSessionRecorder(database, maxBatchAgeMs = 25L)
        try {
            val start = HistorySessionStart(
                sessionId = "timer-flush",
                name = "Timer flush",
                startedWallTimeMillis = 1_000L,
                startedElapsedRealtimeNanos = 1_000_000_000L,
                bootId = "boot",
                preset = SamplingPreset.POWER_SAVER,
                capabilityReport = capabilityReport(),
            )
            assertTrue(recorder.startSession(start))
            assertTrue(
                recorder.recordFrame(
                    HistoryFrameRecord(
                        sessionId = start.sessionId,
                        sessionStartElapsedRealtimeNanos = start.startedElapsedRealtimeNanos,
                        timelineFrame = LiveTimelineFrame(
                            frame = MetricFrame(
                                sequence = 1L,
                                elapsedRealtimeNanos = 2_000_000_000L,
                                wallTimeMillis = 2_000L,
                                systemCpuPercentBasisPoints = 1_000,
                                memoryTotalKb = 8_000L,
                                memoryAvailableKb = 4_000L,
                                collectionDurationMs = 5L,
                                catalogRevision = 0L,
                                source = MetricDataSource.PROCFS,
                                frameFlags = 0,
                                metrics = emptyList(),
                            ),
                            environment = MonitorEnvironment(
                                appForeground = false,
                                screenInteractive = false,
                            ),
                            applications = emptyList(),
                            catalog = emptyList(),
                            packageResolutions = emptyList(),
                        ),
                        samplingIntervalMs = SamplingPreset.POWER_SAVER.backgroundIntervalMs,
                        retentionReasons = emptyMap(),
                    ),
                ),
            )

            val stored = withTimeout(5_000L) {
                database.sampleDao().observeSystemSamples(start.sessionId)
                    .first { it.isNotEmpty() }
            }
            assertEquals(1L, stored.single().sequence)
        } finally {
            recorder.close()
        }
    }

    private fun capability() = CapabilityReportEntity(
        probedAtWallTimeMs = 1L,
        quality = "AVAILABLE",
        metricCoverage = 1.0,
        backendMode = "ADB",
        reportJson = "{}",
    )

    private fun capabilityReport() = CapabilityReport(
        probedAtWallTimeMs = 1L,
        shizukuApiVersion = 13,
        shizukuUid = 2_000,
        shizukuSelinuxContext = "u:r:shell:s0",
        serviceUid = 2_000,
        servicePid = 42,
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
        pssProbeKb = 1L,
        pssProbeDurationMs = 1L,
        pssBatchProbeDurationMs = 1L,
        thermalZoneCount = 1,
        thermalReadableCount = 1,
        thermalSensorNames = listOf("battery"),
        mappedUidCount = 1,
        sampledUidCount = 1,
        packageCandidateCount = 1,
        procScanDurationMs = 1L,
        totalDurationMs = 1L,
        processListTruncated = false,
        errorFlags = 0,
        quality = CapabilityQuality.AVAILABLE,
    )

    private fun session(id: String, status: String, capabilityId: Long) = SessionEntity(
        id = id,
        name = id,
        status = status,
        startWallTimeMs = 1_000L,
        endWallTimeMs = if (status == "COMPLETED") 2_000L else null,
        startElapsedRealtimeNanos = 1_000_000_000L,
        bootId = "boot",
        samplingProfile = "BALANCED",
        deviceModel = "device",
        androidVersion = "16",
        romDisplay = "rom",
        procViewVersion = "1",
        shizukuVersion = "13",
        backendMode = "ADB",
        capabilityReportId = capabilityId,
        lastHeartbeatWallTimeMs = 2_000L,
    )

    private fun systemSample(sessionId: String) = SystemSampleEntity(
        sessionId = sessionId,
        sequence = 1L,
        elapsedOffsetMs = 0L,
        wallTimeMs = 1_000L,
        cpuPercentBasisPoints = 1_000,
        memoryTotalKb = 8_000L,
        memoryAvailableKb = 4_000L,
        batteryLevelPercent = 50,
        batteryTemperatureDeciC = 300,
        chargingState = "DISCHARGING",
        thermalStatus = 0,
        screenInteractive = true,
        samplingIntervalMs = 1_000L,
        collectionDurationMs = 5L,
        dataSource = "PROCFS",
        frameFlags = 0,
    )

    private fun identity(sessionId: String) = ProcessIdentityEntity(
        sessionId = sessionId,
        pid = 42,
        startTimeTicks = 420L,
        parentPid = 1,
        uid = 10_123,
        packageName = "com.example",
        processName = "com.example",
        displayName = "Example",
        commandLine = "com.example",
        isSystem = false,
        isNative = false,
        firstSeenOffsetMs = 0L,
        lastSeenOffsetMs = 0L,
    )

    private fun processSample(sessionId: String, identityId: Long) = ProcessSampleEntity(
        sessionId = sessionId,
        systemSampleSequence = 1L,
        processIdentityId = identityId,
        cpuPercentBasisPoints = 1_000,
        rssKb = 100L,
        pssKb = 80L,
        pssSampleElapsedRealtimeNanos = 1_000_000_000L,
        processState = "R",
        rank = 1,
        reasonKept = 1 or 4,
    )

    private fun event(sessionId: String) = SessionEventEntity(
        sessionId = sessionId,
        elapsedOffsetMs = 0L,
        wallTimeMs = 1_000L,
        type = "SESSION_STARTED",
    )
}
