package io.github.PctAIGM.procview.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.net.toUri
import io.github.PctAIGM.procview.BuildConfig
import io.github.PctAIGM.procview.model.BackendMode
import io.github.PctAIGM.procview.model.CapabilityQuality
import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.ShizukuFailure
import io.github.PctAIGM.procview.model.ShizukuPhase
import io.github.PctAIGM.procview.model.ShizukuUiState
import io.github.PctAIGM.procview.shizuku.ipc.CapabilityProbeParcel
import io.github.PctAIGM.procview.shizuku.ipc.IProcViewUserService
import io.github.PctAIGM.procview.shizuku.user.ProcViewUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class ShizukuCoordinator(context: Context) {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ShizukuUiState.Checking)
    val state: StateFlow<ShizukuUiState> = _state.asStateFlow()

    @Volatile
    private var userService: IProcViewUserService? = null
    private var binding = false
    private var acceptsUserServiceConnections = true
    private var bindTimeoutJob: Job? = null
    private var rebindJob: Job? = null
    private var probeJob: Job? = null
    private var lastCapabilityReport: CapabilityReport? = null
    private val rebindBackoff = RetryBackoff(
        initialDelayMs = INITIAL_REBIND_DELAY_MS,
        maximumDelayMs = MAX_REBIND_DELAY_MS,
    )
    private var generation = 0L

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ProcViewUserService::class.java.name),
    )
        .daemon(false)
        .tag(USER_SERVICE_TAG)
        .processNameSuffix(USER_SERVICE_PROCESS_SUFFIX)
        .debuggable(BuildConfig.DEBUG)
        .version(USER_SERVICE_VERSION)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (acceptsUserServiceConnections) {
            evaluateBinderState(forceProbe = false)
        } else {
            // A manager restart must not resurrect the non-daemon UserService after a
            // completed session. Keep the cached capability visible until an explicit
            // refresh or the next session preflight re-enables connections.
            _state.value = disconnectedIdleState()
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        generation++
        binding = false
        bindTimeoutJob?.cancel()
        cancelRebind(resetAttempt = true)
        probeJob?.cancel()
        probeJob = null
        userService = null
        _state.value = ShizukuUiState(phase = ShizukuPhase.NOT_RUNNING)
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) {
            acceptsUserServiceConnections = true
            evaluateBinderState(forceProbe = true)
        } else {
            _state.value = ShizukuUiState(phase = ShizukuPhase.PERMISSION_DENIED)
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            binding = false
            bindTimeoutJob?.cancel()
            if (!acceptsUserServiceConnections) {
                runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
                return
            }
            if (binder == null || !binder.pingBinder()) {
                userService = null
                _state.value = ShizukuUiState(
                    phase = ShizukuPhase.ERROR,
                    failure = ShizukuFailure.INVALID_BINDER,
                )
                scheduleRebind()
                return
            }

            val service = IProcViewUserService.Stub.asInterface(binder)
            val protocol = runCatching { service.protocolVersion }.getOrNull()
            if (protocol != USER_SERVICE_VERSION) {
                // Destroy the incompatible endpoint instead of leaving the connection bound.
                // Suppress the resulting disconnect callback so it cannot overwrite the
                // actionable protocol error or enter an automatic rebind loop.
                acceptsUserServiceConnections = false
                userService = null
                lastCapabilityReport = null
                runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
                _state.value = ShizukuUiState(
                    phase = ShizukuPhase.ERROR,
                    failure = ShizukuFailure.PROTOCOL_MISMATCH,
                )
                return
            }
            cancelRebind(resetAttempt = true)
            userService = service
            runProbe()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            generation++
            binding = false
            probeJob?.cancel()
            probeJob = null
            userService = null
            if (!acceptsUserServiceConnections) return
            _state.value = if (Shizuku.pingBinder()) {
                ShizukuUiState(
                    phase = ShizukuPhase.ERROR,
                    failure = ShizukuFailure.BIND_FAILED,
                )
            } else {
                ShizukuUiState(phase = ShizukuPhase.NOT_RUNNING)
            }
            if (Shizuku.pingBinder()) scheduleRebind()
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        evaluateBinderState(forceProbe = false)
    }

    fun refresh() {
        acceptsUserServiceConnections = true
        cancelRebind(resetAttempt = true)
        evaluateBinderState(forceProbe = true)
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            _state.value = ShizukuUiState(phase = ShizukuPhase.NOT_RUNNING)
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure {
                _state.value = ShizukuUiState(
                    phase = ShizukuPhase.ERROR,
                    failure = ShizukuFailure.PROBE_FAILED,
                )
            }
    }

    fun cancelProbe() {
        if (_state.value.phase != ShizukuPhase.PROBING) return
        generation++
        probeJob?.cancel()
        probeJob = null
        _state.value = ShizukuUiState(
            phase = ShizukuPhase.ERROR,
            shizukuApiVersion = runCatching { Shizuku.getVersion() }.getOrNull(),
            failure = ShizukuFailure.PROBE_CANCELLED,
        )
    }

    fun openShizukuOrDownload(): Boolean {
        val managerIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
        val intent = managerIntent ?: Intent(
            Intent.ACTION_VIEW,
            SHIZUKU_DOWNLOAD_URL.toUri(),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            applicationContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    internal fun connectedUserService(): IProcViewUserService? = userService
        ?.takeIf { service -> service.asBinder().pingBinder() }

    fun disconnectUserService() {
        generation++
        binding = false
        acceptsUserServiceConnections = false
        bindTimeoutJob?.cancel()
        bindTimeoutJob = null
        cancelRebind(resetAttempt = true)
        probeJob?.cancel()
        probeJob = null
        userService = null
        if (Shizuku.pingBinder()) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        }
        _state.value = disconnectedIdleState()
    }

    private fun evaluateBinderState(forceProbe: Boolean) {
        if (!Shizuku.pingBinder()) {
            _state.value = ShizukuUiState(
                phase = if (isShizukuInstalled()) ShizukuPhase.NOT_RUNNING else ShizukuPhase.NOT_INSTALLED,
            )
            return
        }

        val apiVersion = runCatching { Shizuku.getVersion() }.getOrNull()
        if (Shizuku.isPreV11() || apiVersion == null || apiVersion < MIN_SHIZUKU_API_VERSION) {
            _state.value = ShizukuUiState(
                phase = ShizukuPhase.INCOMPATIBLE,
                shizukuApiVersion = apiVersion,
                failure = ShizukuFailure.API_TOO_OLD,
            )
            return
        }

        val permission = runCatching { Shizuku.checkSelfPermission() }.getOrNull()
        if (permission != PackageManager.PERMISSION_GRANTED) {
            val denied = runCatching { Shizuku.shouldShowRequestPermissionRationale() }
                .getOrDefault(false)
            _state.value = ShizukuUiState(
                phase = if (denied) ShizukuPhase.PERMISSION_DENIED else ShizukuPhase.PERMISSION_REQUIRED,
                shizukuApiVersion = apiVersion,
            )
            return
        }

        if (!acceptsUserServiceConnections) {
            _state.value = disconnectedIdleState()
            return
        }

        val currentService = userService
        if (currentService != null && currentService.asBinder().pingBinder()) {
            if (forceProbe || state.value.report == null) runProbe()
            return
        }
        bindUserService(apiVersion)
    }

    private fun bindUserService(apiVersion: Int) {
        if (binding || !acceptsUserServiceConnections) return
        binding = true
        _state.value = ShizukuUiState(
            phase = ShizukuPhase.CONNECTING,
            shizukuApiVersion = apiVersion,
            report = lastCapabilityReport,
        )
        val bindGeneration = ++generation
        runCatching { Shizuku.bindUserService(userServiceArgs, serviceConnection) }
            .onFailure {
                binding = false
                _state.value = ShizukuUiState(
                    phase = ShizukuPhase.ERROR,
                    shizukuApiVersion = apiVersion,
                    failure = ShizukuFailure.BIND_FAILED,
                )
                scheduleRebind()
            }
        if (!binding) return
        bindTimeoutJob = scope.launch {
            delay(BIND_TIMEOUT_MS)
            if (binding && generation == bindGeneration) {
                binding = false
                _state.value = ShizukuUiState(
                    phase = ShizukuPhase.ERROR,
                    shizukuApiVersion = apiVersion,
                    failure = ShizukuFailure.BIND_TIMEOUT,
                )
                scheduleRebind()
            }
        }
    }

    private fun scheduleRebind() {
        if (!acceptsUserServiceConnections || rebindJob?.isActive == true) return
        val delayMs = rebindBackoff.takeNextDelayMs()
        rebindJob = scope.launch {
            delay(delayMs)
            rebindJob = null
            if (!acceptsUserServiceConnections) return@launch
            binding = false
            evaluateBinderState(forceProbe = false)
        }
    }

    private fun cancelRebind(resetAttempt: Boolean) {
        rebindJob?.cancel()
        rebindJob = null
        if (resetAttempt) rebindBackoff.reset()
    }

    private fun runProbe() {
        val service = userService ?: return
        probeJob?.cancel()
        val probeGeneration = ++generation
        _state.value = ShizukuUiState(
            phase = ShizukuPhase.PROBING,
            shizukuApiVersion = runCatching { Shizuku.getVersion() }.getOrNull(),
            report = lastCapabilityReport,
        )
        probeJob = scope.launch {
            try {
                val parcel = withContext(Dispatchers.IO) { service.runCapabilityProbe() }
                if (probeGeneration != generation) return@launch
                val report = withContext(Dispatchers.IO) { parcel.toDomainReport() }
                if (probeGeneration != generation) return@launch
                cancelRebind(resetAttempt = true)
                lastCapabilityReport = report
                _state.value = ShizukuUiState(
                    phase = if (report.quality == CapabilityQuality.AVAILABLE) {
                        ShizukuPhase.AVAILABLE
                    } else {
                        ShizukuPhase.PARTIAL
                    },
                    shizukuApiVersion = report.shizukuApiVersion,
                    report = report,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (probeGeneration == generation) {
                    _state.value = ShizukuUiState(
                        phase = ShizukuPhase.ERROR,
                        shizukuApiVersion = runCatching { Shizuku.getVersion() }.getOrNull(),
                        failure = ShizukuFailure.PROBE_FAILED,
                    )
                }
            } finally {
                if (probeGeneration == generation) probeJob = null
            }
        }
    }

    private fun CapabilityProbeParcel.toDomainReport(): CapabilityReport {
        val sampled = sampledUids?.distinct().orEmpty()
        var mappedUidCount = 0
        var packageCandidateCount = 0
        sampled.forEach { uid ->
            val packages = runCatching { packageManager.getPackagesForUid(uid) }
                .getOrNull()
                .orEmpty()
            if (packages.isNotEmpty()) mappedUidCount++
            packageCandidateCount += packages.size
        }

        val serverUid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val apiVersion = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        val selinuxContext = runCatching { Shizuku.getSELinuxContext() }.getOrNull().orEmpty()
        val available = CapabilityReleaseGate.isAvailable(
            CapabilityGateInput(
                protocolVersion = protocolVersion,
                expectedProtocolVersion = USER_SERVICE_VERSION,
                procStatReadable = procStatReadable,
                procMeminfoReadable = procMeminfoReadable,
                bootIdReadable = bootIdReadable,
                bootId = bootId,
                psCommandAvailable = psCommandAvailable,
                psPidCount = psPidCount,
                effectiveCpuAndRssReadableCount = cpuAndRssReadableCount,
                pssCommandAvailable = pssCommandAvailable,
                pssValueParsed = pssValueParsed,
                pssReadableCount = pssReadableCount,
                selectedProcessListTruncated = processListTruncated,
                psFallbackSelected = psFallbackSelected,
                psSnapshotAvailable = psSnapshotAvailable,
                psSnapshotPidCount = psSnapshotPidCount,
                psSnapshotCpuAndRssReadableCount = psSnapshotCpuAndRssReadableCount,
            ),
        )

        return CapabilityReport(
            probedAtWallTimeMs = probeStartedWallTimeMs,
            shizukuApiVersion = apiVersion,
            shizukuUid = serverUid,
            shizukuSelinuxContext = selinuxContext,
            serviceUid = serviceUid,
            servicePid = servicePid,
            backendMode = when (serverUid) {
                ROOT_UID -> BackendMode.ROOT
                SHELL_UID -> BackendMode.ADB
                else -> BackendMode.UNKNOWN
            },
            protocolVersion = protocolVersion,
            bootId = bootId,
            procStatReadable = procStatReadable,
            procMeminfoReadable = procMeminfoReadable,
            procPidCount = procPidCount,
            psPidCount = psPidCount,
            statReadableCount = statReadableCount,
            statusReadableCount = statusReadableCount,
            cmdlineReadableCount = cmdlineReadableCount,
            rssReadableCount = rssReadableCount,
            cpuAndRssReadableCount = cpuAndRssReadableCount,
            pid1StatReadable = pid1StatReadable,
            psCommandAvailable = psCommandAvailable,
            pssCommandAvailable = pssCommandAvailable,
            pssValueParsed = pssValueParsed,
            pssReadableCount = pssReadableCount.coerceAtLeast(0),
            pssProbeKb = pssProbeKb.takeIf { pssValueParsed && it >= 0 },
            pssProbeDurationMs = pssProbeDurationMs,
            pssBatchProbeDurationMs = pssBatchProbeDurationMs,
            thermalZoneCount = thermalZoneCount,
            thermalReadableCount = thermalReadableCount,
            thermalSensorNames = thermalSensorNames?.filter(String::isNotBlank).orEmpty(),
            mappedUidCount = mappedUidCount,
            sampledUidCount = sampled.size,
            packageCandidateCount = packageCandidateCount,
            procScanDurationMs = procScanDurationMs,
            totalDurationMs = totalDurationMs,
            processListTruncated = processListTruncated,
            errorFlags = errorFlags,
            quality = if (available) CapabilityQuality.AVAILABLE else CapabilityQuality.PARTIAL,
            psSnapshotAvailable = psSnapshotAvailable,
            psSnapshotPidCount = psSnapshotPidCount.coerceAtLeast(0),
            psSnapshotCpuAndRssReadableCount =
                psSnapshotCpuAndRssReadableCount.coerceAtLeast(0),
            psSnapshotDurationMs = psSnapshotDurationMs.coerceAtLeast(0L),
            psFallbackSelected = psFallbackSelected,
        )
    }

    @Suppress("DEPRECATION")
    private fun isShizukuInstalled(): Boolean {
        return runCatching {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        }.getOrDefault(false)
    }

    private fun disconnectedIdleState(): ShizukuUiState {
        if (!Shizuku.pingBinder()) {
            return ShizukuUiState(
                phase = if (isShizukuInstalled()) {
                    ShizukuPhase.NOT_RUNNING
                } else {
                    ShizukuPhase.NOT_INSTALLED
                },
            )
        }
        val apiVersion = runCatching { Shizuku.getVersion() }.getOrNull()
        if (Shizuku.isPreV11() || apiVersion == null || apiVersion < MIN_SHIZUKU_API_VERSION) {
            return ShizukuUiState(
                phase = ShizukuPhase.INCOMPATIBLE,
                shizukuApiVersion = apiVersion,
                failure = ShizukuFailure.API_TOO_OLD,
            )
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val denied = runCatching { Shizuku.shouldShowRequestPermissionRationale() }
                .getOrDefault(false)
            return ShizukuUiState(
                phase = if (denied) {
                    ShizukuPhase.PERMISSION_DENIED
                } else {
                    ShizukuPhase.PERMISSION_REQUIRED
                },
                shizukuApiVersion = apiVersion,
            )
        }
        val report = lastCapabilityReport
        return if (report != null) {
            ShizukuUiState(
                phase = if (report.quality == CapabilityQuality.AVAILABLE) {
                    ShizukuPhase.AVAILABLE
                } else {
                    ShizukuPhase.PARTIAL
                },
                shizukuApiVersion = apiVersion,
                report = report,
            )
        } else {
            ShizukuUiState(
                phase = ShizukuPhase.ERROR,
                shizukuApiVersion = apiVersion,
                failure = ShizukuFailure.BIND_FAILED,
            )
        }
    }

    private companion object {
        const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        const val USER_SERVICE_TAG = "procview-monitor"
        const val USER_SERVICE_PROCESS_SUFFIX = "monitor"
        const val USER_SERVICE_VERSION = 4
        const val MIN_SHIZUKU_API_VERSION = 13
        const val PERMISSION_REQUEST_CODE = 10_031
        const val BIND_TIMEOUT_MS = 10_000L
        const val INITIAL_REBIND_DELAY_MS = 1_000L
        const val MAX_REBIND_DELAY_MS = 30_000L
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
    }
}
