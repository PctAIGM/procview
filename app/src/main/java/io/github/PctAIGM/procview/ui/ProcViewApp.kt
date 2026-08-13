package io.github.PctAIGM.procview.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.PctAIGM.procview.ProcViewApplication
import io.github.PctAIGM.procview.R
import io.github.PctAIGM.procview.BuildConfig
import io.github.PctAIGM.procview.diagnostics.shareCapabilityReport
import io.github.PctAIGM.procview.data.StorageHealth
import io.github.PctAIGM.procview.monitor.MonitorPhase
import io.github.PctAIGM.procview.monitor.MonitorFailure
import io.github.PctAIGM.procview.monitor.MonitorRuntimeSnapshot
import io.github.PctAIGM.procview.monitor.MonitorService
import io.github.PctAIGM.procview.monitor.PauseReason
import io.github.PctAIGM.procview.monitor.SamplingPreset
import io.github.PctAIGM.procview.monitor.SessionMachineState
import io.github.PctAIGM.procview.model.BackendMode
import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.MetricDataSource
import io.github.PctAIGM.procview.model.PinnedTarget
import io.github.PctAIGM.procview.model.ShizukuFailure
import io.github.PctAIGM.procview.model.ShizukuPhase
import io.github.PctAIGM.procview.model.ShizukuUiState
import io.github.PctAIGM.procview.ui.live.LiveDashboard
import io.github.PctAIGM.procview.ui.history.HistoryScreen
import io.github.PctAIGM.procview.ui.settings.SettingsScreen
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class MainDestination(
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Live(R.string.nav_live, Icons.Filled.MonitorHeart, Icons.Outlined.MonitorHeart),
    History(R.string.nav_history, Icons.Filled.History, Icons.Outlined.History),
    Settings(R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcViewApp(modifier: Modifier = Modifier) {
    val application = LocalContext.current.applicationContext as ProcViewApplication
    val probeViewModel: ShizukuProbeViewModel = viewModel(
        factory = ShizukuProbeViewModel.Factory(
            coordinator = application.shizukuCoordinator,
            backend = application.monitorBackend,
            packageResolver = application.packageResolver,
        ),
    )
    val shizukuState by probeViewModel.state.collectAsStateWithLifecycle()
    val samplingPreview by probeViewModel.samplingPreview.collectAsStateWithLifecycle()
    val monitorSnapshot by application.monitorRuntimeStore.state.collectAsStateWithLifecycle()
    val pinnedTargets by application.pinnedTargetStore.targets.collectAsStateWithLifecycle()
    val userSettings by application.userSettingsStore.settings.collectAsStateWithLifecycle(
        initialValue = io.github.PctAIGM.procview.settings.UserSettings(),
    )
    val storageHealth by produceState(
        initialValue = StorageHealth.Empty,
        userSettings.storageWarningMegabytes,
    ) {
        while (isActive) {
            value = try {
                application.storageMonitor.snapshot(userSettings.storageWarningMegabytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                StorageHealth.Empty
            }
            delay(STORAGE_HEALTH_REFRESH_MS)
        }
    }
    val uiScope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(MainDestination.Live) }
    val destinationStateHolder = rememberSaveableStateHolder()
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    var monitorStartFailed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val defaultSessionName = stringResource(R.string.default_session_name)
    var showStartDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSessionName by rememberSaveable { mutableStateOf(defaultSessionName) }
    var pendingPresetName by rememberSaveable {
        mutableStateOf(userSettings.samplingPreset.name)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionDenied = !granted
        monitorStartFailed = !MonitorService.start(
            context,
            pendingSessionName,
            SamplingPreset.entries.firstOrNull { it.name == pendingPresetName }
                ?: SamplingPreset.BALANCED,
        )
    }
    val startSession = { sessionName: String, preset: SamplingPreset ->
        pendingSessionName = sessionName.trim().take(80).ifBlank { defaultSessionName }
        pendingPresetName = preset.name
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionDenied = false
            monitorStartFailed = !MonitorService.start(
                context,
                pendingSessionName,
                preset,
            )
        }
    }
    val title = stringResource(destination.label)
    val partialInternalBuild = BuildConfig.DEBUG && shizukuState.phase == ShizukuPhase.PARTIAL
    val sessionBackendReady = shizukuState.phase == ShizukuPhase.AVAILABLE || partialInternalBuild

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                    if (storageHealth.warningActive) {
                        GlobalStorageWarning(
                            storage = storageHealth,
                            onManage = { destination = MainDestination.Settings },
                        )
                    }
                }
            },
            bottomBar = {
                if (!useNavigationRail) {
                    MainNavigationBar(
                        destination = destination,
                        onDestinationSelected = { destination = it },
                    )
                }
            },
        ) { contentPadding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (useNavigationRail) {
                    MainNavigationRail(
                        destination = destination,
                        onDestinationSelected = { destination = it },
                        modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    destinationStateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            MainDestination.Live -> LiveBaseline(
                                contentPadding = contentPadding,
                                shizukuState = shizukuState,
                                samplingPreview = samplingPreview,
                                monitorSnapshot = monitorSnapshot,
                                notificationPermissionDenied = notificationPermissionDenied ||
                                    (monitorSnapshot.machineState.hasActiveSession &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED),
                                monitorStartFailed = monitorStartFailed ||
                                    monitorSnapshot.failure == MonitorFailure.FOREGROUND_SERVICE,
                                monitorStorageFailed =
                                    monitorSnapshot.failure == MonitorFailure.STORAGE &&
                                        !monitorSnapshot.machineState.hasActiveSession,
                                sessionBackendReady = sessionBackendReady,
                                partialInternalBuild = partialInternalBuild,
                                pinnedTargets = pinnedTargets,
                                onPrimaryAction = probeViewModel::performPrimaryAction,
                                onStartSession = {
                                    pendingPresetName = userSettings.samplingPreset.name
                                    showStartDialog = true
                                },
                                onPauseSession = { MonitorService.pause(context) },
                                onResumeSession = { MonitorService.resume(context) },
                                onStopSession = { MonitorService.stop(context) },
                                onTogglePin = { target ->
                                    uiScope.launch { application.pinnedTargetStore.toggle(target) }
                                },
                                onDetailProcessKeysChanged =
                                    application.monitorRuntimeStore::setDetailProcessKeys,
                            )
                            MainDestination.History -> HistoryScreen(
                                contentPadding = contentPadding,
                                repository = application.historyRepository,
                                exporter = application.sessionExporter,
                                settingsStore = application.userSettingsStore,
                                pinnedTargets = pinnedTargets,
                                coroutineScope = uiScope,
                            )
                            MainDestination.Settings -> SettingsScreen(
                                contentPadding = contentPadding,
                                settingsStore = application.userSettingsStore,
                                historyRepository = application.historyRepository,
                                storageMonitor = application.storageMonitor,
                                diagnosticsExporter = application.diagnosticsExporter,
                                pinnedTargets = pinnedTargets,
                                capabilityReport = shizukuState.report,
                                diagnosticsContent = {
                                    StatusCard(
                                        state = shizukuState,
                                        onPrimaryAction = probeViewModel::performPrimaryAction,
                                    )
                                    shizukuState.report?.let { report ->
                                        CapabilityReportCard(
                                            report = report,
                                            onShare = { shareCapabilityReport(context, report) },
                                        )
                                    }
                                    if (BuildConfig.DEBUG) {
                                        SamplingPreviewCard(
                                            state = samplingPreview,
                                            enabled = (shizukuState.phase == ShizukuPhase.AVAILABLE ||
                                                shizukuState.phase == ShizukuPhase.PARTIAL) &&
                                                !monitorSnapshot.machineState.hasActiveSession,
                                            onRun = probeViewModel::runSamplingPreview,
                                        )
                                    }
                                },
                                onRemovePin = { target ->
                                    uiScope.launch { application.pinnedTargetStore.remove(target) }
                                },
                                onOpenHistory = { destination = MainDestination.History },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showStartDialog) {
        SessionStartDialog(
            initialName = pendingSessionName,
            initialPreset = SamplingPreset.entries.firstOrNull { it.name == pendingPresetName }
                ?: SamplingPreset.BALANCED,
            onDismiss = { showStartDialog = false },
            onStart = { name, preset ->
                showStartDialog = false
                startSession(name, preset)
            },
        )
    }
}

@Composable
private fun GlobalStorageWarning(
    storage: StorageHealth,
    onManage: () -> Unit,
) {
    val message = if (storage.deviceLow) {
        stringResource(R.string.settings_device_storage_warning, storage.availablePercent)
    } else {
        stringResource(
            R.string.settings_app_storage_warning,
            formatStorageBytes(storage.databaseBytes),
            formatStorageBytes(storage.warningBytes),
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onManage) { Text(stringResource(R.string.action_manage)) }
        }
    }
}

private fun formatStorageBytes(value: Long): String = when {
    value >= 1_073_741_824L -> String.format(
        java.util.Locale.getDefault(),
        "%.2f GB",
        value / 1_073_741_824.0,
    )
    value >= 1_048_576L -> String.format(
        java.util.Locale.getDefault(),
        "%.1f MB",
        value / 1_048_576.0,
    )
    value >= 1_024L -> String.format(
        java.util.Locale.getDefault(),
        "%.1f KB",
        value / 1_024.0,
    )
    else -> "$value B"
}

private const val STORAGE_HEALTH_REFRESH_MS = 30_000L

@Composable
private fun SessionStartDialog(
    initialName: String,
    initialPreset: SamplingPreset,
    onDismiss: () -> Unit,
    onStart: (String, SamplingPreset) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var presetName by rememberSaveable(initialPreset.name) { mutableStateOf(initialPreset.name) }
    val preset = SamplingPreset.entries.firstOrNull { it.name == presetName }
        ?: SamplingPreset.BALANCED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_start_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.session_name_label)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.session_name_counter, name.length, 80))
                    },
                )
                Text(
                    text = stringResource(R.string.session_preset_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                SamplingPreset.entries.forEach { item ->
                    FilterChip(
                        selected = preset == item,
                        onClick = { presetName = item.name },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        label = {
                            Column {
                                Text(stringResource(item.titleResource()))
                                Text(
                                    text = stringResource(
                                        R.string.session_preset_cadence,
                                        item.foregroundIntervalMs / 1_000.0,
                                        item.backgroundIntervalMs / 1_000.0,
                                        item.pssIntervalMs / 1_000,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                    )
                }
                Text(
                    text = stringResource(
                        if (preset.holdsScreenOffWakeLock) {
                            R.string.session_wake_lock_enabled
                        } else {
                            R.string.session_wake_lock_disabled
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onStart(name, preset) }) {
                Text(stringResource(R.string.action_start_session_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@StringRes
private fun SamplingPreset.titleResource(): Int = when (this) {
    SamplingPreset.FINE -> R.string.session_preset_fine
    SamplingPreset.BALANCED -> R.string.session_preset_balanced
    SamplingPreset.POWER_SAVER -> R.string.session_preset_power_saver
}

@Composable
private fun MainNavigationBar(
    destination: MainDestination,
    onDestinationSelected: (MainDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
    ) {
        MainDestination.entries.forEach { item ->
            val selected = destination == item
            NavigationBarItem(
                selected = selected,
                onClick = { onDestinationSelected(item) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(item.label)) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun MainNavigationRail(
    destination: MainDestination,
    onDestinationSelected: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        MainDestination.entries.forEach { item ->
            val selected = destination == item
            NavigationRailItem(
                selected = selected,
                onClick = { onDestinationSelected(item) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(item.label)) },
                colors = NavigationRailItemDefaults.colors(indicatorColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun LiveBaseline(
    contentPadding: PaddingValues,
    shizukuState: ShizukuUiState,
    samplingPreview: SamplingPreviewState,
    monitorSnapshot: MonitorRuntimeSnapshot,
    notificationPermissionDenied: Boolean,
    monitorStartFailed: Boolean,
    monitorStorageFailed: Boolean,
    sessionBackendReady: Boolean,
    partialInternalBuild: Boolean,
    pinnedTargets: Set<PinnedTarget>,
    onPrimaryAction: () -> Unit,
    onStartSession: () -> Unit,
    onPauseSession: () -> Unit,
    onResumeSession: () -> Unit,
    onStopSession: () -> Unit,
    onTogglePin: (PinnedTarget) -> Unit,
    onDetailProcessKeysChanged: (Set<io.github.PctAIGM.procview.model.ProcessKey>) -> Unit,
) {
    LiveDashboard(
        contentPadding = contentPadding,
        snapshot = monitorSnapshot,
        pinnedTargets = pinnedTargets,
        onTogglePin = onTogglePin,
        onDetailProcessKeysChanged = onDetailProcessKeysChanged,
        headerContent = {
            CompactBackendStatus(state = shizukuState, onPrimaryAction = onPrimaryAction)
            SessionControlCard(
                snapshot = monitorSnapshot,
                backendReady = sessionBackendReady,
                previewRunning = samplingPreview is SamplingPreviewState.Running,
                notificationPermissionDenied = notificationPermissionDenied,
                monitorStartFailed = monitorStartFailed,
                monitorStorageFailed = monitorStorageFailed,
                partialInternalBuild = partialInternalBuild,
                onStart = onStartSession,
                onPause = onPauseSession,
                onResume = onResumeSession,
                onStop = onStopSession,
            )
        },
    )
}

@Composable
private fun CompactBackendStatus(
    state: ShizukuUiState,
    onPrimaryAction: () -> Unit,
) {
    val copy = statusCopy(state)
    val busy = state.phase in setOf(
        ShizukuPhase.CHECKING,
        ShizukuPhase.CONNECTING,
        ShizukuPhase.PROBING,
    )
    val ready = state.phase == ShizukuPhase.AVAILABLE || state.phase == ShizukuPhase.PARTIAL
    val summary = state.report?.takeIf { it.metricCoverageReferenceCount > 0 }?.let {
        stringResource(R.string.live_backend_coverage, (it.metricCoverage * 100).toInt())
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Surface(
                    modifier = Modifier.size(9.dp),
                    shape = CircleShape,
                    color = copy.indicatorColor,
                ) {}
            }
            Text(
                text = buildString {
                    append(stringResource(copy.titleRes))
                    summary?.let { append(" · ").append(it) }
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!ready) {
                copy.actionRes?.let { actionRes ->
                    TextButton(onClick = onPrimaryAction) {
                        Text(stringResource(actionRes), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: ShizukuUiState,
    onPrimaryAction: () -> Unit,
) {
    val copy = statusCopy(state)
    val busy = state.phase in setOf(
        ShizukuPhase.CHECKING,
        ShizukuPhase.CONNECTING,
        ShizukuPhase.PROBING,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = CircleShape,
                        color = copy.indicatorColor,
                    ) {}
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(copy.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(copy.bodyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            copy.actionRes?.let { actionRes ->
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    onClick = onPrimaryAction,
                ) {
                    Text(stringResource(actionRes))
                }
            }
        }
    }
}

@Composable
private fun statusCopy(state: ShizukuUiState): StatusCopy = when (state.phase) {
    ShizukuPhase.CHECKING -> StatusCopy(
        R.string.shizuku_checking_title,
        R.string.shizuku_checking_body,
        null,
        MaterialTheme.colorScheme.primary,
    )
    ShizukuPhase.NOT_INSTALLED -> StatusCopy(
        R.string.shizuku_not_installed_title,
        R.string.shizuku_not_installed_body,
        R.string.action_install_shizuku,
        MaterialTheme.colorScheme.error,
    )
    ShizukuPhase.NOT_RUNNING -> StatusCopy(
        R.string.shizuku_not_running_title,
        R.string.shizuku_not_running_body,
        R.string.action_open_shizuku,
        MaterialTheme.colorScheme.error,
    )
    ShizukuPhase.INCOMPATIBLE -> StatusCopy(
        R.string.shizuku_incompatible_title,
        R.string.shizuku_incompatible_body,
        R.string.action_recheck,
        MaterialTheme.colorScheme.error,
    )
    ShizukuPhase.PERMISSION_REQUIRED -> StatusCopy(
        R.string.shizuku_permission_title,
        R.string.shizuku_permission_body,
        R.string.action_grant_read_access,
        MaterialTheme.colorScheme.primary,
    )
    ShizukuPhase.PERMISSION_DENIED -> StatusCopy(
        R.string.shizuku_denied_title,
        R.string.shizuku_denied_body,
        R.string.action_grant_read_access,
        MaterialTheme.colorScheme.error,
    )
    ShizukuPhase.CONNECTING -> StatusCopy(
        R.string.shizuku_connecting_title,
        R.string.shizuku_connecting_body,
        null,
        MaterialTheme.colorScheme.primary,
    )
    ShizukuPhase.PROBING -> StatusCopy(
        R.string.shizuku_probing_title,
        R.string.shizuku_probing_body,
        R.string.action_cancel,
        MaterialTheme.colorScheme.primary,
    )
    ShizukuPhase.AVAILABLE -> StatusCopy(
        R.string.shizuku_available_title,
        R.string.shizuku_available_body,
        R.string.action_recheck,
        MaterialTheme.colorScheme.tertiary,
    )
    ShizukuPhase.PARTIAL -> StatusCopy(
        R.string.shizuku_partial_title,
        R.string.shizuku_partial_body,
        R.string.action_recheck,
        MaterialTheme.colorScheme.secondary,
    )
    ShizukuPhase.ERROR -> if (state.failure == ShizukuFailure.PROBE_CANCELLED) {
        StatusCopy(
            R.string.shizuku_cancelled_title,
            R.string.shizuku_cancelled_body,
            R.string.action_retry,
            MaterialTheme.colorScheme.outline,
        )
    } else {
        StatusCopy(
            R.string.shizuku_error_title,
            failureBody(state.failure),
            R.string.action_retry,
            MaterialTheme.colorScheme.error,
        )
    }
}

@androidx.annotation.StringRes
private fun failureBody(failure: ShizukuFailure): Int = when (failure) {
    ShizukuFailure.API_TOO_OLD -> R.string.shizuku_incompatible_body
    ShizukuFailure.BIND_TIMEOUT -> R.string.shizuku_bind_timeout_body
    ShizukuFailure.PROTOCOL_MISMATCH -> R.string.shizuku_protocol_mismatch_body
    ShizukuFailure.PROBE_CANCELLED -> R.string.shizuku_cancelled_body
    ShizukuFailure.BIND_FAILED,
    ShizukuFailure.INVALID_BINDER,
    -> R.string.shizuku_bind_failed_body
    ShizukuFailure.PROBE_FAILED,
    ShizukuFailure.NONE,
    -> R.string.shizuku_probe_failed_body
}

private data class StatusCopy(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val bodyRes: Int,
    @androidx.annotation.StringRes val actionRes: Int?,
    val indicatorColor: Color,
)

@Composable
private fun CapabilityReportCard(
    report: CapabilityReport,
    onShare: () -> Unit,
) {
    // Never round a below-threshold result up to the displayed release gate.
    val coveragePercent = (report.metricCoverage * 100).toInt().coerceIn(0, 100)
    val coverageDetails = if (report.metricCoverageReferenceCount > 0) {
        stringResource(
            R.string.value_coverage,
            coveragePercent,
            report.cpuAndRssReadableCount,
            report.metricCoverageReferenceCount,
        )
    } else {
        stringResource(R.string.value_unavailable)
    }
    val mode = when (report.backendMode) {
        BackendMode.ADB -> stringResource(R.string.backend_adb)
        BackendMode.ROOT -> stringResource(R.string.backend_root)
        BackendMode.UNKNOWN -> stringResource(R.string.backend_unknown)
    }
    val singlePssProbe = report.pssProbeKb?.let { value ->
        stringResource(R.string.value_pss_single_probe, value, report.pssProbeDurationMs)
    } ?: stringResource(R.string.value_unavailable)
    val pss = if (report.pssCommandAvailable) {
        stringResource(
            R.string.value_pss_probe,
            report.pssReadableCount,
            singlePssProbe,
            report.pssBatchProbeDurationMs,
        )
    } else {
        stringResource(R.string.value_unavailable)
    }
    val thermal = if (report.thermalSensorNames.isEmpty()) {
        stringResource(R.string.value_unavailable)
    } else {
        report.thermalSensorNames.take(3).joinToString()
    }
    val selectedMetricSource = stringResource(
        if (report.psFallbackSelected) {
            R.string.data_source_ps_fallback
        } else {
            R.string.data_source_procfs
        },
    )
    val selectedMetricSourceDetails = if (report.psFallbackSelected) {
        stringResource(
            R.string.value_capability_source,
            selectedMetricSource,
            report.psSnapshotCpuAndRssReadableCount,
            report.psSnapshotPidCount,
            report.psSnapshotDurationMs,
        )
    } else {
        stringResource(
            R.string.value_capability_source,
            selectedMetricSource,
            report.cpuAndRssReadableCount,
            report.metricCoverageReferenceCount.takeIf { it > 0 } ?: report.procPidCount,
            report.procScanDurationMs,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                text = stringResource(R.string.capability_report_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_last_checked),
                value = formatDateTime(report.probedAtWallTimeMs),
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_backend),
                value = stringResource(R.string.value_backend, mode, report.serviceUid),
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_enumeration),
                value = stringResource(R.string.value_proc_ps_count, report.procPidCount, report.psPidCount),
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_metric_source),
                value = selectedMetricSourceDetails,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_metric_coverage),
                value = coverageDetails,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_metric_readability),
                value = stringResource(
                    R.string.value_metric_readability,
                    report.metricCoverageReferenceCount,
                    report.statReadableCount,
                    report.rssReadableCount,
                    report.pssReadableCount,
                ),
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_proc_readability),
                value = stringResource(
                    R.string.value_proc_readability,
                    report.statReadableCount,
                    report.statusReadableCount,
                    report.cmdlineReadableCount,
                ),
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(label = stringResource(R.string.capability_pss), value = pss)
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(label = stringResource(R.string.capability_thermal), value = thermal)
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_package_mapping),
                value = stringResource(
                    R.string.value_package_mapping,
                    report.mappedUidCount,
                    report.sampledUidCount,
                    report.packageCandidateCount,
                ),
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(
                label = stringResource(R.string.capability_probe_cost),
                value = stringResource(
                    R.string.value_probe_cost,
                    report.procScanDurationMs,
                    report.totalDurationMs,
                ),
            )
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 48.dp),
                onClick = onShare,
            ) {
                Text(stringResource(R.string.action_share_capability_report))
            }
        }
    }
}

@Composable
private fun SessionControlCard(
    snapshot: MonitorRuntimeSnapshot,
    backendReady: Boolean,
    previewRunning: Boolean,
    notificationPermissionDenied: Boolean,
    monitorStartFailed: Boolean,
    monitorStorageFailed: Boolean,
    partialInternalBuild: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val machine = snapshot.machineState
    val displayedPhase = resolveDisplayedMonitorPhase(machine, backendReady)
    val elapsedSeconds by produceState(
        initialValue = snapshot.startedElapsedRealtimeNanos?.let { started ->
            ((android.os.SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(0L) /
                1_000_000_000L)
        } ?: 0L,
        snapshot.startedElapsedRealtimeNanos,
        machine.hasActiveSession,
    ) {
        while (machine.hasActiveSession) {
            value = snapshot.startedElapsedRealtimeNanos?.let { started ->
                ((android.os.SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(0L) /
                    1_000_000_000L)
            } ?: 0L
            delay(1_000L)
        }
    }
    val status = when (displayedPhase) {
        MonitorPhase.NOT_READY -> stringResource(R.string.monitor_status_not_ready)
        MonitorPhase.READY -> stringResource(R.string.monitor_status_ready)
        MonitorPhase.STARTING -> stringResource(R.string.monitor_status_starting)
        MonitorPhase.RUNNING -> stringResource(R.string.monitor_status_running)
        MonitorPhase.COMPLETED -> stringResource(R.string.monitor_status_completed)
        MonitorPhase.INTERRUPTED -> stringResource(R.string.monitor_status_interrupted)
        MonitorPhase.PAUSED -> when (machine.pauseReason) {
            PauseReason.USER -> stringResource(R.string.monitor_status_paused_user)
            PauseReason.SHIZUKU -> stringResource(R.string.monitor_status_paused_shizuku)
            PauseReason.STORAGE -> stringResource(R.string.monitor_status_paused_storage)
            null -> stringResource(R.string.monitor_status_paused_user)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = snapshot.sessionName ?: stringResource(R.string.monitor_session_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = CircleShape,
                    color = when (displayedPhase) {
                        MonitorPhase.RUNNING -> MaterialTheme.colorScheme.tertiary
                        MonitorPhase.STARTING,
                        MonitorPhase.READY,
                        -> MaterialTheme.colorScheme.primary
                        MonitorPhase.PAUSED -> MaterialTheme.colorScheme.secondary
                        MonitorPhase.NOT_READY,
                        MonitorPhase.INTERRUPTED,
                        -> MaterialTheme.colorScheme.error
                        MonitorPhase.COMPLETED -> MaterialTheme.colorScheme.outline
                    },
                ) {}
            }
            if (machine.hasActiveSession) {
                Text(
                    text = stringResource(
                        R.string.monitor_session_compact_summary,
                        formatDuration(elapsedSeconds),
                        snapshot.frameCount,
                        snapshot.effectiveIntervalMs,
                    ) + if (snapshot.wakeLockHeld) {
                        " · ${stringResource(R.string.monitor_session_wake_lock)}"
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val showPauseToggle = machine.phase == MonitorPhase.STARTING ||
                        machine.phase == MonitorPhase.RUNNING ||
                        machine.pauseReason == PauseReason.USER ||
                        machine.pauseReason == PauseReason.STORAGE
                    if (showPauseToggle) {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            onClick = if (machine.phase == MonitorPhase.PAUSED) onResume else onPause,
                        ) {
                            Text(
                                stringResource(
                                    if (machine.phase == MonitorPhase.PAUSED) {
                                        if (machine.pauseReason == PauseReason.STORAGE) {
                                            R.string.action_retry_storage
                                        } else {
                                            R.string.action_resume_session
                                        }
                                    } else {
                                        R.string.action_pause_session
                                    },
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    OutlinedButton(
                        modifier = if (showPauseToggle) {
                            Modifier.weight(1f).heightIn(min = 48.dp)
                        } else {
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        },
                        onClick = onStop,
                    ) {
                        Text(
                            stringResource(R.string.action_stop_session),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            } else {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    enabled = backendReady && !previewRunning,
                    onClick = onStart,
                ) {
                    Text(
                        stringResource(R.string.action_start_session),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (notificationPermissionDenied) {
                Text(
                    text = stringResource(R.string.notification_permission_denied_explanation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (partialInternalBuild) {
                Text(
                    text = stringResource(R.string.partial_internal_build_explanation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (monitorStartFailed) {
                Text(
                    text = stringResource(R.string.monitor_start_failed_explanation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (monitorStorageFailed) {
                Text(
                    text = stringResource(R.string.monitor_storage_start_failed_explanation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The monitor service is intentionally dormant while no session exists, so its application-wide
 * runtime snapshot can remain NOT_READY/READY after the capability UI has fresher information.
 * Terminal states are retained because they describe the last session, and active states always
 * come from the service-owned state machine.
 */
internal fun resolveDisplayedMonitorPhase(
    machine: SessionMachineState,
    backendReady: Boolean,
): MonitorPhase = if (
    !machine.hasActiveSession &&
    (machine.phase == MonitorPhase.NOT_READY || machine.phase == MonitorPhase.READY)
) {
    if (backendReady) MonitorPhase.READY else MonitorPhase.NOT_READY
} else {
    machine.phase
}

private fun formatDuration(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3_600L
    val minutes = (safe % 3_600L) / 60L
    val seconds = safe % 60L
    return String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatDateTime(wallTimeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(wallTimeMillis))

@Composable
private fun SamplingPreviewCard(
    state: SamplingPreviewState,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                text = stringResource(R.string.sampling_preview_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            when (state) {
                SamplingPreviewState.Idle -> Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(R.string.sampling_preview_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SamplingPreviewState.Running -> Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.sampling_preview_running),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is SamplingPreviewState.Failed -> Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(R.string.sampling_preview_failed, state.errorType),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is SamplingPreviewState.Ready -> {
                    val result = state.result
                    MetricRow(
                        label = stringResource(R.string.sampling_preview_frame),
                        value = stringResource(
                            R.string.value_sampling_frame,
                            result.sequence,
                            result.intervalMs,
                        ),
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    MetricRow(
                        label = stringResource(R.string.sampling_preview_values),
                        value = stringResource(
                            R.string.value_sampling_values,
                            result.processCount,
                            result.cpuValueCount,
                            result.rssValueCount,
                            result.pssValueCount,
                        ),
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    MetricRow(
                        label = stringResource(R.string.sampling_preview_catalog),
                        value = stringResource(
                            R.string.value_sampling_catalog,
                            result.catalogCount,
                            result.applicationCount,
                            result.catalogRevision,
                        ),
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    MetricRow(
                        label = stringResource(R.string.sampling_preview_transport),
                        value = stringResource(
                            R.string.value_sampling_transport,
                            when (result.source) {
                                MetricDataSource.PROCFS ->
                                    stringResource(R.string.data_source_procfs)
                                MetricDataSource.PS_FALLBACK ->
                                    stringResource(R.string.data_source_ps_fallback)
                            },
                            result.collectionDurationMs,
                            result.frameFlags,
                        ),
                    )
                }
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 48.dp),
                enabled = enabled && state !is SamplingPreviewState.Running,
                onClick = onRun,
            ) {
                Text(
                    stringResource(
                        if (state is SamplingPreviewState.Ready) {
                            R.string.action_run_sampling_preview_again
                        } else {
                            R.string.action_run_sampling_preview
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun MetricGroup(
    systemCpuPercentBasisPoints: Int?,
    memoryTotalKb: Long?,
    memoryAvailableKb: Long?,
) {
    val cpuValue = systemCpuPercentBasisPoints?.let { basisPoints ->
        stringResource(R.string.value_percent, basisPoints / 100.0)
    } ?: "—"
    val memoryValue = if (
        memoryTotalKb != null && memoryTotalKb > 0 && memoryAvailableKb != null
    ) {
        stringResource(
            R.string.value_percent,
            ((memoryTotalKb - memoryAvailableKb).coerceAtLeast(0L).toDouble() /
                memoryTotalKb.toDouble()) * 100.0,
        )
    } else {
        null
    } ?: "—"
    val summary = if (systemCpuPercentBasisPoints == null && memoryTotalKb == null) {
        stringResource(R.string.metric_summary_accessibility)
    } else {
        stringResource(R.string.metric_summary_value_accessibility, cpuValue, memoryValue)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = summary },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            MetricRow(label = stringResource(R.string.metric_cpu), value = cpuValue)
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(label = stringResource(R.string.metric_memory), value = memoryValue)
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.35f),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}
