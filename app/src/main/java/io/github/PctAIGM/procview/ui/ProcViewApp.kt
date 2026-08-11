package io.github.PctAIGM.procview.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.PctAIGM.procview.ProcViewApplication
import io.github.PctAIGM.procview.R
import io.github.PctAIGM.procview.diagnostics.shareCapabilityReport
import io.github.PctAIGM.procview.model.BackendMode
import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.ShizukuFailure
import io.github.PctAIGM.procview.model.ShizukuPhase
import io.github.PctAIGM.procview.model.ShizukuUiState
import kotlin.math.roundToInt

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
        factory = ShizukuProbeViewModel.Factory(application.shizukuCoordinator),
    )
    val shizukuState by probeViewModel.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(MainDestination.Live) }
    val title = stringResource(destination.label)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 0.dp,
            ) {
                MainDestination.entries.forEach { item ->
                    val selected = destination == item
                    NavigationBarItem(
                        selected = selected,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(item.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { contentPadding ->
        when (destination) {
            MainDestination.Live -> LiveBaseline(
                contentPadding = contentPadding,
                shizukuState = shizukuState,
                onPrimaryAction = probeViewModel::performPrimaryAction,
            )
            MainDestination.History -> EmptyDestination(
                contentPadding = contentPadding,
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body),
            )
            MainDestination.Settings -> EmptyDestination(
                contentPadding = contentPadding,
                title = stringResource(R.string.settings_baseline_title),
                body = stringResource(R.string.settings_baseline_body),
            )
        }
    }
}

@Composable
private fun LiveBaseline(
    contentPadding: PaddingValues,
    shizukuState: ShizukuUiState,
    onPrimaryAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusCard(state = shizukuState, onPrimaryAction = onPrimaryAction)
        shizukuState.report?.let { report ->
            val context = LocalContext.current
            CapabilityReportCard(
                report = report,
                onShare = { shareCapabilityReport(context, report) },
            )
        }
        MetricGroup()
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                        .height(48.dp),
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
        null,
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
    ShizukuPhase.ERROR -> StatusCopy(
        R.string.shizuku_error_title,
        failureBody(state.failure),
        R.string.action_retry,
        MaterialTheme.colorScheme.error,
    )
}

@androidx.annotation.StringRes
private fun failureBody(failure: ShizukuFailure): Int = when (failure) {
    ShizukuFailure.API_TOO_OLD -> R.string.shizuku_incompatible_body
    ShizukuFailure.BIND_TIMEOUT -> R.string.shizuku_bind_timeout_body
    ShizukuFailure.PROTOCOL_MISMATCH -> R.string.shizuku_protocol_mismatch_body
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
    val coveragePercent = (report.metricCoverage * 100).roundToInt().coerceIn(0, 100)
    val mode = when (report.backendMode) {
        BackendMode.ADB -> stringResource(R.string.backend_adb)
        BackendMode.ROOT -> stringResource(R.string.backend_root)
        BackendMode.UNKNOWN -> stringResource(R.string.backend_unknown)
    }
    val pss = report.pssProbeKb?.let { value ->
        stringResource(R.string.value_pss_probe, value, report.pssProbeDurationMs)
    } ?: stringResource(R.string.value_unavailable)
    val thermal = if (report.thermalSensorNames.isEmpty()) {
        stringResource(R.string.value_unavailable)
    } else {
        report.thermalSensorNames.take(3).joinToString()
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
                label = stringResource(R.string.capability_metric_coverage),
                value = stringResource(
                    R.string.value_coverage,
                    coveragePercent,
                    report.cpuAndRssReadableCount,
                    report.procPidCount,
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
                    .height(48.dp),
                onClick = onShare,
            ) {
                Text(stringResource(R.string.action_share_capability_report))
            }
        }
    }
}

@Composable
private fun MetricGroup() {
    val summary = stringResource(R.string.metric_summary_accessibility)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = summary },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            MetricRow(label = stringResource(R.string.metric_cpu), value = "—")
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            MetricRow(label = stringResource(R.string.metric_memory), value = "—")
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
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyDestination(
    contentPadding: PaddingValues,
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
