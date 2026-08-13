package io.github.PctAIGM.procview

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import io.github.PctAIGM.procview.data.PinnedTargetStore
import io.github.PctAIGM.procview.data.HistoryRepository
import io.github.PctAIGM.procview.data.RoomMonitorSessionRecorder
import io.github.PctAIGM.procview.data.StorageMonitor
import io.github.PctAIGM.procview.data.db.ProcViewDatabase
import io.github.PctAIGM.procview.data.db.SessionEventEntity
import io.github.PctAIGM.procview.diagnostics.DiagnosticsExporter
import io.github.PctAIGM.procview.export.SessionExporter
import io.github.PctAIGM.procview.monitor.MonitorRuntimeStore
import io.github.PctAIGM.procview.monitor.MonitorNotificationFactory
import io.github.PctAIGM.procview.sampler.AndroidPackageResolver
import io.github.PctAIGM.procview.shizuku.ShizukuCoordinator
import io.github.PctAIGM.procview.shizuku.ShizukuProcBackend
import io.github.PctAIGM.procview.settings.AppLocaleController
import io.github.PctAIGM.procview.settings.UserSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ProcViewApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionRecovery = CompletableDeferred<Boolean>()
    val monitorRuntimeStore = MonitorRuntimeStore()
    val database: ProcViewDatabase by lazy {
        ProcViewDatabase.get(this)
    }
    val sessionRecorder: RoomMonitorSessionRecorder by lazy {
        RoomMonitorSessionRecorder(database)
    }
    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(
            context = this,
            database = database,
            canReclaimStorage = {
                !monitorRuntimeStore.state.value.machineState.hasActiveSession
            },
        )
    }
    val sessionExporter: SessionExporter by lazy {
        SessionExporter(this, database)
    }
    val diagnosticsExporter: DiagnosticsExporter by lazy {
        DiagnosticsExporter(this)
    }
    val storageMonitor: StorageMonitor by lazy {
        StorageMonitor(this, historyRepository)
    }
    val pinnedTargetStore: PinnedTargetStore by lazy {
        PinnedTargetStore(database.pinnedTargetDao())
    }
    val userSettingsStore: UserSettingsStore by lazy {
        UserSettingsStore(this)
    }
    val shizukuCoordinator: ShizukuCoordinator by lazy {
        ShizukuCoordinator(this)
    }
    val monitorBackend: ShizukuProcBackend by lazy {
        ShizukuProcBackend(shizukuCoordinator)
    }
    val packageResolver: AndroidPackageResolver by lazy {
        AndroidPackageResolver(packageManager)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleController.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLocaleController.applyPlatformLocale(this)
        applicationScope.launch {
            userSettingsStore.settings
                .map { it.language }
                .distinctUntilChanged()
                .collect { language ->
                    AppLocaleController.remember(this@ProcViewApplication, language)
                    MonitorNotificationFactory(this@ProcViewApplication).createChannel()
                }
        }
        applicationScope.launch {
            val recovered = try {
                recoverInterruptedSessions()
                true
            } catch (cancellation: CancellationException) {
                sessionRecovery.complete(false)
                throw cancellation
            } catch (_: Exception) {
                // A new session must not start until every previously open session has been
                // moved to a durable terminal state. The UI exposes this as a storage failure.
                false
            }
            sessionRecovery.complete(recovered)
            if (!recovered) return@launch

            try {
                database.sessionDao().sessionsNeedingSummaryRepair().forEach { sessionId ->
                    try {
                        database.withTransaction {
                            database.sampleDao().deleteSummaries(sessionId)
                            database.sampleDao().rebuildSummaries(sessionId)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Summaries are derived data. Keep the recovered terminal state durable
                        // and retry this session on the next application startup.
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Summary rows are derived and can be retried at the next startup without
                // blocking a newly requested monitoring session.
            }
        }
    }

    private suspend fun recoverInterruptedSessions() {
        database.withTransaction {
            val openSessions = database.sessionDao().openSessions()
            if (openSessions.isEmpty()) return@withTransaction
            val interruptedAt = System.currentTimeMillis()
            val terminalEvents = openSessions.map { session ->
                val terminalOffsetMs = maxOf(
                    session.elapsedDurationMs,
                    database.sampleDao().latestElapsedOffsetMs(session.id) ?: 0L,
                )
                database.sessionDao().updateSessionState(
                    sessionId = session.id,
                    status = "INTERRUPTED",
                    pauseReason = null,
                    wallTimeMs = interruptedAt,
                    elapsedOffsetMs = terminalOffsetMs,
                    terminal = true,
                )
                SessionEventEntity(
                    sessionId = session.id,
                    elapsedOffsetMs = terminalOffsetMs,
                    wallTimeMs = interruptedAt,
                    type = "SESSION_INTERRUPTED",
                    payloadJson = "{\"reason\":\"process_restart\"}",
                )
            }
            database.sampleDao().insertEvents(terminalEvents)
        }
    }

    suspend fun awaitSessionRecovery(): Boolean = sessionRecovery.await()
}
