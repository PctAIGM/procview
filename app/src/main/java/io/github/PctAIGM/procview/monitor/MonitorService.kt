package io.github.PctAIGM.procview.monitor

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.PctAIGM.procview.BuildConfig
import io.github.PctAIGM.procview.ProcViewApplication
import io.github.PctAIGM.procview.model.ShizukuPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var application: ProcViewApplication
    private lateinit var controller: MonitorSessionController
    private lateinit var environmentTracker: MonitorEnvironmentTracker
    private lateinit var wakeLock: MonitorWakeLock
    private lateinit var notificationFactory: MonitorNotificationFactory
    private lateinit var notificationManager: NotificationManager
    private var foregroundStarted = false
    private var runtimeInitialized = false
    private var stopping = false
    private var startOperationInProgress = false
    private var stopOperationInProgress = false
    private var startJob: Job? = null
    private var pauseRequestedWhileStarting = false
    private var stopRequestedWhileStarting = false
    private var lastStartId = 0
    private var lastNotificationElapsedMs = Long.MIN_VALUE
    private var lastNotificationFingerprint: NotificationFingerprint? = null
    private var pendingStartNotification: MonitorRuntimeSnapshot? = null

    override fun onCreate() {
        super.onCreate()
        application = applicationContext as ProcViewApplication
        notificationFactory = MonitorNotificationFactory(this)
        notificationFactory.createChannel()
        notificationManager = getSystemService(NotificationManager::class.java)
        // Enter the foreground before Room, Shizuku, and environment initialization can
        // consume Android's startForegroundService deadline on a slow or cold device.
        if (!promoteToForeground(startupNotificationSnapshot())) return
        wakeLock = MonitorWakeLock(this, serviceScope)
        environmentTracker = MonitorEnvironmentTracker(this).also { it.start() }
        controller = MonitorSessionController(
            backend = application.monitorBackend,
            packageResolver = application.packageResolver,
            store = application.monitorRuntimeStore,
            scope = serviceScope,
            onBackendFailure = application.shizukuCoordinator::refresh,
            allowPartialCapability = BuildConfig.DEBUG,
            pinnedTargets = { application.pinnedTargetStore.targets.value },
            recorder = application.sessionRecorder,
        )

        serviceScope.launch {
            environmentTracker.state.collect(controller::updateEnvironment)
        }
        serviceScope.launch {
            application.shizukuCoordinator.state.collect { state ->
                when (state.phase) {
                    ShizukuPhase.AVAILABLE,
                    ShizukuPhase.PARTIAL,
                    -> {
                        val report = state.report
                        // Keep the last capability report visible while idle, but do not
                        // mistake that cached report for a live UserService connection.
                        if (
                            report != null &&
                            application.shizukuCoordinator.connectedUserService() != null
                        ) {
                            controller.backendAvailable(report)
                            finishInterruptedSessionIfNeeded()
                        } else {
                            controller.backendUnavailable()
                        }
                    }
                    ShizukuPhase.NOT_INSTALLED,
                    ShizukuPhase.NOT_RUNNING,
                    ShizukuPhase.INCOMPATIBLE,
                    ShizukuPhase.PERMISSION_REQUIRED,
                    ShizukuPhase.PERMISSION_DENIED,
                    ShizukuPhase.ERROR,
                    -> controller.backendUnavailable()
                    ShizukuPhase.CHECKING,
                    ShizukuPhase.CONNECTING,
                    ShizukuPhase.PROBING,
                    -> Unit
                }
            }
        }
        serviceScope.launch {
            application.monitorRuntimeStore.state.collect(::handleRuntimeSnapshot)
        }
        serviceScope.launch {
            application.sessionRecorder.failures.collect { controller.storageFailed() }
        }
        serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                if (foregroundStarted) {
                    postNotification(
                        application.monitorRuntimeStore.state.value,
                        android.os.SystemClock.elapsedRealtime(),
                    )
                }
            }
        }
        runtimeInitialized = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!runtimeInitialized) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val action = intent?.action
        val current = application.monitorRuntimeStore.state.value
        val isNewStart = action == MonitorServiceActions.START &&
            !current.machineState.hasActiveSession &&
            !startOperationInProgress &&
            !stopOperationInProgress &&
            !stopping
        val notificationSnapshot = when {
            isNewStart -> current.copy(
                machineState = current.machineState.copy(
                    phase = MonitorPhase.STARTING,
                    pauseReason = null,
                ),
                sessionName = intent.getStringExtra(MonitorServiceActions.EXTRA_SESSION_NAME),
            )
            startOperationInProgress -> pendingStartNotification ?: current
            else -> current
        }
        if (isNewStart) pendingStartNotification = notificationSnapshot
        if (!promoteToForeground(notificationSnapshot)) return START_NOT_STICKY

        when (action) {
            MonitorServiceActions.START -> {
                if (
                    current.machineState.hasActiveSession ||
                    startOperationInProgress ||
                    stopOperationInProgress ||
                    stopping
                ) {
                    return START_NOT_STICKY
                }
                startOperationInProgress = true
                pauseRequestedWhileStarting = false
                stopRequestedWhileStarting = false
                val sessionName = intent.getStringExtra(MonitorServiceActions.EXTRA_SESSION_NAME)
                    .orEmpty()
                val preset = intent.getStringExtra(MonitorServiceActions.EXTRA_PRESET)
                    ?.let { value -> SamplingPreset.entries.firstOrNull { it.name == value } }
                    ?: SamplingPreset.BALANCED
                val pendingStart = serviceScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        if (!application.awaitSessionRecovery()) {
                            application.monitorRuntimeStore.update { previous ->
                                previous.copy(failure = MonitorFailure.STORAGE)
                            }
                            finishService(removeNotification = true)
                            return@launch
                        }
                        if (stopRequestedWhileStarting) {
                            finishService(removeNotification = true)
                            return@launch
                        }
                        val started = controller.startSession(sessionName, preset)
                        when {
                            !started -> finishService(removeNotification = true)
                            application.monitorRuntimeStore.state.value.machineState.phase ==
                                MonitorPhase.INTERRUPTED ->
                                finishInterruptedSessionIfNeeded()
                            stopRequestedWhileStarting -> {
                                stopOperationInProgress = true
                                try {
                                    if (controller.stopByUser()) {
                                        finishService(removeNotification = true)
                                    }
                                } finally {
                                    if (!stopping) stopOperationInProgress = false
                                }
                            }
                            pauseRequestedWhileStarting -> controller.pauseByUser()
                        }
                    } finally {
                        startJob = null
                        pendingStartNotification = null
                        pauseRequestedWhileStarting = false
                        stopRequestedWhileStarting = false
                        if (!stopping) startOperationInProgress = false
                    }
                }
                startJob = pendingStart
                pendingStart.start()
            }
            MonitorServiceActions.PAUSE -> when {
                stopOperationInProgress -> Unit
                startOperationInProgress -> pauseRequestedWhileStarting = true
                current.machineState.hasActiveSession -> {
                    serviceScope.launch { controller.pauseByUser() }
                }
                else -> finishService(removeNotification = true)
            }
            MonitorServiceActions.RESUME -> when {
                stopOperationInProgress -> Unit
                startOperationInProgress -> pauseRequestedWhileStarting = false
                current.machineState.hasActiveSession -> {
                    serviceScope.launch {
                        if (current.machineState.pauseReason == PauseReason.STORAGE) {
                            controller.storageRecovered()
                            finishInterruptedSessionIfNeeded()
                        } else {
                            controller.resumeByUser()
                        }
                    }
                }
                else -> finishService(removeNotification = true)
            }
            MonitorServiceActions.STOP -> when {
                stopOperationInProgress -> Unit
                startOperationInProgress -> {
                    stopRequestedWhileStarting = true
                    stopOperationInProgress = true
                    val pendingStart = startJob
                    serviceScope.launch {
                        try {
                            pendingStart?.cancel(UserStopDuringStartCancellation())
                            pendingStart?.join()
                            if (application.monitorRuntimeStore.state.value
                                    .machineState.hasActiveSession
                            ) {
                                if (controller.stopByUser()) {
                                    finishService(removeNotification = true)
                                }
                            } else {
                                finishService(removeNotification = true)
                            }
                        } finally {
                            if (!stopping) stopOperationInProgress = false
                        }
                    }
                }
                current.machineState.hasActiveSession -> {
                    stopOperationInProgress = true
                    serviceScope.launch {
                        try {
                            if (controller.stopByUser()) {
                                finishService(removeNotification = true)
                            }
                        } finally {
                            if (!stopping) stopOperationInProgress = false
                        }
                    }
                }
                else -> finishService(removeNotification = true)
            }
            else -> if (!current.machineState.hasActiveSession) {
                finishService(removeNotification = true)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::controller.isInitialized) {
            if (!stopping) controller.interruptNow()
            controller.close()
        }
        if (::environmentTracker.isInitialized) environmentTracker.close()
        if (::wakeLock.isInitialized) wakeLock.close()
        if (::application.isInitialized) {
            // Avoid lazily constructing Shizuku solely to tear it down when the early
            // foreground promotion itself failed before runtime initialization.
            if (runtimeInitialized) application.shizukuCoordinator.disconnectUserService()
            application.monitorRuntimeStore.update { previous -> previous.copy(wakeLockHeld = false) }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(snapshot: MonitorRuntimeSnapshot): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                MonitorNotificationFactory.NOTIFICATION_ID,
                notificationFactory.build(snapshot, android.os.SystemClock.elapsedRealtimeNanos()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
            foregroundStarted = true
            lastNotificationElapsedMs = android.os.SystemClock.elapsedRealtime()
            true
        } catch (_: RuntimeException) {
            application.monitorRuntimeStore.update { previous ->
                previous.copy(failure = MonitorFailure.FOREGROUND_SERVICE)
            }
            if (::controller.isInitialized) controller.interruptNow()
            stopping = true
            if (lastStartId > 0) stopSelf(lastStartId) else stopSelf()
            false
        }
    }

    private fun startupNotificationSnapshot(): MonitorRuntimeSnapshot {
        val current = application.monitorRuntimeStore.state.value
        if (current.machineState.hasActiveSession) return current
        return current.copy(
            machineState = current.machineState.copy(
                phase = MonitorPhase.STARTING,
                pauseReason = null,
            ),
        )
    }

    private fun handleRuntimeSnapshot(snapshot: MonitorRuntimeSnapshot) {
        val shouldHoldWakeLock = WakeLockPolicy.shouldHold(
            machineState = snapshot.machineState,
            preset = snapshot.preset,
            environment = snapshot.environment,
        )
        val held = wakeLock.setRequired(shouldHoldWakeLock)
        if (snapshot.wakeLockHeld != held) {
            application.monitorRuntimeStore.update { previous -> previous.copy(wakeLockHeld = held) }
            return
        }
        if (!foregroundStarted) return

        val nowMs = android.os.SystemClock.elapsedRealtime()
        val notificationSnapshot = notificationSnapshot(snapshot)
        val fingerprint = NotificationFingerprint.from(notificationSnapshot)
        val stateChanged = fingerprint != lastNotificationFingerprint
        val metricsDue = lastNotificationElapsedMs == Long.MIN_VALUE ||
            nowMs - lastNotificationElapsedMs >= NOTIFICATION_UPDATE_INTERVAL_MS
        if (stateChanged || metricsDue) {
            postNotification(notificationSnapshot, nowMs)
        }
    }

    private fun finishInterruptedSessionIfNeeded() {
        val snapshot = application.monitorRuntimeStore.state.value
        if (
            snapshot.machineState.phase == MonitorPhase.INTERRUPTED &&
            snapshot.failure != MonitorFailure.STORAGE &&
            !stopping
        ) {
            finishService(removeNotification = true)
        }
    }

    private fun postNotification(snapshot: MonitorRuntimeSnapshot, nowMs: Long) {
        val notificationSnapshot = notificationSnapshot(snapshot)
        runCatching {
            notificationManager.notify(
                MonitorNotificationFactory.NOTIFICATION_ID,
                notificationFactory.build(notificationSnapshot, nowMs * NANOS_PER_MILLISECOND),
            )
        }
        lastNotificationFingerprint = NotificationFingerprint.from(notificationSnapshot)
        lastNotificationElapsedMs = nowMs
    }

    private fun notificationSnapshot(snapshot: MonitorRuntimeSnapshot): MonitorRuntimeSnapshot =
        pendingStartNotification?.takeIf {
            startOperationInProgress &&
                (snapshot.machineState.phase == MonitorPhase.NOT_READY ||
                    snapshot.machineState.phase == MonitorPhase.READY)
        } ?: snapshot

    private fun finishService(removeNotification: Boolean) {
        if (stopping) return
        stopping = true
        wakeLock.setRequired(false)
        application.monitorRuntimeStore.update { previous -> previous.copy(wakeLockHeld = false) }
        if (foregroundStarted) {
            runCatching {
                ServiceCompat.stopForeground(
                    this,
                    if (removeNotification) {
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    } else {
                        ServiceCompat.STOP_FOREGROUND_DETACH
                    },
                )
            }
            foregroundStarted = false
        }
        stopSelf(lastStartId)
    }

    private data class NotificationFingerprint(
        val phase: MonitorPhase,
        val pauseReason: PauseReason?,
        val failure: MonitorFailure,
        val effectiveIntervalMs: Long,
        val wakeLockHeld: Boolean,
    ) {
        companion object {
            fun from(snapshot: MonitorRuntimeSnapshot) = NotificationFingerprint(
                phase = snapshot.machineState.phase,
                pauseReason = snapshot.machineState.pauseReason,
                failure = snapshot.failure,
                effectiveIntervalMs = snapshot.effectiveIntervalMs,
                wakeLockHeld = snapshot.wakeLockHeld,
            )
        }
    }

    companion object {
        fun start(context: Context, sessionName: String, preset: SamplingPreset): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java)
                        .setAction(MonitorServiceActions.START)
                        .putExtra(MonitorServiceActions.EXTRA_SESSION_NAME, sessionName)
                        .putExtra(MonitorServiceActions.EXTRA_PRESET, preset.name),
                )
                true
            }.getOrDefault(false)

        fun pause(context: Context): Boolean = runCatching {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(MonitorServiceActions.PAUSE),
            )
            true
        }.getOrDefault(false)

        fun resume(context: Context): Boolean = runCatching {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(MonitorServiceActions.RESUME),
            )
            true
        }.getOrDefault(false)

        fun stop(context: Context): Boolean = runCatching {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(MonitorServiceActions.STOP),
            )
            true
        }.getOrDefault(false)

        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 5_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
